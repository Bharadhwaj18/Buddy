// Repeating tasks: the same small RRULE subset the phone uses (FREQ, INTERVAL, BYDAY, BYMONTHDAY).
// Port of android/.../data/tasks/Repeat.kt, enough for the laptop: complete or skip to the next time.

export type Freq = "DAILY" | "WEEKLY" | "MONTHLY" | "YEARLY";

export interface RepeatRule {
  freq: Freq;
  interval: number;
  /** WEEKLY: 0 = Sunday … 6 = Saturday. Empty = same weekday as the date. */
  days: number[];
  /** MONTHLY: null = same day of the month; -1 = last day. */
  monthDay: number | null;
}

const DAY_CODES = ["SU", "MO", "TU", "WE", "TH", "FR", "SA"];

export function parseRule(raw: string | null | undefined): RepeatRule | null {
  if (!raw) return null;
  const parts = Object.fromEntries(
    raw.split(";").map((p) => {
      const i = p.indexOf("=");
      return [p.slice(0, i).toUpperCase(), p.slice(i + 1).toUpperCase()];
    }),
  );
  const freq = parts.FREQ as Freq | undefined;
  if (!freq || !["DAILY", "WEEKLY", "MONTHLY", "YEARLY"].includes(freq)) return null;
  const interval = Math.max(1, Number(parts.INTERVAL ?? 1) || 1);
  const days = (parts.BYDAY ?? "").split(",").map((d) => DAY_CODES.indexOf(d)).filter((d) => d >= 0);
  const monthDay = parts.BYMONTHDAY !== undefined ? Number(parts.BYMONTHDAY) : null;
  return { freq, interval, days, monthDay };
}

export function encodeRule(r: RepeatRule): string {
  const out = [`FREQ=${r.freq}`];
  if (r.interval > 1) out.push(`INTERVAL=${r.interval}`);
  if (r.freq === "WEEKLY" && r.days.length) out.push("BYDAY=" + [...r.days].sort().map((d) => DAY_CODES[d]).join(","));
  if (r.freq === "MONTHLY" && r.monthDay !== null) out.push(`BYMONTHDAY=${r.monthDay}`);
  return out.join(";");
}

/** The next occurrence strictly after [from] (which is itself an occurrence). Time of day is kept. */
export function nextAfter(from: Date, r: RepeatRule): Date {
  const d = new Date(from);
  switch (r.freq) {
    case "DAILY":
      d.setDate(d.getDate() + r.interval);
      return d;
    case "WEEKLY": {
      if (r.days.length === 0) {
        d.setDate(d.getDate() + 7 * r.interval);
        return d;
      }
      for (let i = 1; i < 366 * 2; i++) {
        const c = new Date(from);
        c.setDate(c.getDate() + i);
        // With an interval, only the weeks that are a multiple of it (counted from the anchor's week).
        if (r.days.includes(c.getDay()) && daysBetweenWeeks(from, c) % r.interval === 0) return c;
      }
      return d;
    }
    case "MONTHLY": {
      const target = new Date(from.getFullYear(), from.getMonth() + r.interval, 1);
      const lastDay = new Date(target.getFullYear(), target.getMonth() + 1, 0).getDate();
      const day = r.monthDay === -1 ? lastDay : Math.min(r.monthDay ?? from.getDate(), lastDay);
      target.setDate(day);
      target.setHours(from.getHours(), from.getMinutes(), from.getSeconds(), from.getMilliseconds());
      return target;
    }
    case "YEARLY":
      d.setFullYear(d.getFullYear() + r.interval);
      return d;
  }
}

/** Whole weeks between the Monday of each date's week (used for INTERVAL with BYDAY). */
function daysBetweenWeeks(a: Date, b: Date): number {
  const mondayOf = (x: Date) => {
    const m = new Date(x);
    m.setHours(0, 0, 0, 0);
    m.setDate(m.getDate() - ((m.getDay() + 6) % 7));
    return m.getTime();
  };
  return Math.round((mondayOf(b) - mondayOf(a)) / (7 * 86_400_000));
}

/**
 * Next due and reminder times for a repeating task. The reminder keeps its gap to the due time.
 * If the task was finished late, it keeps advancing until the next time is in the future.
 */
export function nextOccurrence(
  rule: RepeatRule,
  dueAt: number | null,
  remindAt: number | null,
  now = Date.now(),
): { dueAt: number | null; remindAt: number | null } {
  const anchor = dueAt ?? remindAt;
  if (anchor === null) return { dueAt: null, remindAt: null };
  const offset = dueAt !== null && remindAt !== null ? remindAt - dueAt : 0;
  let next = nextAfter(new Date(anchor), rule);
  while (next.getTime() + offset <= now) next = nextAfter(next, rule);
  const t = next.getTime();
  return dueAt !== null
    ? { dueAt: t, remindAt: remindAt !== null ? t + offset : null }
    : { dueAt: null, remindAt: t };
}
