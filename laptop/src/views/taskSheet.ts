// The details view inside the pill's drop-down: click a task or reminder to see it all and change it
// (title, reminder time, due date, repeat, priority, notes), mark it done or delete it.

import type { Task, TaskPatch } from "../lib/tasks";
import { parseWhen, whenMs } from "../lib/when";
import { escapeHtml, whenText } from "../lib/format";

const REPEATS: Array<[string, string]> = [
  ["", "Doesn't repeat"],
  ["FREQ=DAILY", "Every day"],
  ["FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", "Weekdays"],
  ["FREQ=WEEKLY", "Every week"],
  ["FREQ=MONTHLY", "Every month"],
  ["FREQ=YEARLY", "Every year"],
];
const PRIORITIES = ["low", "normal", "high", "urgent"];

const pad = (n: number) => String(n).padStart(2, "0");
const dateValue = (ms: number | null) => {
  if (ms === null) return "";
  const d = new Date(ms);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
};
const timeValue = (ms: number | null) => {
  if (ms === null) return "";
  const d = new Date(ms);
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
};
const localMs = (date: string, time: string) => {
  if (!date) return null;
  const [y, m, d] = date.split("-").map(Number);
  const [h, mi] = (time || "00:00").split(":").map(Number);
  return new Date(y, m - 1, d, h, mi, 0, 0).getTime();
};
const label = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

export function sheetHtml(t: Task): string {
  const repeatKnown = REPEATS.some(([v]) => v === (t.repeat ?? ""));
  const created = typeof t.row.created_at === "string" ? Date.parse(t.row.created_at) : NaN;
  return `
    <div class="sheet" data-sheet="${escapeHtml(t.id)}">
      <div class="sheet-head">
        <button class="icon-btn" data-sheet-act="back" aria-label="Back" title="Back (Esc)">←</button>
        <span class="sheet-kind">${t.remindAt !== null ? "Reminder" : "Task"}${t.isDone ? " · done" : ""}</span>
        <button class="icon-btn danger" data-sheet-act="delete" aria-label="Delete" title="Delete">🗑</button>
      </div>
      <div class="sheet-body">
        <input class="field sheet-title" name="title" value="${escapeHtml(t.title)}" placeholder="Title" />

        <label class="sheet-label">When</label>
        <input class="field" name="when" placeholder="Type it: tomorrow 2.30 pm, in 20 min, fri 5" autocomplete="off" />
        <div class="sheet-hint" data-when-hint></div>

        <label class="sheet-label">Reminder</label>
        <div class="sheet-row">
          <input class="field" type="date" name="remindDate" value="${dateValue(t.remindAt)}" />
          <input class="field" type="time" name="remindTime" value="${timeValue(t.remindAt)}" />
        </div>
        <div class="sheet-chips">
          <button class="chip-btn" data-quick="10">+10 min</button>
          <button class="chip-btn" data-quick="60">+1 hr</button>
          <button class="chip-btn" data-quick="eve">6 pm</button>
          <button class="chip-btn" data-quick="tmrw">Tomorrow 9 am</button>
          <button class="chip-btn" data-quick="none">No reminder</button>
        </div>

        <label class="sheet-label">Due</label>
        <div class="sheet-row">
          <input class="field" type="date" name="dueDate" value="${dateValue(t.dueAt)}" />
          <input class="field" type="time" name="dueTime" value="${t.dueAllDay ? "" : timeValue(t.dueAt)}" title="Leave empty for all day" />
        </div>

        <div class="sheet-row">
          <div class="sheet-col">
            <label class="sheet-label">Repeat</label>
            <select class="field" name="repeat">
              ${repeatKnown ? "" : `<option value="__keep" selected>Custom (keep as is)</option>`}
              ${REPEATS.map(([v, l]) => `<option value="${v}" ${repeatKnown && v === (t.repeat ?? "") ? "selected" : ""}>${l}</option>`).join("")}
            </select>
          </div>
          <div class="sheet-col">
            <label class="sheet-label">Priority</label>
            <select class="field" name="priority">
              ${PRIORITIES.map((p) => `<option value="${p}" ${p === t.priority ? "selected" : ""}>${label(p)}</option>`).join("")}
            </select>
          </div>
        </div>

        <label class="sheet-label">Notes</label>
        <textarea class="field" name="notes" rows="3" placeholder="Add notes">${escapeHtml((t.row.notes as string | null) ?? "")}</textarea>

        ${Number.isNaN(created) ? "" : `<div class="sheet-meta">Added ${escapeHtml(whenText(created))}</div>`}
        <div class="sheet-error" data-error></div>
      </div>
      <div class="sheet-foot">
        <button class="pill-btn small" data-sheet-act="${t.isDone ? "reopen" : "done"}">${t.isDone ? "Not done" : "Mark done"}</button>
        <span class="grow"></span>
        <button class="pill-btn small" data-sheet-act="back">Cancel</button>
        <button class="pill-btn small primary" data-sheet-act="save">Save</button>
      </div>
      <div class="sheet-confirm" data-confirm hidden>
        <span>Delete “${escapeHtml(t.title)}”?</span>
        <button class="pill-btn small" data-sheet-act="keep">Keep</button>
        <button class="pill-btn small danger" data-sheet-act="really-delete">Delete</button>
      </div>
    </div>`;
}

type Field = HTMLInputElement & HTMLSelectElement & HTMLTextAreaElement;

/** Hooks up the "When" box and the quick reminder buttons. */
export function wireSheet(sheet: HTMLElement): void {
  const f = (name: string) => sheet.querySelector<Field>(`[name="${name}"]`)!;
  const hint = sheet.querySelector<HTMLElement>("[data-when-hint]")!;
  const setReminder = (ms: number | null) => {
    f("remindDate").value = dateValue(ms);
    f("remindTime").value = timeValue(ms);
  };
  f("when").addEventListener("input", () => {
    const text = f("when").value.trim();
    if (!text) {
      hint.textContent = "";
      return;
    }
    // A bare time with no words ("2.30 pm") reads like a reminder for that time.
    const w = parseWhen(`remind me ${text}`);
    const ms = whenMs(w);
    if (ms === null || w.time === null) {
      hint.textContent = "Didn't catch a time yet";
      hint.classList.add("bad");
      return;
    }
    hint.classList.remove("bad");
    hint.textContent = `Reminder → ${whenText(ms)}`;
    setReminder(ms);
  });
  sheet.addEventListener("click", (e) => {
    const b = (e.target as HTMLElement).closest<HTMLElement>("[data-quick]");
    if (!b) return;
    e.preventDefault();
    const now = new Date();
    const q = b.dataset.quick!;
    if (q === "none") setReminder(null);
    else if (q === "eve") setReminder(new Date(now.getFullYear(), now.getMonth(), now.getDate() + (now.getHours() >= 18 ? 1 : 0), 18, 0).getTime());
    else if (q === "tmrw") setReminder(new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1, 9, 0).getTime());
    else setReminder(Date.now() + Number(q) * 60_000);
    hint.textContent = "";
  });
}

/** What changed, ready for the engine; or an error to show. */
export function readSheet(sheet: HTMLElement, t: Task): { patch: TaskPatch } | { error: string } {
  const f = (name: string) => sheet.querySelector<Field>(`[name="${name}"]`)!;
  const title = f("title").value.trim();
  if (!title) return { error: "Give it a title" };
  const rDate = f("remindDate").value;
  const rTime = f("remindTime").value;
  if (rDate && !rTime) return { error: "Pick a time for the reminder (or clear its date)" };
  if (!rDate && rTime) return { error: "Pick a day for the reminder (or clear its time)" };
  const remindAt = rDate ? localMs(rDate, rTime) : null;
  const dDate = f("dueDate").value;
  const dTime = f("dueTime").value;
  if (!dDate && dTime) return { error: "Pick a due day (or clear the due time)" };
  const dueAt = dDate ? localMs(dDate, dTime) : null;
  const repeatValue = f("repeat").value;
  const repeat = repeatValue === "__keep" ? t.repeat : repeatValue || null;
  if (repeat && remindAt === null && dueAt === null) return { error: "A repeat needs a reminder or a due date to start from" };
  const notes = f("notes").value.trim() || null;
  const iso = (ms: number | null) => (ms === null ? null : new Date(ms).toISOString());

  const patch: TaskPatch = {};
  if (title !== t.title) patch.title = title;
  if (notes !== ((t.row.notes as string | null) ?? null)) patch.notes = notes;
  if (remindAt !== t.remindAt) patch.remind_at = iso(remindAt);
  const dueAllDay = dueAt === null || !dTime;
  if (dueAt !== t.dueAt || dueAllDay !== t.dueAllDay) {
    patch.due_at = iso(dueAt);
    patch.due_all_day = dueAllDay;
  }
  if (repeat !== t.repeat) patch.repeat = repeat;
  const priority = f("priority").value;
  if (priority !== t.priority) patch.priority = priority;
  return { patch };
}
