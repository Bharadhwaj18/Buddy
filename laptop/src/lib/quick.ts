// Quick words that skip the AI, and the times Buddy picks when you don't say one.
//   "rm call Ravi 4pm" or "!call Ravi"  → a reminder (next round hour if no time is given)
//   "t: send the deck"                   → a task (on Today, no time)
// Times understood at the end: "4pm", "4:30 pm", "at 16:00", "in 20 min", "in 2 hours",
// "tonight", "tomorrow", "tomorrow 9am".

export interface Draft {
  kind: "reminder" | "task";
  title: string;
  /** Epoch ms or null. */
  remindAt: number | null;
  dueAt: number | null;
  /** True when dueAt carries a time of day (not just a date). */
  hasTime: boolean;
}

/** The next full hour after now: 3:10 → 4:00, 3:00 → 4:00. */
export function nextRoundHour(now = Date.now()): number {
  const d = new Date(now);
  d.setMinutes(0, 0, 0);
  d.setHours(d.getHours() + 1);
  return d.getTime();
}

/** Does the text ask to be reminded? */
export function wantsReminder(text: string): boolean {
  return /\bremind(er|ers)?\b/i.test(text);
}

/** Recognises the quick words. Null when the text doesn't start with one (then the AI reads it). */
export function parseQuick(input: string, now = Date.now()): Draft | null {
  const text = input.trim();
  let kind: Draft["kind"];
  let rest: string;
  let m: RegExpMatchArray | null;
  if ((m = text.match(/^(?:rm\s+|!\s*)(.+)$/i))) {
    kind = "reminder";
    rest = m[1];
  } else if ((m = text.match(/^t:\s*(.+)$/i))) {
    kind = "task";
    rest = m[1];
  } else {
    return null;
  }
  const { title, at, hasTime } = splitTime(rest, now);
  if (!title) return null;
  if (kind === "reminder") {
    const remindAt = at === null ? nextRoundHour(now) : hasTime ? at : atHour(at, 9);
    return { kind, title, remindAt, dueAt: null, hasTime: true };
  }
  return { kind, title, remindAt: null, dueAt: at, hasTime: at !== null && hasTime };
}

function atHour(ms: number, h: number, mi = 0): number {
  const d = new Date(ms);
  d.setHours(h, mi, 0, 0);
  return d.getTime();
}

/** "4pm" / "4:30 pm" / "16:00" → [h, m], or null. */
function clock(s: string): [number, number] | null {
  const m = s.trim().match(/^(\d{1,2})(?::(\d{2}))?\s*(am|pm)?$/i);
  if (!m) return null;
  let h = Number(m[1]);
  const mi = m[2] ? Number(m[2]) : 0;
  const ap = m[3]?.toLowerCase();
  if (!ap && !m[2]) return null; // a bare number like "3" is too ambiguous
  if (ap === "pm" && h < 12) h += 12;
  if (ap === "am" && h === 12) h = 0;
  if (h > 23 || mi > 59) return null;
  return [h, mi];
}

/** Takes a time phrase off the end of the text. at is null when there is none. */
export function splitTime(text: string, now = Date.now()): { title: string; at: number | null; hasTime: boolean } {
  const s = text.trim();
  const tidy = (t: string) => t.replace(/[\s,]+(at|on|by)?$/i, "").trim();

  let m = s.match(/^(.*?)\s+in\s+(\d+)\s*(m|min|mins|minutes?|h|hr|hrs|hours?)$/i);
  if (m) {
    const n = Number(m[2]);
    const minutes = /^h/i.test(m[3]) ? n * 60 : n;
    return { title: tidy(m[1]), at: now + minutes * 60_000, hasTime: true };
  }

  m = s.match(/^(.*?)\s+(tomorrow|tmrw|today|tonight)(?:\s+(?:at\s+)?(\d{1,2}(?::\d{2})?\s*(?:am|pm)?))?$/i);
  if (m) {
    const word = m[2].toLowerCase();
    const day = new Date(now);
    if (word === "tomorrow" || word === "tmrw") day.setDate(day.getDate() + 1);
    const c = m[3] ? clock(m[3]) : null;
    if (m[3] && !c) return { title: s, at: null, hasTime: false };
    if (c) return { title: tidy(m[1]), at: atHour(day.getTime(), c[0], c[1]), hasTime: true };
    if (word === "tonight") return { title: tidy(m[1]), at: atHour(day.getTime(), 20), hasTime: true };
    return { title: tidy(m[1]), at: atHour(day.getTime(), 0), hasTime: false };
  }

  m = s.match(/^(.*?)\s+(?:at\s+)?(\d{1,2}(?::\d{2})?\s*(?:am|pm)?)$/i);
  if (m) {
    const c = clock(m[2]);
    if (c) {
      let at = atHour(now, c[0], c[1]);
      if (at <= now) at += 86_400_000; // 9am said at 3pm means tomorrow
      return { title: tidy(m[1]), at, hasTime: true };
    }
  }
  return { title: s, at: null, hasTime: false };
}

export interface Chip {
  id: string;
  label: string;
}

/** One-tap fixes shown on the saving card. */
export function chipsFor(d: Draft, now = Date.now()): Chip[] {
  const chips: Chip[] = [];
  if (d.kind === "reminder") chips.push({ id: "task", label: "Task instead" });
  else chips.push({ id: "remind", label: "Remind me" });
  chips.push({ id: "1h", label: "In 1 hr" });
  if (new Date(now).getHours() < 18) chips.push({ id: "evening", label: "6 pm" });
  chips.push({ id: "tomorrow", label: "Tomorrow 9 am" });
  return chips;
}

/** Applies a chip to the draft. */
export function applyChip(d: Draft, id: string, now = Date.now()): Draft {
  const asReminder = (at: number): Draft => ({ ...d, kind: "reminder", remindAt: at, dueAt: null, hasTime: true });
  switch (id) {
    case "task":
      return { ...d, kind: "task", remindAt: null };
    case "remind":
      return asReminder(d.dueAt !== null && d.hasTime && d.dueAt > now ? d.dueAt : nextRoundHour(now));
    case "1h":
      return asReminder(now + 3_600_000);
    case "evening":
      return asReminder(atHour(now, 18));
    case "tomorrow":
      return asReminder(atHour(now + 86_400_000, 9));
    default:
      return d;
  }
}
