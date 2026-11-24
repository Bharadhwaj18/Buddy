// The reminder engine. It runs once, in the hidden "cards" window: it keeps tasks in step with the server,
// rings reminders, answers the other windows, and broadcasts its state to them.
//
// Laptop first: a reminder pops up here at its time. The phone waits 30 s and checks reminder_acks;
// anything done here (Done / Snooze / Tomorrow / Dismiss) is written there, so the phone stays quiet.
// Answers given on the phone close the pop-up here.

import { emit, listen } from "@tauri-apps/api/event";
import { invoke } from "@tauri-apps/api/core";
import { WebviewWindow } from "@tauri-apps/api/webviewWindow";
import { LogicalPosition, LogicalSize } from "@tauri-apps/api/dpi";
import * as cloud from "./cloud";
import { loadSettings } from "./settings";
import { toTask, completeRows, skipRow, tomorrowRow, snoozeRow, reopenRow, deleteRow, type Row, type Task } from "./tasks";

/** A reminder showing on screen. [base] is the task's remind_at, the key acks are kept under. */
export interface Ring {
  taskId: string;
  title: string;
  base: number;
  at: number;
  missed: boolean;
  repeats: boolean;
}

/** What every window gets to draw from. */
export interface EngineState {
  tasks: Row[];
  rings: Ring[];
  online: boolean;
  signedIn: boolean;
  email: string | null;
  queued: number;
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
  | { type: "addRow"; row: Row }
  | { type: "addNote"; row: Row }
  | { type: "refresh" };

interface Memory {
  /** taskId → the time it last rang here (rings once per time). */
  fired: Record<string, number>;
  /** taskId → "base:until" snooze. */
  snoozes: Record<string, string>;
  lastAck: number;
  initialised: boolean;
}

const MEMORY = "buddy-reminders";
const RING_GRACE_MS = 10 * 60_000;
const MISSED_WINDOW_MS = 12 * 60 * 60_000;

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
  private online = true;
  private signedIn = false;
  private memory: Memory = readMemory();
  private deviceName = "Laptop";
  private busy: Promise<void> = Promise.resolve();

  constructor(private deviceId: string) {}

  /** Runs the engine: polling, reminders, and answers from the other windows. */
  async start(): Promise<void> {
    this.deviceName = (await invoke<string>("device_name").catch(() => "Laptop")) || "Laptop";
    await listen<Action>("buddy-action", (e) => this.receive(e.payload));
    // The reminder cards live in this same window and hand their buttons over directly.
    window.addEventListener("buddy-action-local", (e) => this.receive((e as CustomEvent<Action>).detail));
    cloud.supabase.auth.onAuthStateChange(() => this.queue(() => this.refresh()));
    setInterval(() => this.queue(() => this.refresh()), 30_000);
    setInterval(() => this.queue(() => this.checkAcks()), 10_000);
    setInterval(() => this.queue(() => this.beat()), 55_000);
    setInterval(() => this.tick(), 2_000);
    await this.queue(() => this.refresh());
    await this.queue(() => this.beat());
  }

  /** One thing at a time, so two writes or refreshes never interleave. */
  private queue(job: () => Promise<void>): Promise<void> {
    this.busy = this.busy.then(job).catch((e) => console.warn("engine", e));
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
    await cloud.flush().catch(() => undefined);
    try {
      const rows = await cloud.openTasks();
      this.online = true;
      this.tasks = rows.map(toTask);
      if (!this.memory.initialised) {
        // First run: don't pop up every past reminder.
        const now = Date.now();
        for (const t of this.tasks) if (t.remindAt !== null && t.remindAt <= now) this.memory.fired[t.id] = t.remindAt;
        this.memory.initialised = true;
      }
      await this.checkAcks();
      // Finished or deleted elsewhere: close its pop-up.
      const open = new Map(this.tasks.filter((t) => !t.isDone).map((t) => [t.id, t]));
      this.rings = this.rings.filter((r) => open.get(r.taskId)?.remindAt === r.base);
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

  /** Looks at every task once a couple of seconds: rings what's due. */
  private tick(): void {
    const now = Date.now();
    let changed = false;
    for (const t of this.tasks) {
      if (t.isDone || t.remindAt === null) continue;
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
    await showCards(this.rings.length);
  }

  private publish(): void {
    const state: EngineState = {
      tasks: this.tasks.map((t) => t.row),
      rings: this.rings,
      online: this.online,
      signedIn: this.signedIn,
      email: null,
      queued: cloud.queued(),
    };
    void emit("buddy-state", state);
    if (this.rings.length === 0) void hideCards();
  }

  // ---------------------------------------------------------------- actions

  private findRing(r: Ring): Ring | undefined {
    return this.rings.find((x) => x.taskId === r.taskId && x.base === r.base);
  }

  private closeRing(r: Ring): void {
    this.rings = this.rings.filter((x) => !(x.taskId === r.taskId && x.base === r.base));
  }

  /**
   * A button was pressed. A reminder answer closes its card right now (not after the queued sync, which
   * could take a while and would redraw the old card meanwhile); the saving happens in the queue.
   */
  private receive(a: Action): void {
    if (a.type === "done" || a.type === "snooze" || a.type === "tomorrow" || a.type === "dismiss") {
      const r = this.findRing(a.ring) ?? a.ring;
      this.closeRing(r);
      const t = this.tasks.find((x) => x.id === r.taskId);
      if (a.type === "snooze" && t) {
        this.memory.snoozes[t.id] = `${r.base}:${Date.now() + a.minutes * 60_000}`;
        this.saveMemory();
      }
      this.publish();
    }
    void this.queue(() => this.act(a));
  }

  private async act(a: Action): Promise<void> {
    if (!(await this.hasSession())) return;
    const device = `laptop:${this.deviceName}`;
    const task = (id: string) => this.tasks.find((t) => t.id === id);
    switch (a.type) {
      case "refresh":
        break;
      case "done":
      case "snooze":
      case "tomorrow":
      case "dismiss": {
        const r = this.findRing(a.ring) ?? a.ring;
        const t = task(r.taskId);
        this.closeRing(r);
        if (t) {
          if (a.type === "done") for (const row of completeRows(t)) await cloud.write("tasks", row);
          if (a.type === "tomorrow") await cloud.write("tasks", tomorrowRow(t));
          if (a.type === "dismiss") {
            const row = skipRow(t);
            if (row) await cloud.write("tasks", row);
          }
          if (a.type === "snooze") {
            const until = Number(this.memory.snoozes[t.id]?.split(":")[1]) || Date.now() + a.minutes * 60_000;
            // Tell the phone first (keyed on the old time), so it stays quiet now.
            await cloud.ack(r.taskId, r.base, "snoozed", device, until);
            // A one-off reminder moves to the new time everywhere: the list shows it, and laptop then phone
            // ring then as usual. Repeating ones keep their schedule; the snooze stays on this laptop.
            if (t.repeat === null) await cloud.write("tasks", snoozeRow(t, until));
          }
        }
        if (a.type !== "snooze") await cloud.ack(r.taskId, r.base, a.type === "done" ? "done" : a.type === "tomorrow" ? "moved" : "dismissed", device);
        break;
      }
      case "complete": {
        const t = task(a.taskId);
        if (t) for (const row of completeRows(t)) await cloud.write("tasks", row);
        this.rings = this.rings.filter((r) => r.taskId !== a.taskId);
        break;
      }
      case "reopen": {
        const t = task(a.taskId);
        if (t) await cloud.write("tasks", reopenRow(t));
        break;
      }
      case "delete": {
        const t = task(a.taskId);
        if (t) await cloud.write("tasks", deleteRow(t));
        break;
      }
      case "addRow":
        await cloud.write("tasks", a.row);
        break;
      case "addNote":
        await cloud.write("items", a.row);
        break;
    }
    await this.refresh();
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
