// Reads dates and times out of typed text, the same way the phone does (QuickParse.kt + Clock.kt).
// Both are checked against shared/time-cases.json, so "2.30 pm" means 2:30 pm on both.
//
// Times: 2pm · 2 pm · 2 p.m. · 2:30pm · 2.30 pm · 2 30 pm · 230pm · 2:30 · 14:30 · 1430 hrs · 09:00 · at 2 · by 4
//        @4pm · around 7 · at 2.30 · half past 2 · quarter past 3 · quarter to 5 · 10 past 4 · 20 to 6 · 2 o'clock
//        two thirty pm · five pm · noon · midday · midnight · 2 in the afternoon · 8 in the morning · 7 tonight
// Days:  today · tonight · tomorrow / tmrw · day after tomorrow · monday · next friday · 28 nov · nov 28 · 28/11
//        the 5th · this weekend · next week · this evening · tomorrow morning · eod
// Relative: in 20 min · in 2 hours · in half an hour · in an hour and a half · 15 minutes from now
// Without am/pm: morning / afternoon / evening / tonight decide; else 12 → noon, 1–6 → pm,
// 7–11 → the next one to come today (am on a later day).

export interface When {
  title: string;
  /** "YYYY-MM-DD" or null. */
  date: string | null;
  /** "HH:mm" or null. */
  time: string | null;
  /** Said "remind me", or gave a time. */
  remind: boolean;
  /** A clock time was actually said (not filled in from "morning" or a default). */
  timeSaid: boolean;
}

// ---------------------------------------------------------------- small date helpers (local time)

type Day = { y: number; m: number; d: number };
const dayOf = (t: Date): Day => ({ y: t.getFullYear(), m: t.getMonth() + 1, d: t.getDate() });
const toDate = (x: Day) => new Date(x.y, x.m - 1, x.d);
const plusDays = (x: Day, n: number): Day => {
  const t = toDate(x);
  t.setDate(t.getDate() + n);
  return dayOf(t);
};
const same = (a: Day | null, b: Day) => a !== null && a.y === b.y && a.m === b.m && a.d === b.d;
const before = (a: Day, b: Day) => toDate(a).getTime() < toDate(b).getTime();
const isoDay = (x: Day) => `${x.y}-${String(x.m).padStart(2, "0")}-${String(x.d).padStart(2, "0")}`;
const monthLength = (y: number, m: number) => new Date(y, m, 0).getDate();
/** 1 = Monday … 7 = Sunday. */
const weekday = (x: Day) => ((toDate(x).getDay() + 6) % 7) + 1;
const nextOrSame = (x: Day, dow: number) => plusDays(x, (dow - weekday(x) + 7) % 7);
const nextDow = (x: Day, dow: number) => plusDays(x, ((dow - weekday(x) + 6) % 7) + 1);

type Time = { h: number; m: number };
const hhmm = (t: Time) => `${String(t.h).padStart(2, "0")}:${String(t.m).padStart(2, "0")}`;
const mins = (t: Time) => t.h * 60 + t.m;

const DAYS: Record<string, number> = {
  mon: 1, monday: 1, tue: 2, tues: 2, tuesday: 2, wed: 3, wednesday: 3, thu: 4, thur: 4, thurs: 4, thursday: 4,
  fri: 5, friday: 5, sat: 6, saturday: 6, sun: 7, sunday: 7,
};
const MONTHS: Record<string, number> = {
  jan: 1, feb: 2, mar: 3, apr: 4, may: 5, jun: 6, jul: 7, aug: 8, sep: 9, sept: 9, oct: 10, nov: 11, dec: 12,
};
const DAY = "(mon|monday|tue|tues|tuesday|wed|wednesday|thu|thur|thurs|thursday|fri|friday|sat|saturday|sun|sunday)";
const MONTH = "(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*";
const ORD = "(\\d{1,2})(?:st|nd|rd|th)";

function monthKey(s: string): number {
  const k = s.toLowerCase();
  return MONTHS[k] ?? MONTHS[k.slice(0, 4)] ?? MONTHS[k.slice(0, 3)];
}

function futureDate(today: Day, month: number, day: number): Day {
  const d = { y: today.y, m: month, d: Math.min(Math.max(day, 1), monthLength(today.y, month)) };
  return before(d, today) ? { ...d, y: d.y + 1, d: Math.min(day, monthLength(d.y + 1, month)) } : d;
}

function nextDayOfMonth(today: Day, day: number): Day {
  const thisMonth = { ...today, d: Math.min(Math.max(day, 1), monthLength(today.y, today.m)) };
  if (!before(thisMonth, today)) return thisMonth;
  const nm = today.m === 12 ? { y: today.y + 1, m: 1 } : { y: today.y, m: today.m + 1 };
  return { ...nm, d: Math.min(Math.max(day, 1), monthLength(nm.y, nm.m)) };
}

// ---------------------------------------------------------------- clock times (mirror of Clock.kt)

interface Found {
  index: number;
  length: number;
  hour: number;
  minute: number;
  meridiem: "a" | "p" | null;
  exact: boolean;
  part: string | null;
}

const WORDS: Record<string, number> = {
  one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10, eleven: 11, twelve: 12,
};
const MINUTE_WORDS: Record<string, number> = {
  "oh five": 5, "o five": 5, five: 5, ten: 10, fifteen: 15, twenty: 20, "twenty five": 25, thirty: 30,
  "thirty five": 35, forty: 40, "forty five": 45, fifty: 50, "fifty five": 55,
};
const CUE_WORDS = "(?:at|by|@|around|about|till|until|before|from)";
const CUE = `(?:${CUE_WORDS}\\s?)?`;
const CUE_REQ = `${CUE_WORDS}\\s?`;
const HOUR = "(\\d{1,2}|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)";
const MER = "(?:\\s?(a\\.?\\s?m\\.?|p\\.?\\s?m\\.?)())";
const PART = "(?:\\s(?:in the |at |this )?(morning|afternoon|evening|night)|\\s(tonight))?";
const MINW = "(oh five|o five|twenty[- ]five|thirty[- ]five|forty[- ]five|fifty[- ]five|ten|fifteen|twenty|thirty|forty|fifty)";
const UNITS =
  "mins?|minutes?|hours?|hrs?|days?|weeks?|months?|years?|secs?|seconds?|kgs?|kms?|g|gms?|grams?|litres?|liters?|l|ml|rs|rupees|bucks|people|persons|times|pages?|pcs|pieces|items";

const g = (m: RegExpMatchArray, i: number) => m[i] ?? "";
const hourOf = (s: string): number | null => (/^\d+$/.test(s) ? Number(s) : WORDS[s.toLowerCase()] ?? null);
const minuteWord = (s: string): number | null => MINUTE_WORDS[s.toLowerCase().replace("-", " ")] ?? null;
const mer = (m: RegExpMatchArray, i: number): "a" | "p" | null => {
  const c = (g(m, i) || g(m, i + 1)).charAt(0).toLowerCase();
  return c === "a" || c === "p" ? c : null;
};
const part = (m: RegExpMatchArray, i: number): string | null =>
  g(m, i) ? g(m, i).toLowerCase() : g(m, i + 1) ? "night" : null;
const leadingZero = (raw: string) => raw.length === 2 && raw.startsWith("0");

type Reader = (m: RegExpMatchArray) => Omit<Found, "index" | "length"> | null;
const form = (pattern: string, read: Reader) => ({
  regex: new RegExp(`(?<=\\s)${pattern}(?=[\\s,!?;)]|\\.(?:\\s|$)|$)(?!\\s(?:${UNITS})\\b|%)`, "gi"),
  read,
});

const FORMS = [
  // half past 2, quarter past 3 pm
  form(`${CUE}(half|quarter) past ${HOUR}${MER}?${PART}`, (m) => {
    const h = hourOf(g(m, 2));
    if (h === null) return null;
    return { hour: h, minute: g(m, 1).toLowerCase() === "half" ? 30 : 15, meridiem: mer(m, 3), exact: false, part: part(m, 5) };
  }),
  // quarter to 5
  form(`${CUE}quarter to ${HOUR}${MER}?${PART}`, (m) => {
    const h = hourOf(g(m, 1));
    if (h === null) return null;
    return { hour: h === 1 ? 12 : h - 1, minute: 45, meridiem: mer(m, 2), exact: false, part: part(m, 4) };
  }),
  // 10 past 4, 20 to 6, twenty five past 3
  form(`${CUE}(\\d{1,2}|five|ten|twenty|twenty[- ]five) (?:minutes? |mins? )?(past|after|to) ${HOUR}${MER}?${PART}`, (m) => {
    const n = /^\d+$/.test(g(m, 1)) ? Number(g(m, 1)) : minuteWord(g(m, 1));
    const h = hourOf(g(m, 3));
    if (n === null || h === null || n < 1 || n > 59) return null;
    if (g(m, 2).toLowerCase() === "to") return { hour: h === 1 ? 12 : h - 1, minute: 60 - n, meridiem: mer(m, 4), exact: false, part: part(m, 6) };
    return { hour: h, minute: n, meridiem: mer(m, 4), exact: false, part: part(m, 6) };
  }),
  // two thirty pm, at 7 forty five (before "five pm" below can take the end of it)
  form(`${CUE}${HOUR} ${MINW}${MER}?${PART}`, (m) => {
    const h = hourOf(g(m, 1));
    const min = minuteWord(g(m, 2));
    if (h === null || min === null) return null;
    const cued = new RegExp(`^${CUE_REQ}`, "i").test(m[0]);
    if (/^\d+$/.test(g(m, 1)) && !cued && mer(m, 3) === null) return null;
    return { hour: h, minute: min, meridiem: mer(m, 3), exact: false, part: part(m, 5) };
  }),
  // 2pm, 2:30 pm, 2.30pm, 2 30 pm, 2 o'clock pm, two pm
  form(`${CUE}${HOUR}(?:[:.](\\d{2})|\\s(\\d{2})(?=\\s?[ap]))?(?:\\s?o'?\\s?clock)?${MER}${PART}`, (m) => {
    const h = hourOf(g(m, 1));
    if (h === null) return null;
    return { hour: h, minute: Number(g(m, 2) || g(m, 3) || "0"), meridiem: mer(m, 4), exact: false, part: part(m, 6) };
  }),
  // 230pm, 1030am
  form(`${CUE}(\\d{1,2})(\\d{2})${MER}${PART}`, (m) => ({
    hour: Number(g(m, 1)), minute: Number(g(m, 2)), meridiem: mer(m, 3), exact: false, part: part(m, 5),
  })),
  // 1430 hrs, 0900 hours
  form(`${CUE}([01]\\d|2[0-3])([0-5]\\d)\\s?(?:hrs|hours|h)`, (m) => ({
    hour: Number(g(m, 1)), minute: Number(g(m, 2)), meridiem: null, exact: true, part: null,
  })),
  // 14:30, 2:30, 09:00, 14:30 hrs
  form(`${CUE}(\\d{1,2}):(\\d{2})(\\s?(?:hrs|hours|h))?${PART}`, (m) => {
    const raw = g(m, 1);
    return { hour: Number(raw), minute: Number(g(m, 2)), meridiem: null, exact: leadingZero(raw) || Number(raw) > 12 || !!g(m, 3), part: part(m, 4) };
  }),
  // 14.30 hrs
  form(`${CUE}(\\d{1,2})\\.(\\d{2})\\s?(?:hrs|hours)`, (m) => ({
    hour: Number(g(m, 1)), minute: Number(g(m, 2)), meridiem: null, exact: true, part: null,
  })),
  // at 2.30 (a dot needs a cue word, so "2.50 kg" isn't a time)
  form(`${CUE_REQ}(\\d{1,2})\\.(\\d{2})${PART}`, (m) => {
    const raw = g(m, 1);
    return { hour: Number(raw), minute: Number(g(m, 2)), meridiem: null, exact: leadingZero(raw) || Number(raw) > 12, part: part(m, 3) };
  }),
  // 2 o'clock
  form(`${CUE}${HOUR}\\s?o'?\\s?clock${PART}`, (m) => {
    const h = hourOf(g(m, 1));
    return h === null ? null : { hour: h, minute: 0, meridiem: null, exact: false, part: part(m, 2) };
  }),
  // noon, 12 noon, midday, midnight
  form(`${CUE}(?:12\\s?)?(noon|midday|midnight)`, (m) =>
    g(m, 1).toLowerCase() === "midnight"
      ? { hour: 23, minute: 59, meridiem: null, exact: true, part: null }
      : { hour: 12, minute: 0, meridiem: null, exact: true, part: null },
  ),
  // at 7, by 4, around 6 tonight
  form(`${CUE_REQ}${HOUR}${PART}`, (m) => {
    const raw = g(m, 1);
    const h = hourOf(raw);
    return h === null ? null : { hour: h, minute: 0, meridiem: null, exact: leadingZero(raw) || h > 12, part: part(m, 2) };
  }),
  // 2 in the afternoon, 7 tonight, 9 at night
  form(`${HOUR}(?:\\s(?:in the |at |this )(morning|afternoon|evening|night)|\\s(tonight))`, (m) => {
    const h = hourOf(g(m, 1));
    return h === null ? null : { hour: h, minute: 0, meridiem: null, exact: false, part: part(m, 2) };
  }),
];

/** The first clock time in [text] (which starts and ends with a space), or null. */
export function findClock(text: string): Found | null {
  for (const f of FORMS) {
    for (const m of text.matchAll(f.regex)) {
      const r = f.read(m);
      if (!r) continue;
      if (r.hour < 0 || r.hour > 23 || r.minute < 0 || r.minute > 59) continue;
      if (r.meridiem !== null && (r.hour === 0 || r.hour > 12)) continue;
      return { ...r, index: m.index ?? 0, length: m[0].length };
    }
  }
  return null;
}

function resolveClock(f: Found, dayPart: string | null, date: Day | null, today: Day, now: Time): Time {
  let h = f.hour;
  const m = f.minute;
  const p = f.part ?? dayPart;
  if (f.meridiem === "a") {
    if (h === 12) h = 0;
  } else if (f.meridiem === "p") {
    if (h < 12) h += 12;
  } else if (f.exact || h === 0 || h > 12) {
    // as written
  } else if (p === "morning") {
    if (h === 12) h = 0;
  } else if (p === "afternoon" || p === "evening") {
    if (h < 12) h += 12;
  } else if (p === "night") {
    if (h === 12) return { h: 23, m: 59 };
    if (h > 4) h += 12;
  } else if (h === 12) {
    // noon
  } else if (h <= 6) {
    h += 12;
  } else if ((date === null || same(date, today)) && h * 60 + m <= mins(now)) {
    h += 12; // 7–11 already gone today: the evening one
  }
  return { h, m };
}

function partOfDayTime(word: string): Time | null {
  if (word.includes("morning")) return { h: 9, m: 0 };
  if (word.includes("afternoon")) return { h: 14, m: 0 };
  if (word.includes("evening")) return { h: 18, m: 0 };
  if (word.includes("night")) return { h: 21, m: 0 };
  return null;
}

// ---------------------------------------------------------------- the whole sentence (mirror of QuickParse.kt)

export function parseWhen(input: string, nowDate = new Date()): When {
  let text = ` ${input.trim()} `;
  const today = dayOf(nowDate);
  const nowT: Time = { h: nowDate.getHours(), m: nowDate.getMinutes() };
  let date: Day | null = null;
  let time: Time | null = null;
  let remind = false;
  let partOfDay: Time | null = null;

  const squeeze = (t: string) => ` ${t.replace(/\s+/g, " ").trim()} `;
  const take = (pattern: string, onMatch: (m: RegExpMatchArray) => void): boolean => {
    const m = text.match(new RegExp(`(?<=\\s)${pattern}(?=[\\s,.!?])`, "i"));
    if (!m || m.index === undefined) return false;
    onMatch(m);
    text = squeeze(text.slice(0, m.index) + text.slice(m.index + m[0].length));
    return true;
  };

  take("(remind me to|remind me|reminder:?|remember to)", () => (remind = true));

  // The clock time comes out first, so date and number words can't eat part of it.
  const clock = findClock(text);
  if (clock) text = squeeze(text.slice(0, clock.index) + text.slice(clock.index + clock.length));
  const timeSaid = clock !== null;

  // Relative: "in 20 minutes", "in half an hour", "in an hour and a half", "10 minutes from now".
  const relative = (amount: string, unit: string, andHalf: boolean) => {
    const a = amount.toLowerCase().trim();
    const named: Record<string, number> = {
      a: 1, an: 1, one: 1, two: 2, three: 3, five: 5, ten: 10, fifteen: 15, twenty: 20, thirty: 30, "a few": 3, few: 3, "couple of": 2,
    };
    let n: number;
    if (a.startsWith("half")) n = 0.5;
    else if (a.startsWith("a couple")) n = 2;
    else if (a in named) n = named[a];
    else if (!Number.isNaN(Number(a))) n = Number(a);
    else return;
    n += andHalf ? 0.5 : 0;
    const at = (ms: number) => {
      const t = new Date(ms);
      date = dayOf(t);
      time = { h: t.getHours(), m: t.getMinutes() };
    };
    const u = unit.toLowerCase().charAt(0);
    if (u === "s") at(nowDate.getTime() + 60_000);
    else if (u === "m") at(nowDate.getTime() + n * 60_000);
    else if (u === "h") at(nowDate.getTime() + n * 3_600_000);
    else if (u === "d") date = plusDays(today, Math.max(1, Math.floor(n)));
    else if (u === "w") date = plusDays(today, 7 * Math.max(1, Math.floor(n)));
  };
  const amount = "(\\d{1,3}(?:\\.\\d)?|half an?|an?|one|two|three|five|ten|fifteen|twenty|thirty|a couple of|a few)";
  const unit = "(seconds|second|secs|sec|minutes|minute|mins|min|m|hours|hour|hrs|hr|h|days|day|d|weeks|week|w)";
  const matched = take(`(?:in|after|within)(?: another| about| around| the next| just)? ${amount} ?${unit}( and a half)?(?: from now| time)?`, (m) =>
    relative(g(m, 1), g(m, 2), !!g(m, 3)),
  );
  if (!matched) {
    take(`${amount} ?(seconds|second|secs|sec|minutes|minute|mins|min|hours|hour|hrs|hr|days|day|weeks|week)( and a half)? (?:from now|later)`, (m) =>
      relative(g(m, 1), g(m, 2), !!g(m, 3)),
    );
  }

  // Days.
  take("day after tomorrow", () => (date = plusDays(today, 2)));
  take("(tomorrow|tmrw|tmr|tomo)", () => (date = plusDays(today, 1)));
  let tonight = false;
  take("tonight", () => {
    date = today;
    partOfDay = { h: 21, m: 0 };
    tonight = true;
  });
  take("today", () => (date = today));
  take("next week", () => (date = nextDow(today, 1)));
  take(`next ${DAY}`, (m) => (date = nextDow(today, DAYS[g(m, 1).toLowerCase()])));
  take(`(?:on |this )?${DAY}`, (m) => (date = nextOrSame(today, DAYS[g(m, 1).toLowerCase()])));
  const dayMonth = take(`(?:on )?(?:the )?(\\d{1,2})(?:st|nd|rd|th)?(?: of)? ${MONTH}`, (m) => {
    date = futureDate(today, monthKey(g(m, 2)), Number(g(m, 1)));
  });
  if (!dayMonth) {
    take(`(?:on )?${MONTH} (\\d{1,2})(?:st|nd|rd|th)?`, (m) => {
      date = futureDate(today, monthKey(g(m, 1)), Number(g(m, 2)));
    });
  }
  // "28/11", "28-11", "28/11/2026" (day first, as written in India).
  if (date === null) {
    take("(?:on )?(\\d{1,2})[/-](\\d{1,2})(?:[/-](\\d{2,4}))?", (m) => {
      const d = Number(g(m, 1));
      const mo = Number(g(m, 2));
      if (mo >= 1 && mo <= 12 && d >= 1 && d <= 31) {
        const yRaw = g(m, 3);
        const y = yRaw ? (Number(yRaw) < 100 ? 2000 + Number(yRaw) : Number(yRaw)) : null;
        date = y !== null ? { y, m: mo, d } : futureDate(today, mo, d);
      }
    });
  }
  if (date === null) take(`(?:on )?(?:the )?${ORD}`, (m) => (date = nextDayOfMonth(today, Number(g(m, 1)))));
  take("(?:this )?weekend", () => {
    if (date === null) date = nextOrSame(today, 6);
  });

  // Part of the day said anywhere ("tomorrow evening", "this morning"), for am / pm.
  let dayPart: string | null = tonight ? "night" : null;
  take("(?:in the |this |at |tomorrow )?(morning|afternoon|evening|night)", (m) => {
    dayPart = g(m, 1).toLowerCase();
    partOfDay = partOfDayTime(dayPart);
    if (m[0].toLowerCase().startsWith("tomorrow")) date = plusDays(today, 1);
  });
  take("(eod|end of (?:the )?day)", () => {
    partOfDay = { h: 18, m: 0 };
    if (date === null) date = today;
  });
  if (clock) time = resolveClock(clock, dayPart, date, today, nowT);
  if (time === null) time = partOfDay;

  // A time with no date means the next time it comes round.
  const t0 = time as Time | null;
  if (t0 !== null && date === null) date = mins(t0) > mins(nowT) ? today : plusDays(today, 1);
  // "Remind me…" with no time: 9 am on the day, or the next round hour if that's already gone.
  if (remind && time === null) {
    const d: Day = date ?? today;
    const nine = 9 * 60;
    const t: Time = same(d, today) && nine <= mins(nowT) ? { h: (nowT.h + 1) % 24, m: 0 } : { h: 9, m: 0 };
    date = same(d, today) && mins(t) < mins(nowT) ? plusDays(today, 1) : d;
    time = t;
  }

  let title = text.trim().replace(/\s+(at|on|by|in|every|the)$/i, "").trim();
  if (remind) title = title.replace(/^(to|that|about)\s+/i, "");
  title = title.charAt(0).toUpperCase() + title.slice(1);
  if (!title) return { title: input.trim(), date: null, time: null, remind: false, timeSaid: false };
  const finalDate = date as Day | null;
  const finalTime = time as Time | null;
  return {
    title,
    date: finalDate ? isoDay(finalDate) : null,
    time: finalTime ? hhmm(finalTime) : null,
    remind: remind || finalTime !== null,
    timeSaid,
  };
}

/** Epoch ms for a parsed date + time (local), or null. */
export function whenMs(w: When): number | null {
  if (!w.date) return null;
  const [y, mo, d] = w.date.split("-").map(Number);
  const [h, mi] = (w.time ?? "00:00").split(":").map(Number);
  return new Date(y, mo - 1, d, h, mi, 0, 0).getTime();
}
