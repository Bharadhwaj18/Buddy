import { beforeEach, describe, expect, it, vi } from "vitest";

// A fake server: records what was sent and answers straight away.
const sent: unknown[] = [];
vi.mock("@supabase/supabase-js", () => ({
  createClient: () => ({
    from: () => ({
      upsert: (rows: unknown[]) => {
        sent.push(...rows);
        return Promise.resolve({ error: null, status: 201 });
      },
    }),
    channel: () => ({ on: () => ({ subscribe: () => ({}) }) }),
    auth: {},
  }),
}));
vi.mock("../config", () => ({ SUPABASE_URL: "http://x", SUPABASE_KEY: "k" }));

const store = new Map<string, string>();
vi.stubGlobal("localStorage", {
  getItem: (k: string) => store.get(k) ?? null,
  setItem: (k: string, v: string) => void store.set(k, v),
  removeItem: (k: string) => void store.delete(k),
});
vi.stubGlobal("location", { search: "?view=cards" });

const cloud = await import("./cloud");

describe("outbox", () => {
  beforeEach(() => {
    store.clear();
    sent.length = 0;
  });

  it("still sends after a flush that had nothing to send", async () => {
    // This used to leave the outbox marked "busy" for good, so nothing was sent again until a restart.
    await cloud.flush();
    cloud.enqueue("tasks", { id: "a" });
    await cloud.flush();
    expect(sent).toEqual([{ id: "a" }]);
    expect(cloud.queued()).toBe(0);
  });

  it("sends in order and empties the outbox", async () => {
    cloud.enqueue("tasks", { id: "a" });
    cloud.enqueue("reminder_acks", { task_id: "a" }, "task_id,remind_at");
    await cloud.flush();
    cloud.enqueue("tasks", { id: "b" });
    await cloud.flush();
    expect(sent).toEqual([{ id: "a" }, { task_id: "a" }, { id: "b" }]);
    expect(cloud.queued()).toBe(0);
  });
});
