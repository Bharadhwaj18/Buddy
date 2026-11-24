// Talks to the same Supabase backend as the phone app, signed in as the same user.
// Only the reminder engine window refreshes the sign-in token (two windows refreshing the same token
// would sign you out); the other windows read the saved session and make their own requests.

import { createClient, type SupabaseClient, type Session } from "@supabase/supabase-js";
import { SUPABASE_URL, SUPABASE_KEY } from "../config";
import type { Row } from "./tasks";

export const isEngine = new URLSearchParams(location.search).get("view") === "cards";

export const supabase: SupabaseClient = createClient(SUPABASE_URL, SUPABASE_KEY, {
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

export function queued(): number {
  return readOutbox().length;
}

export async function write(table: string, row: Record<string, unknown>, onConflict = "id"): Promise<void> {
  writeOutbox([...readOutbox(), { table, row, onConflict }]);
  await flush();
}

/** Sends queued writes in order. Stops at the first failure so the order is kept. */
export async function flush(): Promise<boolean> {
  for (;;) {
    const items = readOutbox();
    const next = items[0];
    if (!next) return true;
    const { error } = await supabase.from(next.table).upsert([next.row], { onConflict: next.onConflict });
    if (error) return false;
    writeOutbox(readOutbox().slice(1));
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

export async function firstPlanAndBucket(): Promise<{ planId: string | null; bucketId: string | null }> {
  const { data: plans } = await supabase
    .from("plans")
    .select("id")
    .is("deleted_at", null)
    .eq("archived", false)
    .order("position", { ascending: true })
    .limit(1);
  const planId = (plans?.[0]?.id as string) ?? null;
  if (!planId) return { planId: null, bucketId: null };
  const { data: buckets } = await supabase
    .from("buckets")
    .select("id")
    .eq("plan_id", planId)
    .is("deleted_at", null)
    .order("position", { ascending: true })
    .limit(1);
  return { planId, bucketId: (buckets?.[0]?.id as string) ?? null };
}

export async function highestPosition(bucketId: string | null): Promise<number> {
  const { data } = await supabase.from("tasks").select("position").eq("bucket_id", bucketId ?? "").is("deleted_at", null);
  return Math.max(0, ...((data ?? []) as Array<{ position: number }>).map((r) => r.position ?? 0));
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

export async function ack(
  taskId: string,
  remindAt: number,
  action: string,
  device: string,
  snoozeUntil: number | null = null,
): Promise<void> {
  await write(
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
