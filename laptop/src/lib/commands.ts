// What the quick bar does with what you typed or said. Nothing is saved here: planning returns steps,
// and a step's commit() saves it, once the 2-second Cancel window has passed.

import { emit } from "@tauri-apps/api/event";
import * as cloud from "./cloud";
import { newRow } from "./tasks";
import { parseRule } from "./repeat";
import { whenText } from "./format";
import { parseQuick, wantsReminder, nextRoundHour, splitTime, type Draft } from "./quick";

export type Step =
  | {
      kind: "save";
      heading: string;
      title: string;
      detail: string;
      /** Set for tasks and reminders: what will be saved, which the saving card's chips can change. */
      draft?: Draft;
      commit: (draft?: Draft) => Promise<string>;
    }
  | { kind: "answer"; text: string }
  | { kind: "info"; text: string };

interface AiAction {
  type: string;
  title?: string;
  due_date?: string;
  due_time?: string;
  remind_at?: string;
  repeat?: string;
  question?: string;
}

interface AiCommandReply {
  actions?: AiAction[];
  speech?: string;
  error?: string | null;
}

interface AiChatReply {
  text?: string;
  error?: string | null;
}

const AI_TIMEOUT_MS = 12_000;

function withTimeout<T>(p: Promise<T>, ms: number): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const t = setTimeout(() => reject(new Error("timeout")), ms);
    p.then(
      (v) => {
        clearTimeout(t);
        resolve(v);
      },
      (e) => {
        clearTimeout(t);
        reject(e);
      },
    );
  });
}

/** Works out what the text means. Uses Buddy's AI; with no answer in time, saves it as a plain task. */
export async function plan(input: string): Promise<Step[]> {
  const text = input.trim();
  if (!text) return [];
  // "rm …", "!…" and "t: …" skip the AI: instant, and they work offline.
  const quick = parseQuick(text);
  if (quick) return [draftStep(quick)];
  const now = new Date();
  let reply: AiCommandReply | null = null;
  try {
    reply = await withTimeout(
      cloud.buddyAi<AiCommandReply>({
        mode: "command",
        text,
        today: isoDay(now),
        now: `${String(now.getHours()).padStart(2, "0")}:${String(now.getMinutes()).padStart(2, "0")}`,
        categories: ["other"],
      }),
      AI_TIMEOUT_MS,
    );
  } catch {
    reply = null;
  }
  if (!reply || reply.error || !reply.actions || reply.actions.length === 0) {
    // No AI answer: still honour "remind me", and any time said at the end.
    const { title, at, hasTime } = splitTime(text);
    if (wantsReminder(text)) {
      return [draftStep({ kind: "reminder", title, remindAt: at !== null && hasTime ? at : nextRoundHour(), dueAt: null, hasTime: true })];
    }
    return [draftStep({ kind: "task", title, remindAt: null, dueAt: at, hasTime: at !== null && hasTime })];
  }
  const steps: Step[] = [];
  for (const a of reply.actions) {
    switch (a.type) {
      case "create_task":
        steps.push(taskStep(a, wantsReminder(text)));
        break;
      case "create_note":
        steps.push(noteStep(a));
        break;
      case "question":
        steps.push(await answer(a.question ?? text));
        break;
      case "log_expense":
      case "log_income":
        steps.push({ kind: "info", text: "Money entries: add those on the phone for now" });
        break;
      default:
        steps.push({ kind: "info", text: "Sorry, I didn't understand that" });
    }
  }
  return steps;
}

function isoDay(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

/** Local date and time → epoch ms. "2026-11-24" + "16:00". */
function localMs(date: string | undefined, time: string | undefined): number | null {
  if (!date) return null;
  const t = Date.parse(`${date}T${time ?? "00:00"}`);
  return Number.isNaN(t) ? null : t;
}

/** Turns the AI's create_task into a draft. "Remind me" always ends up as a reminder. */
function taskStep(a: AiAction, remind: boolean): Step {
  const title = (a.title ?? "").trim();
  if (!title) return { kind: "info", text: "Didn't catch the task" };
  const hasTime = !!a.due_time;
  const dueAt = localMs(a.due_date, a.due_time);
  const remindRaw = a.remind_at ? Date.parse(a.remind_at) : NaN;
  let remindAt = Number.isNaN(remindRaw) ? null : remindRaw;
  if (remindAt === null && remind) {
    // Said "remind me" but no time: the time they gave for the task, else 9 am on the day they gave,
    // else the next round hour.
    remindAt = dueAt !== null && hasTime ? dueAt : dueAt !== null ? localMs(a.due_date, "09:00") : nextRoundHour();
  }
  const draft: Draft =
    remindAt !== null
      ? { kind: "reminder", title, remindAt, dueAt: hasTime || !remind ? dueAt : null, hasTime }
      : { kind: "task", title, remindAt: null, dueAt, hasTime };
  return draftStep(draft, a.repeat ?? null);
}

/** What the card says about a draft, e.g. "today 4:00 pm". */
export function describe(d: Draft): string {
  if (d.kind === "reminder" && d.remindAt !== null) return whenText(d.remindAt);
  if (d.dueAt !== null) return `due ${whenText(d.dueAt, d.hasTime)}`;
  return "on Today";
}

function draftStep(first: Draft, repeatText: string | null = null): Step {
  const repeat = parseRule(repeatText);
  const heading = (d: Draft) => (d.kind === "reminder" ? "Reminder" : "Task");
  return {
    kind: "save",
    heading: heading(first),
    title: first.title,
    detail: repeat ? `${describe(first)} · repeats` : describe(first),
    draft: first,
    commit: async (edited?: Draft) => {
      const d = edited ?? first;
      const { planId, bucketId } = await cloud.firstPlanAndBucket();
      const position = (await cloud.highestPosition(bucketId)) + 1;
      const row = newRow(
        { title: d.title, dueAt: d.dueAt, dueAllDay: !d.hasTime, remindAt: d.kind === "reminder" ? d.remindAt : null, repeat },
        { planId, bucketId, position },
      );
      await emit("buddy-action", { type: "addRow", row });
      return d.kind === "reminder" && d.remindAt !== null
        ? `Reminder set for ${whenText(d.remindAt)}`
        : d.dueAt !== null
          ? `Task added for ${whenText(d.dueAt, d.hasTime)}`
          : "Task added to Today";
    },
  };
}

function noteStep(a: AiAction): Step {
  const body = (a.question ?? a.title ?? "").trim();
  if (!body) return { kind: "info", text: "Didn't catch the note" };
  const title = (a.title ?? "").trim() || body.slice(0, 40);
  return {
    kind: "save",
    heading: "Note",
    title,
    detail: body === title ? "" : body.slice(0, 80),
    commit: async () => {
      const now = new Date().toISOString();
      const row = {
        id: crypto.randomUUID(),
        kind: "note",
        data: { title, body, pinned: false, tags: [] },
        occurred_on: null,
        ref_id: null,
        created_at: now,
        updated_at: now,
        deleted_at: null,
      };
      await emit("buddy-action", { type: "addNote", row });
      return "Note saved";
    },
  };
}

async function answer(question: string): Promise<Step> {
  try {
    const now = new Date();
    const r = await withTimeout(
      cloud.buddyAi<AiChatReply>({
        mode: "chat",
        today: isoDay(now),
        now: `${String(now.getHours()).padStart(2, "0")}:${String(now.getMinutes()).padStart(2, "0")}`,
        messages: [{ role: "user", text: question }],
      }),
      30_000,
    );
    if (r.error) return { kind: "info", text: r.error };
    return { kind: "answer", text: r.text?.trim() || "No answer" };
  } catch {
    return { kind: "info", text: "Couldn't reach Buddy. Check the internet and try again." };
  }
}
