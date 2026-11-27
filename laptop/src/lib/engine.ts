// The reminder engine. It runs once, in the hidden "cards" window: it keeps tasks in step with the server,
// rings reminders, answers the other windows, and broadcasts its state to them.
//
// Laptop first: a reminder pops up here at its time. The phone waits 5 min and checks reminder_acks;
// anything done here (Done / Snooze / Tomorrow / Dismiss) is written there, so the phone stays quiet.
// Answers given on the phone close the pop-up here.

import { emit, listen } from "@tauri-apps/api/event";
import { invoke } from "@tauri-apps/api/core";
import { WebviewWindow } from "@tauri-apps/api/webviewWindow";
import { LogicalPosition, LogicalSize } from "@tauri-apps/api/dpi";
import * as cloud from "./cloud";
import { writeText } from "@tauri-apps/plugin-clipboard-manager";
import { loadSettings } from "./settings";
import { cachedPlans, cachePlans, chosenLists, type PlanInfo } from "./lists";
import { toTask, completeRows, skipRow, tomorrowRow, snoozeRow, reopenRow, deleteRow, editRow, type Row, type Task, type TaskPatch } from "./tasks";

/** A reminder showing on screen. [base] is the task's remind_at, the key acks are kept under. */
export interface Ring {
  taskId: string;
  title: string;
  base: number;
  at: number;
  missed: boolean;
  repeats: boolean;
}

/** A one-time code from the phone, on screen for a few minutes. */
export interface OtpCard {
  id: string;
  code: string;
  source: string;
  at: number;
}

/** What every window gets to draw from. */
export interface EngineState {
  tasks: Row[];
  rings: Ring[];
  otps: OtpCard[];
  parcels: cloud.ParcelInfo[];
  online: boolean;
  signedIn: boolean;
  email: string | null;
  queued: number;
  /** Writes the server refused for good (kept aside). */
  failed: number;
  /** Every list (plan), for the settings. */
  plans: Array<{ id: string; name: string }>;
  /** The lists this laptop shows (ids); empty while unknown. */
  lists: string[];
}

/** Actions the other windows ask the engine to do. */
export type Action =
  | { type: "done"; ring: Ring }
  | { type: "snooze"; ring: Ring; minutes: number }
  | { type: "tomorrow"; ring: Ring }
  | { type: "dismiss"; ring: Ring }
  | { type: "complete"; taskId: string }
  | { type: "reopen"; taskId: string }
  | { type: "delete"; taskId: string }
  | { type: "update"; taskId: string; patch: TaskPatch }
  | { type: "addRow"; row: Row }
  | { type: "addNote"; row: Row }
  | { type: "otpClose"; id: string }
  | { type: "refresh" };

interface Memory {
  /** taskId → the time it last rang here (rings once per time). */
  fired: Record<string, number>;
  /** taskId → "base:until" snooze. */
  snoozes: Record<string, string>;
  lastAck: number;
  initialised: boolean;
  /** The lists last told to the phone, and the item that holds them. */
  sharedLists?: string;
  listsItemId?: string;
}

const MEMORY = "buddy-reminders";
const RING_GRACE_MS = 10 * 60_000;
const MISSED_WINDOW_MS = 12 * 60 * 60_000;
/** How long a one-time code stays on screen. */
const OTP_SHOW_MS = 3 * 60_000;
/** The longest one engine job may take before the next one goes ahead. */
const JOB_LIMIT_MS = 60_000;

function readMemory(): Memory {
  try {
    const m = JSON.parse(localStorage.getItem(MEMORY) ?? "null");
    if (m) return { fired: {}, snoozes: {}, lastAck: 0, initialised: false, ...m };
  } catch {
    /* start fresh */
  }
  return { fired: {}, snoozes: {}, lastAck: 0, initialised: false };
}

export class Engine {
  private tasks: Task[] = [];
  private rings: Ring[] = [];
  private otps: OtpCard[] = [];
  private seenOtps = new Set<string>();
  private parcels: cloud.ParcelInfo[] = [];
  private online = true;
  private signedIn = false;
  private memory: Memory = readMemory();
  private plans: PlanInfo[] = cachedPlans();
  private deviceName = "Laptop";
  private busy: Promise<void> = Promise.resolve();

  constructor(private deviceId: string) {}

  /** Runs the engine: polling, reminders, and answers from the other windows. */
  async start(): Promise<void> {
    this.deviceName = (await invoke<string>("device_name").catch(() => "Laptop")) || "Laptop";
    await listen<Action>("buddy-action", (e) => this.receive(e.payload));
    // The reminder cards live in this same window and hand their buttons over directly.
    window.addEventListener("buddy-action-local", (e) => this.receive((e as CustomEvent<Action>).detail));
    // Never wait inside the sign-in library's callback (it waits for us in turn): just schedule a refresh.
    cloud.supabase.auth.onAuthStateChange(() => {
      setTimeout(() => void this.queue(() => this.refresh()), 0);
    });
    setInterval(() => this.queue(() => this.refresh()), 30_000);
    setInterval(() => this.queue(() => this.checkAcks()), 10_000);
    setInterval(() => this.queue(() => this.beat()), 55_000);
    setInterval(() => this.tick(), 2_000);
    // Codes from the phone: pushed the moment they arrive, with a slow check as a backup.
    cloud.watchOtps(() => void this.checkOtps());
    setInterval(() => void this.checkOtps(), 20_000);
    await this.queue(() => this.refresh());
    await this.queue(() => this.beat());
  }

  /**
   * One thing at a time, so two refreshes never interleave. Each job gets [JOB_LIMIT_MS]: a job that hangs
   * (a request lost while the laptop slept) is given up on, so it can never freeze syncing and reminders.
   */
  private queue(job: () => Promise<void>): Promise<void> {
    const limited = () =>
      new Promise<void>((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error("engine job took too long; moving on")), JOB_LIMIT_MS);
        job().then(
          () => {
            clearTimeout(timer);
            resolve();
          },
          (e) => {
            clearTimeout(timer);
            reject(e);
          },
        );
      });
    this.busy = this.busy.then(limited).catch((e) => console.warn("engine", e));
    return this.busy;
  }

  private async beat(): Promise<void> {
    if (!(await this.hasSession())) return;
    await cloud.heartbeat(this.deviceId, this.deviceName).catch(() => undefined);
  }

  private async hasSession(): Promise<boolean> {
    const s = await cloud.currentSession();
    this.signedIn = s !== null;
    return this.signedIn;
  }

  /** Reads tasks (and flushes queued writes), then tells the other windows. */
  async refresh(): Promise<void> {
    if (!(await this.hasSession())) {
      this.tasks = [];
      this.rings = [];
      this.publish();
      return;
    }
    void cloud.watchPokes(() => void this.queue(() => this.refresh())).catch(() => undefined);
    const plans = await cloud.plans().catch(() => null);
    if (plans && plans.length > 0) {
      this.plans = plans;
      cachePlans(plans);
    }
    this.fileUnfiled();
    this.shareLists();
    await cloud.flush().catch(() => undefined);
    try {
      const rows = await cloud.openTasks();
      this.online = true;
      // Changes still waiting to be sent win over what the server has, so the screen never jumps back.
      const byId = new Map(rows.map((r) => [r.id, r]));
      for (const p of cloud.pending("tasks")) {
        if (p.deleted_at) byId.delete(p.id);
        else byId.set(p.id, { ...(byId.get(p.id) ?? {}), ...p });
      }
      this.tasks = [...byId.values()].map(toTask);
      if (!this.memory.initialised) {
        // First run: don't pop up every past reminder.
        const now = Date.now();
        for (const t of this.tasks) if (t.remindAt !== null && t.remindAt <= now) this.memory.fired[t.id] = t.remindAt;
        this.memory.initialised = true;
      }
      await this.checkAcks();
      this.parcels = await cloud.activeParcels().catch(() => this.parcels);
      // Finished or deleted elsewhere: close its pop-up.
      const open = new Map(this.tasks.filter((t) => !t.isDone).map((t) => [t.id, t]));
      this.rings = this.rings.filter((r) => {
        const t = open.get(r.taskId);
        return t !== undefined && t.remindAt === r.base && this.shows(t);
      });
    } catch {
      this.online = false;
    }
    this.saveMemory();
    this.publish();
  }

  /** Answers made on other devices (the phone, or this laptop before a restart). */
  private async checkAcks(): Promise<void> {
    if (!(await this.hasSession())) return;
    try {
      const acks = await cloud.recentAcks(new Date(Math.max(this.memory.lastAck, Date.now() - 60 * 60_000)));
      for (const a of acks) {
        const at = Date.parse(a.acked_at);
        if (at <= this.memory.lastAck) continue;
        this.memory.lastAck = at;
        if (a.device === `laptop:${this.deviceName}`) continue;
        const base = Date.parse(a.remind_at);
        this.rings = this.rings.filter((r) => !(r.taskId === a.task_id && r.base === base));
        const task = this.tasks.find((t) => t.id === a.task_id);
        if (!task || task.remindAt !== base) continue;
        if (a.action === "snoozed" && a.snooze_until) {
          this.memory.snoozes[task.id] = `${base}:${Date.parse(a.snooze_until)}`;
        } else {
          this.memory.fired[task.id] = this.effective(task) ?? base;
        }
      }
      this.saveMemory();
      this.publish();
    } catch {
      /* try again on the next check */
    }
  }

  private effective(t: Task): number | null {
    if (t.remindAt === null) return null;
    const s = this.memory.snoozes[t.id]?.split(":");
    if (s && s.length === 2 && Number(s[0]) === t.remindAt) return Number(s[1]);
    return t.remindAt;
  }

  /** New codes from the phone: show, copy, and delete them from the server straight away. */
  private async checkOtps(): Promise<void> {
    if (!this.signedIn) return;
    let rows: cloud.Otp[];
    try {
      rows = await cloud.freshOtps();
    } catch {
      return;
    }
    let added = false;
    for (const o of rows) {
      void cloud.deleteOtp(o.id).catch(() => undefined);
      if (this.seenOtps.has(o.id)) continue;
      this.seenOtps.add(o.id);
      this.otps = [...this.otps.filter((x) => x.code !== o.code), { id: o.id, code: o.code, source: o.source ?? "SMS", at: Date.now() }];
      added = true;
    }
    if (!added) return;
    const latest = this.otps[this.otps.length - 1];
    await writeText(latest.code).catch(() => undefined);
    this.publish();
    await this.announce();
  }

  /** Looks at every task once a couple of seconds: rings what's due. */
  private tick(): void {
    const now = Date.now();
    let changed = false;
    if (this.otps.some((o) => now - o.at > OTP_SHOW_MS)) {
      this.otps = this.otps.filter((o) => now - o.at <= OTP_SHOW_MS);
      this.publish();
    }
    for (const t of this.tasks) {
      if (t.isDone || t.remindAt === null || !this.shows(t)) continue;
      const at = this.effective(t);
      if (at === null || at > now || this.memory.fired[t.id] === at) continue;
      this.memory.fired[t.id] = at;
      changed = true;
      if (at < now - MISSED_WINDOW_MS) continue;
      const missed = at < now - RING_GRACE_MS;
      this.rings = this.rings.filter((r) => r.taskId !== t.id);
      this.rings.push({ taskId: t.id, title: t.title, base: t.remindAt, at, missed, repeats: t.repeat !== null });
      if (!missed) void this.announce();
      this.publish();
    }
    if (changed) this.saveMemory();
  }

  private saveMemory(): void {
    try {
      localStorage.setItem(MEMORY, JSON.stringify(this.memory));
    } catch {
      /* ignore */
    }
  }

  /** Shows the cards window and plays a notification sound (once). */
  private async announce(): Promise<void> {
    const settings = loadSettings();
    if (settings.sound) await invoke("notify_sound").catch(() => undefined);
    await showCards(this.rings.length + this.otps.length);
  }

  // ---------------------------------------------------------------- lists

  /** The lists this laptop is about (settings, else Work); null while they aren't known. */
  private lists(): Set<string> | null {
    const ids = chosenLists(this.plans, loadSettings().lists);
    return ids ? new Set(ids) : null;
  }

  private shows(t: Task): boolean {
    const l = this.lists();
    return l === null || l.has(t.row.plan_id as string);
  }

  /** Where something added on the laptop goes: the first chosen list's first column, at the bottom. */
  private filing(): { planId: string | null; bucketId: string | null; position: number } {
    const ids = chosenLists(this.plans, loadSettings().lists);
    const plan = ids ? this.plans.find((p) => p.id === ids[0]) : undefined;
    if (!plan) return { planId: null, bucketId: null, position: 0 };
    const positions = this.tasks.filter((t) => t.row.bucket_id === plan.bucketId).map((t) => Number(t.row.position) || 0);
    return { planId: plan.id, bucketId: plan.bucketId, position: Math.max(0, ...positions) + 1 };
  }

  /** Added before the lists were known (first start, offline): file them now, before they're sent. */
  private fileUnfiled(): void {
    const f = this.filing();
    if (!f.planId) return;
    for (const row of cloud.pending("tasks")) {
      if (row.plan_id || row.deleted_at) continue;
      cloud.enqueue("tasks", { ...row, plan_id: f.planId, bucket_id: f.bucketId, position: f.position });
    }
  }

  /** Tells the phone which lists ring here first (it rings the others straight away). */
  private shareLists(): void {
    const l = this.lists();
    if (!l) return;
    const ids = [...l].sort();
    const key = ids.join(",");
    if (this.memory.sharedLists === key) return;
    this.memory.listsItemId ??= crypto.randomUUID();
    const now = new Date().toISOString();
    cloud.enqueue("items", {
      id: this.memory.listsItemId,
      kind: "laptop_lists",
      data: { plans: ids, device: this.deviceName },
      occurred_on: null,
      ref_id: null,
      created_at: now,
      updated_at: now,
      deleted_at: null,
    });
    this.memory.sharedLists = key;
    this.saveMemory();
  }

  private publish(): void {
    const state: EngineState = {
      tasks: this.tasks.filter((t) => this.shows(t)).map((t) => t.row),
      rings: this.rings,
      otps: this.otps,
      parcels: this.parcels,
      online: this.online,
      signedIn: this.signedIn,
      email: null,
      queued: cloud.queued(),
      failed: cloud.failedWrites(),
      plans: this.plans.map(({ id, name }) => ({ id, name })),
      lists: [...(this.lists() ?? [])],
    };
    void emit("buddy-state", state);
    if (this.rings.length === 0 && this.otps.length === 0) void hideCards();
  }

  // ---------------------------------------------------------------- actions

  private findRing(r: Ring): Ring | undefined {
    return this.rings.find((x) => x.taskId === r.taskId && x.base === r.base);
  }

  private closeRing(r: Ring): void {
    this.rings = this.rings.filter((x) => !(x.taskId === r.taskId && x.base === r.base));
  }

  /**
   * A button was pressed or something was added. It is saved in the outbox right here (so a restart or a
   * lost connection can't lose it), shown at once, and sent to the server straight after.
   */
  private receive(a: Action): void {
    if (a.type === "otpClose") {
      this.otps = this.otps.filter((o) => o.id !== a.id);
      this.publish();
      return;
    }
    if (a.type !== "refresh") {
      try {
        this.save(a);
      } catch (e) {
        console.warn("engine: couldn't save", e);
      }
      this.saveMemory();
      this.publish();
    }
    void this.queue(() => this.refresh());
  }

  /** Puts the writes for [a] in the outbox and applies them to what's on screen. */
  private save(a: Exclude<Action, { type: "otpClose" } | { type: "refresh" }>): void {
    const device = `laptop:${this.deviceName}`;
    const task = (id: string) => this.tasks.find((t) => t.id === id);
    const put = (row: Row) => {
      cloud.enqueue("tasks", row);
      this.tasks = this.tasks.filter((t) => t.id !== row.id);
      if (!row.deleted_at) this.tasks.push(toTask(row));
    };
    switch (a.type) {
      case "done":
      case "snooze":
      case "tomorrow":
      case "dismiss": {
        const r = this.findRing(a.ring) ?? a.ring;
        this.closeRing(r);
        const t = task(r.taskId);
        if (a.type === "snooze") {
          const until = Date.now() + a.minutes * 60_000;
          // Tell the phone first (keyed on the old time), so it stays quiet now.
          cloud.ack(r.taskId, r.base, "snoozed", device, until);
          if (t && t.repeat === null) {
            // A one-off reminder moves to the new time everywhere: the lists show it, and laptop then
            // phone ring then as usual.
            put(snoozeRow(t, until));
          } else if (t) {
            // Repeating ones keep their schedule; the snooze is remembered on each device.
            this.memory.snoozes[t.id] = `${r.base}:${until}`;
          }
          break;
        }
        if (t) {
          if (a.type === "done") completeRows(t).forEach(put);
          if (a.type === "tomorrow") put(tomorrowRow(t));
          if (a.type === "dismiss") {
            const row = skipRow(t);
            if (row) put(row);
          }
        }
        cloud.ack(r.taskId, r.base, a.type === "done" ? "done" : a.type === "tomorrow" ? "moved" : "dismissed", device);
        break;
      }
      case "complete": {
        const t = task(a.taskId);
        if (t) completeRows(t).forEach(put);
        this.rings = this.rings.filter((r) => r.taskId !== a.taskId);
        break;
      }
      case "reopen": {
        const t = task(a.taskId);
        if (t) put(reopenRow(t));
        break;
      }
      case "delete": {
        const t = task(a.taskId);
        if (t) put(deleteRow(t));
        this.rings = this.rings.filter((r) => r.taskId !== a.taskId);
        break;
      }
      case "update": {
        const t = task(a.taskId);
        if (!t) break;
        const row = editRow(t, a.patch);
        put(row);
        if ("remind_at" in a.patch && a.patch.remind_at !== t.row.remind_at) {
          // A new reminder time: forget any snooze of the old one and close its card; it rings at the new time.
          delete this.memory.snoozes[t.id];
          this.rings = this.rings.filter((r) => r.taskId !== t.id);
        }
        break;
      }
      case "addRow": {
        // Filed here, from what the engine already knows: saving never waits on the network.
        const f = this.filing();
        put(a.row.plan_id ? a.row : { ...a.row, plan_id: f.planId, bucket_id: f.bucketId, position: f.position });
        break;
      }
      case "addNote":
        cloud.enqueue("items", a.row);
        break;
    }
  }
}

// ---------------------------------------------------------------- cards window

async function cardsWindow(): Promise<WebviewWindow | null> {
  return WebviewWindow.getByLabel("cards");
}

/** Pins the cards to the bottom-right corner and shows them. Sized to how many there are. */
export async function showCards(count: number): Promise<void> {
  const w = await cardsWindow();
  if (!w) return;
  const width = 340;
  const height = count > 1 ? 330 : 210;
  const screenW = window.screen.availWidth;
  const screenH = window.screen.availHeight;
  await w.setSize(new LogicalSize(width, height));
  await w.setPosition(new LogicalPosition(screenW - width - 16, screenH - height - 16));
  await w.show();
}

export async function hideCards(): Promise<void> {
  const w = await cardsWindow();
  await w?.hide();
}
