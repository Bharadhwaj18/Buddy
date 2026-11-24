import { describe, expect, it } from "vitest";
import { parseRule, encodeRule, nextAfter, nextOccurrence } from "./repeat";
import { toTask, completeRows, tomorrowRow, buildAgenda, skipRow, type Row } from "./tasks";
import { lookFor, type Settings } from "./settings";

const at = (y: number, mo: number, d: number, h = 0, mi = 0) => new Date(y, mo - 1, d, h, mi).getTime();

function row(over: Partial<Row>): Row {
  return {
    id: "11111111-1111-1111-1111-111111111111",
    user_id: "someone",
    server_updated_at: "2026-11-20T10:00:00Z",
    title: "Weekly report",
    status: "todo",
    created_at: "2026-11-01T10:00:00Z",
    updated_at: "2026-11-20T10:00:00Z",
    checklist: [{ id: "a", text: "draft", done: true }],
    some_future_column: 42,
    ...over,
  } as Row;
}

describe("repeat rules", () => {
  it("round-trips the RRULE subset", () => {
    const r = parseRule("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE")!;
    expect(r.freq).toBe("WEEKLY");
    expect(r.interval).toBe(2);
    expect(r.days).toEqual([1, 3]);
    expect(encodeRule(r)).toBe("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE");
    expect(parseRule("nonsense")).toBeNull();
  });

  it("weekly without days moves one week on, keeping the time", () => {
    const next = nextAfter(new Date(at(2026, 11, 23, 10, 0)), parseRule("FREQ=WEEKLY")!);
    expect(next.getTime()).toBe(at(2026, 11, 30, 10, 0));
  });

  it("weekdays only skips the weekend", () => {
    // Friday 27 Nov 2026 → Monday 30 Nov
    const next = nextAfter(new Date(at(2026, 11, 27, 9, 0)), parseRule("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR")!);
    expect(next.getTime()).toBe(at(2026, 11, 30, 9, 0));
  });

  it("last day of the month", () => {
    const next = nextAfter(new Date(at(2026, 11, 30, 9, 0)), parseRule("FREQ=MONTHLY;BYMONTHDAY=-1")!);
    expect(next.getTime()).toBe(at(2026, 12, 31, 9, 0));
  });

  it("a late completion keeps moving until the next time is in the future", () => {
    const rule = parseRule("FREQ=DAILY")!;
    const next = nextOccurrence(rule, at(2026, 11, 20, 9, 0), null, at(2026, 11, 23, 12, 0));
    expect(next.dueAt).toBe(at(2026, 11, 24, 9, 0));
  });
});

describe("task edits", () => {
  it("completing a repeating task spawns the next one and stops the old one repeating", () => {
    const now = at(2026, 11, 23, 10, 5);
    const t = toTask(row({ repeat: "FREQ=WEEKLY", remind_at: new Date(at(2026, 11, 23, 10, 0)).toISOString() }));
    const [done, next] = completeRows(t, now);
    expect(done.status).toBe("done");
    expect(done.repeat).toBeNull();
    expect(done.some_future_column).toBe(42);
    expect(done).not.toHaveProperty("user_id");
    expect(next.status).toBe("todo");
    expect(next.repeat).toBe("FREQ=WEEKLY");
    expect(next.id).not.toBe(t.id);
    expect(Date.parse(next.remind_at as string)).toBe(at(2026, 11, 30, 10, 0));
    expect((next.checklist as Array<{ done: boolean }>)[0].done).toBe(false);
  });

  it("completing a one-off task is a single row", () => {
    const rows = completeRows(toTask(row({ remind_at: new Date(at(2026, 11, 23, 10, 0)).toISOString() })), at(2026, 11, 23, 10, 5));
    expect(rows).toHaveLength(1);
  });

  it("tomorrow keeps the time and the gap between reminder and due", () => {
    const due = at(2026, 11, 23, 11, 0);
    const remind = at(2026, 11, 23, 10, 30);
    const moved = tomorrowRow(toTask(row({ due_at: new Date(due).toISOString(), remind_at: new Date(remind).toISOString() })), at(2026, 11, 23, 10, 31));
    const tomorrow = new Date(2026, 10, 24);
    expect(Date.parse(moved.due_at as string)).toBe(new Date(tomorrow.getFullYear(), tomorrow.getMonth(), tomorrow.getDate(), 11, 0).getTime());
    expect(Date.parse(moved.remind_at as string)).toBe(new Date(tomorrow.getFullYear(), tomorrow.getMonth(), tomorrow.getDate(), 10, 30).getTime());
  });

  it("skip is only for repeating tasks", () => {
    expect(skipRow(toTask(row({})))).toBeNull();
  });
});

describe("today", () => {
  it("sorts today into overdue, reminders, tasks and done", () => {
    const now = at(2026, 11, 23, 12, 0);
    const iso = (t: number) => new Date(t).toISOString();
    const all = [
      toTask(row({ id: "a", title: "Late from yesterday", remind_at: iso(at(2026, 11, 22, 9, 0)) })),
      toTask(row({ id: "b", title: "Morning reminder", remind_at: iso(at(2026, 11, 23, 9, 0)) })),
      toTask(row({ id: "c", title: "Afternoon reminder", remind_at: iso(at(2026, 11, 23, 16, 0)) })),
      toTask(row({ id: "d", title: "Due today", due_at: iso(at(2026, 11, 23, 0, 0)) })),
      toTask(row({ id: "e", title: "Finished", status: "done", completed_at: iso(at(2026, 11, 23, 8, 0)) })),
      toTask(row({ id: "f", title: "Tomorrow", remind_at: iso(at(2026, 11, 24, 9, 0)) })),
    ];
    const a = buildAgenda(all, now);
    expect(a.overdue.map((t) => t.title)).toEqual(["Late from yesterday"]);
    expect(a.reminders.map((t) => t.title)).toEqual(["Morning reminder", "Afternoon reminder"]);
    expect(a.tasks.map((t) => t.title)).toEqual(["Due today"]);
    expect(a.done.map((t) => t.title)).toEqual(["Finished"]);
    expect(a.next?.title).toBe("Afternoon reminder");
    expect(a.late).toBe(2);
    expect(a.tomorrow).toBe(1);
    expect(a.left).toBe(4);
    expect(a.total).toBe(5);
  });
});

describe("light by day, dark in the evening", () => {
  const s = { theme: "by_time", darkFrom: "19:00", lightFrom: "07:00" } as Settings;
  it("switches at the set times", () => {
    expect(lookFor(s, new Date(2026, 10, 23, 12, 0)).dark).toBe(false);
    expect(lookFor(s, new Date(2026, 10, 23, 19, 0)).dark).toBe(true);
    expect(lookFor(s, new Date(2026, 10, 23, 2, 0)).dark).toBe(true);
    expect(lookFor(s, new Date(2026, 10, 23, 7, 0)).dark).toBe(false);
  });
  it("respects an always-light or always-dark choice", () => {
    expect(lookFor({ ...s, theme: "dark" } as Settings, new Date(2026, 10, 23, 12, 0)).dark).toBe(true);
    expect(lookFor({ ...s, theme: "light" } as Settings, new Date(2026, 10, 23, 23, 0)).dark).toBe(false);
  });
});

import { hotkeyLabel } from "./settings";

describe("hotkey labels", () => {
  it("reads shortcuts the way people say them", () => {
    expect(hotkeyLabel("ctrl+Space")).toBe("Ctrl + Space");
    expect(hotkeyLabel("ctrl+shift+KeyB")).toBe("Ctrl + Shift + B");
    expect(hotkeyLabel("ControlRight")).toBe("Right Ctrl");
    expect(hotkeyLabel("alt+Digit1")).toBe("Alt + 1");
  });
});

import { parseQuick, nextRoundHour, applyChip, wantsReminder, splitTime } from "./quick";
import { newRow } from "./tasks";

describe("quick words and default times", () => {
  const now = at(2026, 11, 24, 15, 10);
  it("the next round hour", () => {
    expect(nextRoundHour(now)).toBe(at(2026, 11, 24, 16, 0));
    expect(nextRoundHour(at(2026, 11, 24, 15, 0))).toBe(at(2026, 11, 24, 16, 0));
    expect(nextRoundHour(at(2026, 11, 24, 23, 30))).toBe(at(2026, 11, 25, 0, 0));
  });
  it("rm and ! make reminders; no time means the next round hour", () => {
    expect(parseQuick("rm call Ravi", now)).toMatchObject({ kind: "reminder", title: "call Ravi", remindAt: at(2026, 11, 24, 16, 0) });
    expect(parseQuick("!call Ravi at 5:30 pm", now)).toMatchObject({ kind: "reminder", title: "call Ravi", remindAt: at(2026, 11, 24, 17, 30) });
    expect(parseQuick("rm stretch in 20 min", now)?.remindAt).toBe(now + 20 * 60_000);
    expect(parseQuick("rm standup tomorrow 9am", now)?.remindAt).toBe(at(2026, 11, 25, 9, 0));
    expect(parseQuick("rm pay rent tomorrow", now)?.remindAt).toBe(at(2026, 11, 25, 9, 0));
    expect(parseQuick("rm gym 9am", now)?.remindAt).toBe(at(2026, 11, 25, 9, 0));
  });
  it("t: makes a task; plain text is left to the AI", () => {
    expect(parseQuick("t: send the deck", now)).toMatchObject({ kind: "task", title: "send the deck", remindAt: null, dueAt: null });
    expect(parseQuick("remind me to call Ravi", now)).toBeNull();
    expect(parseQuick("trim the hedge", now)).toBeNull();
  });
  it("spots 'remind me'", () => {
    expect(wantsReminder("Remind me to call Ravi")).toBe(true);
    expect(wantsReminder("call Ravi")).toBe(false);
  });
  it("leaves numbers that aren't times alone", () => {
    expect(splitTime("buy 3", now)).toMatchObject({ title: "buy 3", at: null });
  });
  it("chips change the draft", () => {
    const d = parseQuick("rm call Ravi", now)!;
    expect(applyChip(d, "task", now)).toMatchObject({ kind: "task", remindAt: null });
    expect(applyChip(d, "tomorrow", now).remindAt).toBe(at(2026, 11, 25, 9, 0));
    expect(applyChip(d, "evening", now).remindAt).toBe(at(2026, 11, 24, 18, 0));
    expect(applyChip({ ...d, kind: "task", remindAt: null }, "remind", now)).toMatchObject({ kind: "reminder", remindAt: at(2026, 11, 24, 16, 0) });
  });
});

describe("one mixed Today list, and the Inbox", () => {
  it("puts undated new tasks on Today", () => {
    const r = newRow({ title: "x", dueAt: null, dueAllDay: true, remindAt: null, repeat: null }, { planId: null, bucketId: null, position: 0 }, at(2026, 11, 24, 2, 0));
    expect(r.my_day).toBe("2026-11-24");
  });
  it("mixes reminders and tasks by time; old undated tasks go to the Inbox", () => {
    const now = at(2026, 11, 24, 12, 0);
    const iso = (t: number) => new Date(t).toISOString();
    const all = [
      toTask(row({ id: "a", title: "Undated on today", my_day: "2026-11-24", position: 1 })),
      toTask(row({ id: "b", title: "Reminder 4pm", remind_at: iso(at(2026, 11, 24, 16, 0)) })),
      toTask(row({ id: "c", title: "Task due 2pm", due_at: iso(at(2026, 11, 24, 14, 0)), due_all_day: false })),
      toTask(row({ id: "d", title: "From yesterday, no date", my_day: "2026-11-23" })),
      toTask(row({ id: "e", title: "No date at all" })),
    ];
    const a = buildAgenda(all, now);
    expect(a.today.map((t) => t.title)).toEqual(["Task due 2pm", "Reminder 4pm", "Undated on today"]);
    expect(a.inbox.map((t) => t.title).sort()).toEqual(["From yesterday, no date", "No date at all"]);
  });
});

import { snoozeRow } from "./tasks";

describe("snooze", () => {
  it("moves a one-off reminder to the new time and keeps the rest of the row", () => {
    const now = at(2026, 11, 24, 15, 0);
    const t = toTask(row({ remind_at: new Date(at(2026, 11, 24, 14, 55)).toISOString() }));
    const r = snoozeRow(t, now + 10 * 60_000, now);
    expect(Date.parse(r.remind_at as string)).toBe(at(2026, 11, 24, 15, 10));
    expect(r.some_future_column).toBe(42);
    expect(r).not.toHaveProperty("user_id");
    expect(Date.parse(r.updated_at as string)).toBeGreaterThan(t.updatedAt);
  });
});
