// The same sentences the phone is tested with (shared/time-cases.json): both must read times alike.
import { describe, expect, it } from "vitest";
import { parseWhen } from "./when";
import raw from "../../../shared/time-cases.json?raw";

const shared = JSON.parse(raw) as {
  now: string;
  cases: Array<[string, string, string | null, string | null]>;
};
const [d, t] = shared.now.split("T");
const [y, mo, day] = d.split("-").map(Number);
const [h, mi] = t.split(":").map(Number);
const now = new Date(y, mo - 1, day, h, mi);

describe("shared time cases", () => {
  for (const [text, title, date, time] of shared.cases) {
    it(text, () => {
      const r = parseWhen(text, now);
      expect([r.title, r.date, r.time]).toEqual([title, date, time]);
    });
  }
});
