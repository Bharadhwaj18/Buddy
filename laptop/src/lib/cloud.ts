// Talks to the same Supabase backend as the phone app, signed in as the same user.
// Only the reminder engine window refreshes the sign-in token (two windows refreshing the same token
// would sign you out); the other windows read the saved session and make their own requests.

import { createClient, type SupabaseClient, type Session, type RealtimeChannel } from "@supabase/supabase-js";
import { SUPABASE_URL, SUPABASE_KEY } from "../config";
import type { Row } from "./tasks";

export const isEngine = new URLSearchParams(location.search).get("view") === "cards";

/**
 * Every request gives up after a while. Without this, a request made just as the laptop slept or changed
 * network can wait forever, and the engine (one job at a time) would wait with it.
 */
const timedFetch: typeof fetch = (input, init) => {
  const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), url.includes("/functions/") ? 70_000 : 20_000);
  init?.signal?.addEventListener("abort", () => ctrl.abort());
  return fetch(input, { ...init, signal: ctrl.signal }).finally(() => clearTimeout(timer));
};

export const supabase: SupabaseClient = createClient(SUPABASE_URL, SUPABASE_KEY, {
  global: { fetch: timedFetch },
  auth: {
    persistSession: true,
    autoRefreshToken: isEngine,
    detectSessionInUrl: false,
    storageKey: "buddy-auth",
  },
});

export async function signIn(email: string, password: string): Promise<void> {
  const { error } = await supabase.auth.signInWithPassword({ email: email.trim(), password });
  if (error) throw error;
}

export async function signOut(): Promise<void> {
  await supabase.auth.signOut();
}

export async function currentSession(): Promise<Session | null> {
  const { data } = await supabase.auth.getSession();
  return data.session;
}

// ---------------------------------------------------------------- outbox
// Writes go here first, so an offline laptop keeps them and sends them when it's back online.

const OUTBOX = "buddy-outbox";
interface Pending {
  table: string;
  row: Record<string, unknown>;
  onConflict: string;
}

function readOutbox(): Pending[] {
  try {
    return JSON.parse(localStorage.getItem(OUTBOX) ?? "[]");
  } catch {
    return [];
  }
}

function writeOutbox(items: Pending[]): void {
  try {
    localStorage.setItem(OUTBOX, JSON.stringify(items));
  } catch {
    /* ignore: the next write retries */
  }
}

/** Rows of [table] still waiting to be sent, oldest first. */
export function pending(table: string): Array<Record<string, unknown> & { id: string }> {
  return readOutbox()
    .filter((p) => p.table === table && typeof p.row.id === "string")
    .map((p) => p.row as Record<string, unknown> & { id: string });
}

export function queued(): number {
  return readOutbox().length;
}

/**
 * Keeps a write on this laptop right away (it survives a restart or a lost connection). Call [flush]
 * afterwards to send it.
 */
export function enqueue(table: string, row: Record<string, unknown>, onConflict = "id"): void {
  writeOutbox([...readOutbox(), { table, row, onConflict }]);
}

export async function write(table: string, row: Record<string, unknown>, onConflict = "id"): Promise<void> {
  enqueue(table, row, onConflict);
  await flush();
}

const FAILED = "buddy-outbox-failed";

/** Writes the server will never take (kept aside so they can't hold up everything after them). */
export function failedWrites(): number {
  try {
    return (JSON.parse(localStorage.getItem(FAILED) ?? "[]") as unknown[]).length;
  } catch {
    return 0;
  }
}

function setAside(p: Pending, why: string): void {
  try {
    const list = JSON.parse(localStorage.getItem(FAILED) ?? "[]") as unknown[];
    localStorage.setItem(FAILED, JSON.stringify([...list, { ...p, why, at: new Date().toISOString() }].slice(-50)));
  } catch {
    /* ignore */
  }
}

let flushing: Promise<boolean> | null = null;
const SEND_LIMIT_MS = 30_000;

/** The last time a send didn't go through, and why (kept on this laptop, for working out what went wrong). */
function note(why: string, p: Pending): void {
  try {
    localStorage.setItem("buddy-outbox-last-problem", JSON.stringify({ at: new Date().toISOString(), why, table: p.table, waiting: readOutbox().length }));
  } catch {
    /* ignore */
  }
  console.warn("outbox:", why);
}

/**
 * Sends queued writes in order. A connection problem stops it (order kept, retried later); a write the
 * server rejects for good (bad data, not allowed) is set aside so the rest still go through.
 */
export function flush(): Promise<boolean> {
  // The "already sending" mark is cleared in a .finally() that always runs after it was set. Clearing it
  // inside the async body ran too early when there was nothing to send (the body finished before the mark
  // was set), which left it set for good: nothing was sent again until Buddy restarted.
  if (!flushing) flushing = sendAll().finally(() => (flushing = null));
  return flushing;
}

async function sendAll(): Promise<boolean> {
  let sent = 0;
  try {
    for (;;) {
      const next = readOutbox()[0];
      if (!next) return true;
      // A hard limit on each send. Without it one send that never answered (seen after the laptop slept)
      // held the outbox shut until Buddy restarted, while everything else kept working.
      const result = await Promise.race([
        supabase.from(next.table).upsert([next.row], { onConflict: next.onConflict }).then((r) => r),
        new Promise<null>((resolve) => setTimeout(() => resolve(null), SEND_LIMIT_MS)),
      ]);
      if (result === null) {
        note("send took too long", next);
        return false;
      }
      const { error, status } = result;
      if (error) {
        const forGood = status >= 400 && status < 500 && ![401, 408, 429].includes(status);
        if (!forGood) {
          note(`${status} ${error.message}`, next);
          return false;
        }
        console.warn("outbox: server refused a write", status, error.message);
        setAside(next, `${status} ${error.message}`);
      } else {
        sent++;
        try {
          localStorage.setItem("buddy-outbox-last-sent", String(Date.now()));
        } catch {
          /* ignore */
        }
      }
      writeOutbox(readOutbox().slice(1));
    }
  } finally {
    if (sent > 0) void poke();
  }
}

// ---------------------------------------------------------------- instant sync between laptop and phone
// A tiny "something changed" signal (no data in it) on a channel named after the user. The other side
// then syncs at once instead of at its next regular check.

let pokeChannel: RealtimeChannel | null = null;
let pokeUser: string | null = null;

/** Listens for the phone's "something changed" signal. Call again after sign-in; it re-joins if needed. */
export async function watchPokes(onPoke: () => void): Promise<void> {
  const uid = (await currentSession())?.user.id ?? null;
  if (uid === pokeUser && pokeChannel) return;
  if (pokeChannel) await supabase.removeChannel(pokeChannel);
  pokeChannel = null;
  pokeUser = uid;
  if (!uid) return;
  pokeChannel = supabase
    .channel(`sync:${uid}`, { config: { broadcast: { self: false } } })
    .on("broadcast", { event: "poke" }, () => onPoke())
    .subscribe();
}

/** Tells the phone to sync now. Best effort: the phone also syncs on its own. */
export async function poke(): Promise<void> {
  try {
    if (pokeChannel?.state === "joined") await pokeChannel.send({ type: "broadcast", event: "poke", payload: { from: "laptop" } });
    else if (pokeChannel) await pokeChannel.httpSend("poke", { from: "laptop" });
  } catch {
    /* the phone's regular sync picks it up */
  }
}

// ---------------------------------------------------------------- reads

/** Unfinished tasks, plus those finished today (for "2 of 6 done"). */
export async function openTasks(): Promise<Row[]> {
  const start = new Date();
  start.setHours(0, 0, 0, 0);
  const { data, error } = await supabase
    .from("tasks")
    .select("*")
    .is("deleted_at", null)
    .or(`status.neq.done,completed_at.gte.${start.toISOString()}`);
  if (error) throw error;
  return (data ?? []) as Row[];
}

/** Every list (plan) that isn't archived, in order, with the column new things go into. */
export async function plans(): Promise<Array<{ id: string; name: string; bucketId: string | null }>> {
  const { data: ps, error } = await supabase
    .from("plans")
    .select("id,name,position")
    .is("deleted_at", null)
    .eq("archived", false)
    .order("position", { ascending: true });
  if (error) throw error;
  const { data: bs, error: e2 } = await supabase
    .from("buckets")
    .select("id,plan_id,position")
    .is("deleted_at", null)
    .order("position", { ascending: true });
  if (e2) throw e2;
  const buckets = (bs ?? []) as Array<{ id: string; plan_id: string }>;
  return ((ps ?? []) as Array<{ id: string; name: string }>).map((p) => ({
    id: p.id,
    name: p.name,
    bucketId: buckets.find((b) => b.plan_id === p.id)?.id ?? null,
  }));
}

export interface Ack {
  task_id: string;
  remind_at: string;
  action: string;
  snooze_until: string | null;
  acked_at: string;
  device: string | null;
}

export async function recentAcks(since: Date): Promise<Ack[]> {
  const { data, error } = await supabase
    .from("reminder_acks")
    .select("*")
    .gte("acked_at", since.toISOString())
    .order("acked_at", { ascending: true });
  if (error) throw error;
  return (data ?? []) as Ack[];
}

export async function laptopSeenAt(): Promise<number | null> {
  const { data } = await supabase
    .from("devices")
    .select("last_seen")
    .eq("kind", "laptop")
    .order("last_seen", { ascending: false })
    .limit(1);
  const v = data?.[0]?.last_seen as string | undefined;
  return v ? Date.parse(v) : null;
}

export async function heartbeat(deviceId: string, name: string): Promise<void> {
  await supabase.from("devices").upsert([{ id: deviceId, name, kind: "laptop" }], { onConflict: "id" });
}

/** Saves a reminder answer in the outbox (sent with the next flush), keyed on the reminder's own time. */
export function ack(
  taskId: string,
  remindAt: number,
  action: string,
  device: string,
  snoozeUntil: number | null = null,
): void {
  enqueue(
    "reminder_acks",
    {
      task_id: taskId,
      remind_at: new Date(remindAt).toISOString(),
      action,
      snooze_until: snoozeUntil !== null ? new Date(snoozeUntil).toISOString() : null,
      device,
    },
    "task_id,remind_at",
  );
}

/** Calls the buddy-ai Edge Function (the same one the phone uses). */
export async function buddyAi<T>(body: Record<string, unknown>): Promise<T> {
  const { data, error } = await supabase.functions.invoke("buddy-ai", { body });
  if (error) throw error;
  return data as T;
}

// ---------------------------------------------------------------- OTPs from the phone

export interface Otp {
  id: string;
  code: string;
  source: string | null;
  created_at: string;
}

/** Codes the phone sent in the last few minutes (older ones are useless, and the server clears them). */
export async function freshOtps(): Promise<Otp[]> {
  const since = new Date(Date.now() - 3 * 60_000).toISOString();
  const { data, error } = await supabase.from("otp_relay").select("*").gte("created_at", since).order("created_at");
  if (error) throw error;
  return (data ?? []) as Otp[];
}

export async function deleteOtp(id: string): Promise<void> {
  await supabase.from("otp_relay").delete().eq("id", id);
}

/** Calls [onNew] the moment the phone sends a code (Supabase Realtime). */
export function watchOtps(onNew: () => void): void {
  supabase
    .channel("otp-relay")
    .on("postgres_changes", { event: "INSERT", schema: "public", table: "otp_relay" }, () => onNew())
    .subscribe();
}

// ---------------------------------------------------------------- parcels (read from items)

export interface ParcelInfo {
  id: string;
  merchant: string;
  title: string | null;
  status: string;
  eta: string | null;
  deliveryCode: string | null;
}

/** Deliveries still on the way (the phone reads them from shop / courier SMS). */
export async function activeParcels(): Promise<ParcelInfo[]> {
  const { data, error } = await supabase
    .from("items")
    .select("id,data")
    .eq("kind", "parcel")
    .is("deleted_at", null)
    .order("updated_at", { ascending: false })
    .limit(30);
  if (error) throw error;
  return (data ?? [])
    .map((r): Record<string, unknown> & { id: string } => ({ ...(r.data as Record<string, unknown>), id: r.id as string }))
    .filter((p) => !p.hidden && p.status !== "DELIVERED" && p.status !== "CANCELLED")
    .map((p) => ({
      id: p.id,
      merchant: String(p.merchant ?? ""),
      title: (p.title as string | null) ?? null,
      status: String(p.status ?? "ORDERED"),
      eta: (p.eta as string | null) ?? null,
      deliveryCode: (p.deliveryCode as string | null) ?? null,
    }));
}
