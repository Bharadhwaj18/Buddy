// The always-on pill at the top of the screen. Hover to drop down today's list; tick things off there.
// Click opens the quick bar. Press and drag anywhere on the pill to move it (it remembers where).

import { listen, emit } from "@tauri-apps/api/event";
import { invoke } from "@tauri-apps/api/core";
import { getCurrentWindow, type Window as TauriWindow } from "@tauri-apps/api/window";
import { LogicalPosition, LogicalSize } from "@tauri-apps/api/dpi";
import { toTask, buildAgenda, type Task, type Agenda } from "../lib/tasks";
import type { EngineState } from "../lib/engine";
import { loadSettings, updateSettings } from "../lib/settings";
import { escapeHtml, timeOf, whenText, capitalise } from "../lib/format";

/** Window size while the drop-down is open. Closed, the window is sized to the pill itself. */
const OPEN = { w: 360, h: 470 };
/** How far the mouse must move (px) before a press becomes a drag instead of a click. */
const DRAG_START_PX = 4;

export function mountPill(root: HTMLElement): void {
  const win: TauriWindow = getCurrentWindow();
  let state: EngineState = { tasks: [], rings: [], online: true, signedIn: false, email: null, queued: 0 };
  let open = false;
  let placing = false;
  let closeTimer: number | undefined;

  const settings = loadSettings();
  let centreX = settings.pillX ?? Math.round(window.screen.availWidth / 2);
  let topY = settings.pillY ?? 6;

  root.innerHTML = `
    <div class="pill-root">
      <div class="pill" data-drag>
        <span class="dot"></span>
        <span class="label"></span>
        <span class="chip late" hidden></span>
        <span class="next"></span>
      </div>
      <div class="panel"></div>
    </div>`;
  const pill = root.querySelector<HTMLElement>(".pill")!;
  const panel = root.querySelector<HTMLElement>(".panel")!;
  const rootEl = root.querySelector<HTMLElement>(".pill-root")!;

  /** The closed window hugs the pill, so nothing is cut off and no empty space blocks clicks. */
  function pillSize(): { w: number; h: number } {
    const r = pill.getBoundingClientRect();
    return { w: Math.ceil(r.width) + 4, h: Math.ceil(r.height) + 4 };
  }

  let lastSize = "";
  async function place(next: boolean): Promise<void> {
    const p = pillSize();
    const size = next ? { w: Math.max(OPEN.w, p.w), h: OPEN.h } : p;
    const key = `${size.w}x${size.h}@${Math.round(centreX)},${Math.round(topY)}`;
    if (key === lastSize) return;
    lastSize = key;
    placing = true;
    await win.setSize(new LogicalSize(size.w, size.h));
    await win.setPosition(new LogicalPosition(Math.round(centreX - size.w / 2), Math.round(topY)));
    window.setTimeout(() => (placing = false), 50);
  }

  function setOpen(next: boolean): void {
    if (open === next) return;
    open = next;
    if (next) {
      void place(true);
      panel.classList.add("open");
    } else {
      panel.classList.remove("open");
      // Shrink the window once the fade has finished, so the pill stays where it is.
      window.setTimeout(() => {
        if (!open) void place(false);
      }, 90);
    }
  }

  // Remember where the user dropped it (its top-centre point), once they let go.
  let saveTimer: number | undefined;
  void win.onMoved(async ({ payload }) => {
    if (placing) return;
    const sf = await win.scaleFactor();
    const size = await win.outerSize();
    centreX = payload.x / sf + size.width / sf / 2;
    topY = payload.y / sf;
    lastSize = "";
    window.clearTimeout(saveTimer);
    saveTimer = window.setTimeout(() => updateSettings({ pillX: Math.round(centreX), pillY: Math.round(topY) }), 300);
  });

  let openTimer: number | undefined;
  rootEl.addEventListener("mouseenter", () => {
    window.clearTimeout(closeTimer);
    // A short wait, so grabbing the pill to move it doesn't open the list first.
    openTimer = window.setTimeout(() => setOpen(true), 160);
  });
  rootEl.addEventListener("mouseleave", () => {
    window.clearTimeout(openTimer);
    window.clearTimeout(closeTimer);
    closeTimer = window.setTimeout(() => setOpen(false), 140);
  });

  // Press anywhere on the pill and move: the window is dragged by Windows itself (smooth).
  // Press and let go without moving: a click, which opens the quick bar.
  let press: { x: number; y: number } | null = null;
  pill.addEventListener("mousedown", (e) => {
    if (e.button !== 0) return;
    press = { x: e.screenX, y: e.screenY };
  });
  window.addEventListener("mousemove", (e) => {
    if (!press || (e.buttons & 1) === 0) return;
    if (Math.abs(e.screenX - press.x) + Math.abs(e.screenY - press.y) < DRAG_START_PX) return;
    press = null;
    window.clearTimeout(openTimer);
    void win.startDragging();
  });
  window.addEventListener("mouseup", () => {
    if (!press) return;
    press = null;
    window.clearTimeout(openTimer);
    setOpen(false);
    void invoke("open_bar");
  });

  panel.addEventListener("click", (e) => {
    if ((e.target as HTMLElement).closest("[data-add]")) {
      e.stopPropagation();
      setOpen(false);
      void invoke("open_bar");
      return;
    }
    const box = (e.target as HTMLElement).closest<HTMLElement>("[data-check]");
    if (!box) return;
    e.stopPropagation();
    const id = box.dataset.check!;
    const task = state.tasks.map(toTask).find((t) => t.id === id);
    if (!task) return;
    void emit("buddy-action", { type: task.isDone ? "reopen" : "complete", taskId: id });
  });

  function render(): void {
    const tasks: Task[] = state.tasks.map(toTask);
    const a = buildAgenda(tasks);
    const pillEl = pill;
    pillEl.classList.toggle("late", a.late > 0);
    const label = root.querySelector<HTMLElement>(".label")!;
    label.textContent = a.total === 0 ? "Nothing today" : a.left === 0 ? "All done" : `${a.left} today`;
    const lateChip = root.querySelector<HTMLElement>(".chip.late")!;
    lateChip.hidden = a.late === 0;
    lateChip.textContent = `${a.late} late`;
    const next = root.querySelector<HTMLElement>(".next")!;
    next.textContent = a.next ? `Next · ${a.next.title} ${timeOf(a.next.remindAt!)}` : "";
    panel.innerHTML = panelHtml(a);
    // The text changed, so the pill may be wider or narrower: fit the window to it.
    if (!open) void place(false);
  }

  void listen<EngineState>("buddy-state", (e) => {
    state = e.payload;
    render();
  });
  window.setInterval(render, 60_000);
  render();
  void place(false);
}

function panelHtml(a: Agenda): string {
  const day = new Date().toLocaleDateString("en-GB", { weekday: "long", day: "numeric", month: "long" });
  const pct = a.total ? Math.round((a.done.length / a.total) * 100) : 0;
  return `
    <div class="panel-head">
      <h2>Today</h2><span class="date">${escapeHtml(day)}</span>
      ${a.total ? `<span class="count">${a.done.length} of ${a.total} done</span>` : ""}
    </div>
    ${a.total ? `<div class="progress"><div style="width:${pct}%"></div></div>` : ""}
    <div class="list">
      ${a.left === 0 ? `<div class="empty">${a.total === 0 ? "Nothing planned for today." : "All done for today. Nice."}</div>` : ""}
      ${section("Overdue", a.overdue, true)}
      ${section("Today", a.today)}
      ${section("Inbox · no date", a.inbox)}
      ${section("Done", a.done.slice(0, 4))}
    </div>
    <div class="panel-foot">
      <span>Tomorrow ${a.tomorrow} · This week ${a.thisWeek}</span>
      <button class="pill-btn small primary" data-add>Add</button>
    </div>`;
}

function section(title: string, items: Task[], late = false): string {
  if (items.length === 0) return "";
  return `<div class="section ${late ? "late" : ""}">${title}</div>${items.map((t) => item(t, late)).join("")}`;
}

function item(t: Task, late: boolean): string {
  const now = Date.now();
  const passed = !t.isDone && t.remindAt !== null && t.remindAt <= now;
  const bell = t.remindAt !== null ? `<span class="bell" title="Reminder">🔔</span>` : "";
  const meta =
    late
      ? `<span class="meta late">${bell}${escapeHtml(whenText((t.remindAt ?? t.dueAt)!, false))}</span>`
      : t.remindAt !== null && !t.isDone
        ? `<span class="meta ${passed ? "late" : ""}">${bell}${t.repeat ? "repeats · " : ""}${timeOf(t.remindAt)}</span>`
        : t.dueAt !== null && !t.dueAllDay && !t.isDone
          ? `<span class="meta">${timeOf(t.dueAt)}</span>`
          : t.priority === "high" || t.priority === "urgent"
            ? `<span class="chip warn">${capitalise(t.priority)}</span>`
            : "";
  const cls = ["item", late ? "late" : "", t.isDone ? "done" : ""].join(" ");
  return `<div class="${cls}"><span class="check ${t.isDone ? "done" : ""}" data-check="${escapeHtml(t.id)}"></span>
    <span class="title">${escapeHtml(t.title)}</span>${meta}</div>`;
}
