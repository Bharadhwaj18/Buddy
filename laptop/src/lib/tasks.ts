// Tasks as the server stores them (snake_case rows) and the edits the laptop makes to them.
// Mirrors TaskEdits / Agenda in the Kotlin desktop app and TaskRepository on the phone, so both agree.

import { nextOccurrence, parseRule, encodeRule, type RepeatRule } from "./repeat";

/** A row from the `tasks` table, as the server returns it. Unknown columns are kept and sent back. */
export type Row = Record<string, unknown> & { id: string };

export interface Task {
  row: Row;
  id: string;
  title: string;
  status: string;
  priority: string;
  remindAt: number | null;
  dueAt: number | null;
  dueAllDay: boolean;
  repeat: string | null;
  updatedAt: number;
  completedAt: number | null;
  myDay: string | null;
  bucketId: string | null;
  position: number;
  isDone: boolean;
}

const ms = (v: unknown): number | null => (typeof v === "string" && v ? Date.parse(v) : null);

export function toTask(row: Row): Task {
  const status = (row.status as string) ?? "todo";
  return {
    row,
    id: row.id,
    title: (row.title as string) ?? "",
    status,
    priority: (row.priority as string) ?? "normal",
    remindAt: ms(row.remind_at),
    dueAt: ms(row.due_at),
    dueAllDay: row.due_all_day !== false,
    repeat: (row.repeat as string) ?? null,
    updatedAt: ms(row.updated_at) ?? 0,
    completedAt: ms(row.completed_at),
    myDay: (row.my_day as string) ?? null,
    bucketId: (row.bucket_id as string) ?? null,
    position: typeof row.position === "number" ? row.position : 0,
    isDone: status === "done",
  };
}

/** What we send: the whole row minus the columns the server fills itself. */
export function forPush(row: Row): Row {
  const { user_id: _u, server_updated_at: _s, ...rest } = row;
  return rest as Row;
}

const iso = (t: number) => new Date(t).toISOString();
const stamp = (t: Task, now: number) => Math.max(now, t.updatedAt + 1);

/** Marks a task done. A repeating task also gets its next occurrence as a new task. */
export function completeRows(t: Task, now = Date.now()): Row[] {
  const rule = parseRule(t.repeat);
  const spawn = rule !== null && (t.dueAt !== null || t.remindAt !== null);
  const done: Row = {
    ...forPush(t.row),
    status: "done",
    completed_at: iso(now),
    updated_at: iso(stamp(t, now)),
    repeat: spawn ? null : t.repeat,
  };
  if (!rule || !spawn) return [done];
  const next = nextOccurrence(rule, t.dueAt, t.remindAt, now);
  const checklist = Array.isArray(t.row.checklist)
    ? (t.row.checklist as Array<Record<string, unknown>>).map((i) => ({ ...i, done: false }))
    : [];
  const copy: Row = {
    ...forPush(t.row),
    id: crypto.randomUUID(),
    status: "todo",
    created_at: iso(now),
    updated_at: iso(now),
    completed_at: null,
    my_day: null,
    due_at: next.dueAt !== null ? iso(next.dueAt) : null,
    remind_at: next.remindAt !== null ? iso(next.remindAt) : null,
    checklist,
  };
  return [done, copy];
}

/** Skips a repeating task to its next time without completing it. Null if it doesn't repeat. */
export function skipRow(t: Task, now = Date.now()): Row | null {
  const rule = parseRule(t.repeat);
  if (!rule) return null;
  const next = nextOccurrence(rule, t.dueAt, t.remindAt, now);
  return {
    ...forPush(t.row),
    due_at: next.dueAt !== null ? iso(next.dueAt) : null,
    remind_at: next.remindAt !== null ? iso(next.remindAt) : null,
    updated_at: iso(stamp(t, now)),
  };
}

/** Same time tomorrow: the reminder and due date move together. */
export function tomorrowRow(t: Task, now = Date.now()): Row {
  const day = new Date(now);
  day.setDate(day.getDate() + 1);
  const onDay = (x: number) => {
    const d = new Date(x);
    d.setFullYear(day.getFullYear(), day.getMonth(), day.getDate());
    return d.getTime();
  };
  const newDue = t.dueAt !== null ? onDay(t.dueAt) : null;
  const shift = t.dueAt !== null && newDue !== null ? newDue - t.dueAt : null;
  const newRemind = t.remindAt !== null ? (shift !== null ? t.remindAt + shift : onDay(t.remindAt)) : null;
  return {
    ...forPush(t.row),
    due_at: newDue !== null ? iso(newDue) : null,
    remind_at: newRemind !== null ? iso(newRemind) : null,
    my_day: null,
    updated_at: iso(stamp(t, now)),
  };
}

/** Moves a one-off reminder to a new time (snooze), so every device shows and rings the new time. */
export function snoozeRow(t: Task, until: number, now = Date.now()): Row {
  return { ...forPush(t.row), remind_at: iso(until), updated_at: iso(stamp(t, now)) };
}

/** Fields the details view can change. */
export type TaskPatch = Partial<{
  title: string;
  notes: string | null;
  remind_at: string | null;
  due_at: string | null;
  due_all_day: boolean;
  repeat: string | null;
  priority: string;
  my_day: string | null;
}>;

/** An edit from the details view: the whole row with the changed fields. */
export function editRow(t: Task, patch: TaskPatch, now = Date.now()): Row {
  return { ...forPush(t.row), ...patch, updated_at: iso(stamp(t, now)) };
}

/** Ticks a done task back to to-do (undo). */
export function reopenRow(t: Task, now = Date.now()): Row {
  return { ...forPush(t.row), status: "todo", completed_at: null, updated_at: iso(stamp(t, now)) };
}

export function deleteRow(t: Task, now = Date.now()): Row {
  return { ...forPush(t.row), deleted_at: iso(now), updated_at: iso(stamp(t, now)) };
}

export interface NewTask {
  title: string;
  dueAt: number | null;
  dueAllDay: boolean;
  remindAt: number | null;
  repeat: RepeatRule | null;
}

/** A brand-new task row, filed into the first plan and bucket (passed in). */
export function newRow(
  input: NewTask,
  filing: { planId: string | null; bucketId: string | null; position: number },
  now = Date.now(),
): Row {
  const today = new Date(now).toDateString();
  return {
    id: crypto.randomUUID(),
    title: input.title.trim(),
    notes: null,
    status: "todo",
    created_at: iso(now),
    updated_at: iso(now),
    deleted_at: null,
    plan_id: filing.planId,
    bucket_id: filing.bucketId,
    priority: "normal",
    due_at: input.dueAt !== null ? iso(input.dueAt) : null,
    // On Today when it's due today, or has no date or reminder at all (then it falls to the Inbox tomorrow).
    my_day:
      (input.dueAt !== null && new Date(input.dueAt).toDateString() === today) || (input.dueAt === null && input.remindAt === null)
        ? isoDay(new Date(now))
        : null,
    position: filing.position,
    completed_at: null,
    checklist: [],
    due_all_day: input.dueAllDay || input.dueAt === null,
    remind_at: input.remindAt !== null ? iso(input.remindAt) : null,
    repeat: input.repeat ? encodeRule(input.repeat) : null,
  };
}

// ---------------------------------------------------------------- today

export interface Agenda {
  overdue: Task[];
  /** Today's reminders and tasks in one list: timed ones by time, then the rest. */
  today: Task[];
  /** Open tasks with no date or reminder that aren't on Today. */
  inbox: Task[];
  reminders: Task[];
  tasks: Task[];
  done: Task[];
  tomorrow: number;
  thisWeek: number;
  next: Task | null;
  /** Overdue, plus today's reminders whose time has passed. */
  late: number;
  left: number;
  total: number;
}

const dayKey = (t: number) => new Date(t).toDateString();
const PRIORITY = { urgent: 0, high: 1, normal: 2, low: 3 } as Record<string, number>;

/** Today at a glance: overdue, today's reminders, today's tasks, and what's done. */
export function buildAgenda(all: Task[], now = Date.now()): Agenda {
  const today = new Date(now);
  const todayKey = today.toDateString();
  const dateOf = (t: Task) => {
    const v = t.remindAt ?? t.dueAt;
    return v === null ? null : new Date(v);
  };
  const open = all.filter((t) => !t.isDone);
  const startOfToday = new Date(today.getFullYear(), today.getMonth(), today.getDate()).getTime();

  const overdue = open
    .filter((t) => {
      const d = dateOf(t);
      return d !== null && d.getTime() < startOfToday;
    })
    .sort((a, b) => (a.remindAt ?? a.dueAt ?? 0) - (b.remindAt ?? b.dueAt ?? 0));
  const reminders = open
    .filter((t) => t.remindAt !== null && dayKey(t.remindAt) === todayKey)
    .sort((a, b) => (a.remindAt ?? 0) - (b.remindAt ?? 0));
  const taken = new Set([...overdue, ...reminders].map((t) => t.id));
  const tasks = open
    .filter((t) => !taken.has(t.id) && ((t.dueAt !== null && dayKey(t.dueAt) === todayKey) || t.myDay === isoDay(today)))
    .sort((a, b) => (PRIORITY[a.priority] ?? 2) - (PRIORITY[b.priority] ?? 2) || (a.dueAt ?? Infinity) - (b.dueAt ?? Infinity));
  const done = all
    .filter((t) => t.isDone && t.completedAt !== null && dayKey(t.completedAt) === todayKey)
    .sort((a, b) => (b.completedAt ?? 0) - (a.completedAt ?? 0));

  const tomorrowStart = startOfToday + 86_400_000;
  const tomorrow = open.filter((t) => {
    const d = dateOf(t);
    return d !== null && d.getTime() >= tomorrowStart && d.getTime() < tomorrowStart + 86_400_000;
  }).length;
  const weekEnd = startOfToday + 8 * 86_400_000;
  const thisWeek = open.filter((t) => {
    const d = dateOf(t);
    return d !== null && d.getTime() >= tomorrowStart && d.getTime() < weekEnd;
  }).length;
  const next = reminders.find((t) => (t.remindAt ?? 0) > now) ?? null;
  const late = overdue.length + reminders.filter((t) => (t.remindAt ?? 0) <= now).length;
  const left = overdue.length + reminders.length + tasks.length;
  const timeOf = (t: Task) => t.remindAt ?? (t.dueAt !== null && !t.dueAllDay ? t.dueAt : null);
  const todayList = [...reminders, ...tasks].sort((x, y) => {
    const a = timeOf(x);
    const b = timeOf(y);
    if (a !== null && b !== null) return a - b;
    if (a !== null) return -1;
    if (b !== null) return 1;
    return (PRIORITY[x.priority] ?? 2) - (PRIORITY[y.priority] ?? 2) || x.position - y.position;
  });
  const listed = new Set([...overdue, ...todayList].map((t) => t.id));
  const inbox = open
    .filter((t) => !listed.has(t.id) && t.remindAt === null && t.dueAt === null)
    .sort((x, y) => (PRIORITY[x.priority] ?? 2) - (PRIORITY[y.priority] ?? 2) || x.position - y.position);
  return { overdue, today: todayList, inbox, reminders, tasks, done, tomorrow, thisWeek, next, late, left, total: left + done.length };
}

function isoDay(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}
