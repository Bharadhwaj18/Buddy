import { describe, expect, it } from "vitest";
import { chosenLists } from "./lists";

const plans = [
  { id: "p", name: "Personal", bucketId: "pb" },
  { id: "w", name: "Work", bucketId: "wb" },
  { id: "h", name: "Health", bucketId: "hb" },
];

describe("lists on this laptop", () => {
  it("is Work by default", () => expect(chosenLists(plans, null)).toEqual(["w"]));
  it("follows what was picked, in the lists' own order", () => expect(chosenLists(plans, ["h", "p"])).toEqual(["p", "h"]));
  it("ignores lists that no longer exist", () => expect(chosenLists(plans, ["gone", "h"])).toEqual(["h"]));
  it("falls back to Work when every picked list is gone", () => expect(chosenLists(plans, ["gone"])).toEqual(["w"]));
  it("shows everything when there's no Work list", () =>
    expect(chosenLists([plans[0], plans[2]], null)).toEqual(["p", "h"]));
  it("hides nothing while the lists aren't known", () => expect(chosenLists([], ["w"])).toBeNull());
});
