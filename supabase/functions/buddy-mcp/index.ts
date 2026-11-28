// Buddy MCP: a READ-ONLY window on Buddy for AI assistants (Claude on web, phone, desktop, Claude Code…).
//
// Sign-in: Supabase Auth is the OAuth 2.1 server. The assistant discovers it from this function's
// protected-resource metadata, registers itself, and sends you to the Buddy consent page to approve.
// Every query then runs as you, under Row Level Security, so it only ever sees your own rows.
//
// Read only, twice over: this server has no tools that write, and the database refuses inserts,
// updates and deletes from any OAuth (AI connector) token (migration 20261128120000_mcp_read_only.sql).
//
// Left out on purpose: one-time codes, stored files (documents, hum audio), bank reference numbers,
// raw counterparty strings and account numbers.
//
// All dates are India time (the app is IST-only). Amounts are in rupees.

import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createMcpHandler, McpServer } from "npm:@modelcontextprotocol/server@2.4.0";
import { withOAuthProtectedResource, withSupabase } from "npm:@supabase/server@1.9.2";
import { z } from "npm:zod@4.6.7";
import type { SupabaseClient } from "npm:@supabase/supabase-js@2.119.0";

type Json = Record<string, unknown>;
type Db = SupabaseClient;

// ---------------------------------------------------------------- dates (IST)

const IST_MS = 5.5 * 3600_000;
const DAY_MS = 86_400_000;
/** Today in India, YYYY-MM-DD. */
const todayIst = () => new Date(Date.now() + IST_MS).toISOString().slice(0, 10);
const addDays = (iso: string, n: number) => new Date(Date.parse(`${iso}T00:00:00Z`) + n * DAY_MS).toISOString().slice(0, 10);
const startOf = (iso: string) => `${iso}T00:00:00+05:30`;
const endOf = (iso: string) => `${iso}T23:59:59.999+05:30`;
/** Timestamp → "YYYY-MM-DD HH:mm" in IST. */
function ist(ts: unknown): string | null {
  if (!ts) return null;
  const ms = typeof ts === "number" ? ts : Date.parse(String(ts));
  if (Number.isNaN(ms)) return null;
  return new Date(ms + IST_MS).toISOString().slice(0, 16).replace("T", " ");
}
const dateOf = (ts: unknown) => ist(ts)?.slice(0, 10) ?? null;

const isoDate = z.string().regex(/^\d{4}-\d{2}-\d{2}$/, "YYYY-MM-DD");
const rupees = (minor: unknown) => Math.round(Number(minor ?? 0)) / 100;

// ---------------------------------------------------------------- money helpers

/** Money in filed under a spending category is money back (₹900 dinner, ₹600 paid back → ₹300 spent). */
const INCOME_CATEGORIES = new Set(["salary", "income", "refunds", "transfer"]);
const isMoneyBack = (r: Json) =>
  r.direction === "credit" && !r.is_transfer && !INCOME_CATEGORIES.has(String(r.category ?? "").split("/")[0]);
/** What a transaction adds to spending: + payment, − money back, null if it isn't spending. */
function spendOf(r: Json): number | null {
  if (r.is_transfer) return null;
  if (r.direction === "debit") return Number(r.amount_minor);
  return isMoneyBack(r) ? -Number(r.amount_minor) : null;
}

/** Account name without card / account digits. */
function accountName(a: Json | undefined): string {
  if (!a) return "unknown account";
  const name = String(a.name ?? "").replace(/•+\s*\d+/g, "").replace(/\d{3,}/g, "").trim();
  return name || `${a.bank ?? ""} ${a.kind ?? "account"}`.trim();
}

async function accounts(db: Db): Promise<Map<string, Json>> {
  const { data, error } = await db.from("accounts").select("id,name,bank,kind,include_in_totals,archived,manual_balance_minor,manual_balance_at").is("deleted_at", null);
  if (error) throw new Error(error.message);
  return new Map((data ?? []).map((a) => [a.id as string, a as Json]));
}

interface TxnFilter {
  from: string;
  to: string;
  category?: string;
  direction?: "debit" | "credit";
  search?: string;
  account?: string;
  include_transfers?: boolean;
}

/** Transactions in a date range, newest first (pages through, up to [max]). */
async function transactions(db: Db, f: TxnFilter, max: number): Promise<Json[]> {
  const out: Json[] = [];
  for (let page = 0; out.length < max; page++) {
    const size = Math.min(1000, max - out.length);
    let q = db
      .from("transactions")
      .select("id,occurred_at,amount_minor,direction,category,payee,channel,note,account_id,is_transfer,balance_after_minor,source")
      .is("deleted_at", null)
      .gte("occurred_at", startOf(f.from))
      .lte("occurred_at", endOf(f.to))
      .order("occurred_at", { ascending: false })
      .range(page * 1000, page * 1000 + size - 1);
    if (!f.include_transfers) q = q.eq("is_transfer", false);
    if (f.category) {
      const cat = f.category.replace(/[^a-zA-Z0-9 _/-]/g, "");
      q = cat.includes("/") ? q.eq("category", cat) : q.or(`category.eq.${cat},category.like.${cat}/*`);
    }
    if (f.direction) q = q.eq("direction", f.direction);
    if (f.search) {
      const s = f.search.replace(/[%_,()]/g, "");
      q = q.or(`payee.ilike.%${s}%,note.ilike.%${s}%`);
    }
    const { data, error } = await q;
    if (error) throw new Error(error.message);
    out.push(...((data ?? []) as Json[]));
    if ((data ?? []).length < size) break;
  }
  return out;
}

function txnView(r: Json, accts: Map<string, Json>): Json {
  return {
    when: ist(r.occurred_at),
    amount: rupees(r.amount_minor),
    direction: r.direction === "debit" ? "out" : "in",
    category: r.category,
    payee: r.payee ?? r.channel ?? null,
    note: r.note ?? null,
    account: accountName(accts.get(r.account_id as string)),
    transfer: r.is_transfer || undefined,
    money_back: isMoneyBack(r) || undefined,
  };
}

/** Spending (payments minus money back) per key. */
function totalsBy(rows: Json[], key: (r: Json) => string): Array<{ key: string; spent: number; count: number }> {
  const g = new Map<string, { total: number; count: number }>();
  for (const r of rows) {
    const s = spendOf(r);
    if (s === null) continue;
    const k = key(r);
    const v = g.get(k) ?? { total: 0, count: 0 };
    v.total += s;
    v.count++;
    g.set(k, v);
  }
  return [...g.entries()].map(([k, v]) => ({ key: k, spent: rupees(v.total), count: v.count })).sort((a, b) => b.spent - a.spent);
}
const spentTotal = (rows: Json[]) => rupees(Math.max(0, rows.reduce((s, r) => s + (spendOf(r) ?? 0), 0)));
const incomeTotal = (rows: Json[]) =>
  rupees(rows.filter((r) => r.direction === "credit" && !r.is_transfer && !isMoneyBack(r)).reduce((s, r) => s + Number(r.amount_minor), 0));

/** The last balance each account's bank reported (or the balance you typed in, if newer). */
async function balances(db: Db, accts: Map<string, Json>): Promise<Json[]> {
  const out: Json[] = [];
  for (const [id, a] of accts) {
    if (a.archived) continue;
    const { data } = await db
      .from("transactions")
      .select("balance_after_minor,occurred_at")
      .eq("account_id", id)
      .is("deleted_at", null)
      .not("balance_after_minor", "is", null)
      .order("occurred_at", { ascending: false })
      .limit(1);
    const bank = data?.[0];
    const manualNewer = a.manual_balance_minor != null && (!bank || Date.parse(String(a.manual_balance_at)) > Date.parse(String(bank.occurred_at)));
    out.push({
      account: accountName(a),
      bank: a.bank ?? null,
      type: a.kind,
      counted_in_totals: a.include_in_totals,
      balance: manualNewer ? rupees(a.manual_balance_minor) : bank ? rupees(bank.balance_after_minor) : null,
      as_of: manualNewer ? ist(a.manual_balance_at) : bank ? ist(bank.occurred_at) : null,
      balance_source: manualNewer ? "typed in" : bank ? "bank SMS" : null,
    });
  }
  return out;
}

// ---------------------------------------------------------------- items (life & health)

async function items(db: Db, kind: string, opts: { from?: string; to?: string; limit?: number; search?: string } = {}): Promise<Json[]> {
  let q = db
    .from("items")
    .select("id,kind,data,occurred_on,ref_id,created_at,updated_at")
    .is("deleted_at", null)
    .eq("kind", kind)
    .order("occurred_on", { ascending: false, nullsFirst: false })
    .order("updated_at", { ascending: false })
    .limit(Math.min(opts.limit ?? 100, 1000));
  if (opts.from) q = q.gte("occurred_on", opts.from);
  if (opts.to) q = q.lte("occurred_on", opts.to);
  const { data, error } = await q;
  if (error) throw new Error(error.message);
  let rows = (data ?? []) as Json[];
  if (opts.search) {
    const s = opts.search.toLowerCase();
    rows = rows.filter((r) => JSON.stringify(r.data).toLowerCase().includes(s));
  }
  return rows;
}

/** Item data, minus file paths and audio blobs. Amounts (…Minor) become rupees. */
function itemView(r: Json, extra: Json = {}): Json {
  const d = { ...((r.data as Json) ?? {}) };
  delete d.storagePath;
  delete d.audio;
  delete d.hourly;
  for (const k of Object.keys(d)) {
    if (k.endsWith("Minor") && typeof d[k] === "number") {
      d[k.slice(0, -5)] = rupees(d[k]);
      delete d[k];
    }
    if (k.endsWith("Ms") && k !== "hrvMs" && k !== "audioOffsetMs" && typeof d[k] === "number" && Number(d[k]) > 1e12) {
      d[k.slice(0, -2)] = ist(d[k]);
      delete d[k];
    }
  }
  return { date: r.occurred_on ?? null, ...d, ...extra, updated: ist(r.updated_at) };
}

const HEALTH = {
  steps: "steps_day",
  sleep: "sleep",
  heart: "heart_day",
  workouts: "workout",
  weight: "weight",
  water: "water",
  body_profile: "body_profile",
  training_plans: "training_plan",
  routines: "routine",
  health_reports: "health_report",
} as const;

const LIFE = [
  "note", "journal", "habit", "habit_log", "goal", "reading", "wish", "person", "vehicle", "vehicle_log",
  "insight", "document", "hum", "subcategory", "budget", "recurring", "holding", "money_back",
] as const;

// ---------------------------------------------------------------- tasks

async function planNames(db: Db): Promise<Map<string, string>> {
  const { data } = await db.from("plans").select("id,name").is("deleted_at", null);
  return new Map((data ?? []).map((p) => [p.id as string, p.name as string]));
}

function taskView(t: Json, plans: Map<string, string>, buckets?: Map<string, string>): Json {
  const list = (t.checklist as Array<{ title?: string; text?: string; done: boolean }> | null) ?? [];
  return {
    title: t.title,
    list: plans.get(t.plan_id as string) ?? null,
    column: buckets?.get(t.bucket_id as string) ?? undefined,
    status: t.status,
    priority: t.priority ?? null,
    due: t.due_at ? (t.due_all_day === false ? ist(t.due_at) : dateOf(t.due_at)) : null,
    reminder: ist(t.remind_at),
    repeats: t.repeat ?? null,
    my_day: t.my_day ?? null,
    notes: t.notes ?? null,
    checklist: list.length ? list.map((i) => `${i.done ? "[x]" : "[ ]"} ${i.title ?? i.text ?? ""}`) : undefined,
    created: dateOf(t.created_at),
    completed: ist(t.completed_at),
  };
}

const TASK_COLS = "id,title,notes,status,priority,due_at,due_all_day,remind_at,repeat,my_day,plan_id,bucket_id,checklist,created_at,completed_at";

// ---------------------------------------------------------------- overview

async function overview(db: Db, day: string): Promise<Json> {
  const monthStart = `${day.slice(0, 8)}01`;
  const lastMonthStart = addDays(monthStart, -1).slice(0, 8) + "01";
  const sameDayLastMonth = (() => {
    const d = Number(day.slice(8, 10));
    const last = addDays(monthStart, -1);
    return `${last.slice(0, 8)}${String(Math.min(d, Number(last.slice(8, 10)))).padStart(2, "0")}`;
  })();

  const [accts, thisMonth, lastMonth, plans] = await Promise.all([
    accounts(db),
    transactions(db, { from: monthStart, to: day }, 5000),
    transactions(db, { from: lastMonthStart, to: sameDayLastMonth }, 5000),
    planNames(db),
  ]);
  const todays = thisMonth.filter((r) => dateOf(r.occurred_at) === day);

  // Budgets against this month's spending.
  const byCategory = totalsBy(thisMonth, (r) => String(r.category ?? "other").split("/")[0]);
  const budgetRows = await items(db, "budget");
  const budgetStatus = budgetRows.map((b) => {
    const d = b.data as Json;
    const cat = String(d.category ?? "");
    const spent = byCategory.find((c) => c.key === cat)?.spent ?? 0;
    const limit = rupees(d.limitMinor);
    return { category: cat, limit, spent, left: Math.round((limit - spent) * 100) / 100 };
  });

  // Tasks.
  const { data: open } = await db.from("tasks").select(TASK_COLS).is("deleted_at", null).neq("status", "done").limit(1000);
  const { data: doneToday } = await db.from("tasks").select("title,plan_id,completed_at").is("deleted_at", null)
    .gte("completed_at", startOf(day)).lte("completed_at", endOf(day));
  const openTasks = (open ?? []) as Json[];
  const dueDate = (t: Json) => (t.due_at ? dateOf(t.due_at) : null);
  const overdue = openTasks.filter((t) => dueDate(t) !== null && dueDate(t)! < day);
  const dueToday = openTasks.filter((t) => dueDate(t) === day || t.my_day === day);
  const remindToday = openTasks.filter((t) => dateOf(t.remind_at) === day);
  const openPerList: Record<string, number> = {};
  for (const t of openTasks) {
    const n = plans.get(t.plan_id as string) ?? "No list";
    openPerList[n] = (openPerList[n] ?? 0) + 1;
  }
  const short = (t: Json) => ({ title: t.title, list: plans.get(t.plan_id as string) ?? null, due: dueDate(t), reminder: ist(t.remind_at) });

  // Health.
  const weekAgo = addDays(day, -6);
  const [steps, sleep, heart, workouts, weight, habits, habitLogs, people, vehicles] = await Promise.all([
    items(db, "steps_day", { from: weekAgo, to: day }),
    items(db, "sleep", { limit: 3 }),
    items(db, "heart_day", { limit: 1 }),
    items(db, "workout", { from: addDays(day, -13), to: day }),
    items(db, "weight", { limit: 1 }),
    items(db, "habit"),
    items(db, "habit_log", { from: day, to: day }),
    items(db, "person"),
    items(db, "vehicle"),
  ]);
  const stepsToday = steps.find((s) => s.occurred_on === day);
  const stepsAvg = steps.length ? Math.round(steps.reduce((s, r) => s + Number((r.data as Json).steps ?? 0), 0) / steps.length) : null;
  const lastSleep = sleep[0] ? (sleep[0].data as Json) : null;

  // Coming up in the next 14 days: birthdays and vehicle papers.
  const soon: Json[] = [];
  const horizon = addDays(day, 14);
  for (const p of people) {
    const b = String((p.data as Json).birthday ?? "");
    if (!/^\d{4}-\d{2}-\d{2}$|^--\d{2}-\d{2}$|^\d{2}-\d{2}$/.test(b)) continue;
    const md = b.slice(-5);
    for (const y of [day.slice(0, 4), String(Number(day.slice(0, 4)) + 1)]) {
      const d = `${y}-${md}`;
      if (d >= day && d <= horizon) soon.push({ what: `Birthday: ${(p.data as Json).name}`, date: d });
    }
  }
  for (const v of vehicles) {
    const d = v.data as Json;
    for (const [k, label] of [["insuranceExpiry", "insurance expires"], ["pucExpiry", "PUC expires"], ["serviceDue", "service due"]] as const) {
      const when = String(d[k] ?? "").slice(0, 10);
      if (when && when <= horizon) soon.push({ what: `${d.name ?? "Vehicle"} ${label}`, date: when, overdue: when < day || undefined });
    }
  }
  soon.sort((a, b) => String(a.date).localeCompare(String(b.date)));

  const habitDone = new Set(habitLogs.map((l) => l.ref_id as string));

  return {
    date: day,
    money: {
      spent_today: spentTotal(todays),
      spent_this_month: spentTotal(thisMonth),
      spent_same_point_last_month: spentTotal(lastMonth),
      income_this_month: incomeTotal(thisMonth),
      top_categories_this_month: byCategory.slice(0, 6).map((c) => ({ category: c.key, spent: c.spent })),
      budgets: budgetStatus,
      balances: await balances(db, accts),
    },
    tasks: {
      open_per_list: openPerList,
      overdue: overdue.slice(0, 25).map(short),
      due_today_or_my_day: dueToday.slice(0, 25).map(short),
      reminders_today: remindToday.map(short),
      done_today: (doneToday ?? []).map((t) => ({ title: t.title, list: plans.get(t.plan_id as string) ?? null, at: ist(t.completed_at) })),
    },
    health: {
      steps_today: stepsToday ? (stepsToday.data as Json).steps : null,
      steps_7_day_average: stepsAvg,
      last_sleep: lastSleep ? { night_of: sleep[0].occurred_on, minutes: lastSleep.minutes, deep: lastSleep.deepMin, rem: lastSleep.remMin } : null,
      resting_heart_rate: heart[0] ? (heart[0].data as Json).restingBpm ?? null : null,
      workouts_last_14_days: workouts.map((w) => ({ date: w.occurred_on, title: (w.data as Json).title ?? (w.data as Json).type, minutes: (w.data as Json).durationMin })),
      latest_weight_kg: weight[0] ? { kg: (weight[0].data as Json).kg, date: weight[0].occurred_on } : null,
    },
    habits_today: habits
      .filter((h) => !(h.data as Json).archived)
      .map((h) => ({ habit: (h.data as Json).name, done: habitDone.has(h.id as string) })),
    coming_up: soon,
  };
}

// ---------------------------------------------------------------- the server

const text = (value: unknown) => ({ content: [{ type: "text" as const, text: JSON.stringify(value, null, 1) }] });
const READ = { readOnlyHint: true, openWorldHint: false } as const;

function buildServer(db: Db): McpServer {
  const server = new McpServer(
    { name: "buddy", version: "1.0.0" },
    {
      instructions:
        "Buddy is the user's personal life app (India, rupees, IST). Everything here is read-only. " +
        "Start with `overview` for a snapshot of today; use the other tools to dig into money, tasks, health and life. " +
        "Spending means payments minus money paid back to the user, excluding transfers between their own accounts.",
    },
  );

  server.registerTool(
    "overview",
    {
      title: "Today at a glance",
      description:
        "A snapshot for a day (default today, IST): money spent today and this month vs the same point last month, top categories, " +
        "budgets, account balances; overdue / due / reminder tasks and what was done; steps, sleep, heart rate, workouts, weight; " +
        "habits; birthdays and vehicle papers coming up in 14 days.",
      inputSchema: z.object({ date: isoDate.optional().describe("YYYY-MM-DD, default today") }),
      annotations: READ,
    },
    async ({ date }) => text(await overview(db, date ?? todayIst())),
  );

  server.registerTool(
    "transactions",
    {
      title: "Transactions",
      description:
        "Bank, card, UPI and cash transactions in a date range, newest first. Transfers between the user's own accounts are left out unless include_transfers.",
      inputSchema: z.object({
        from: isoDate,
        to: isoDate,
        category: z.string().optional().describe("Category, e.g. food, fuel, or a sub-category like food/Coffee"),
        direction: z.enum(["out", "in"]).optional(),
        search: z.string().optional().describe("Text in the payee or note"),
        account: z.string().optional().describe("Part of the account or bank name"),
        include_transfers: z.boolean().optional(),
        limit: z.number().int().min(1).max(1000).optional().describe("Default 200"),
      }),
      annotations: READ,
    },
    async (a) => {
      const accts = await accounts(db);
      let rows = await transactions(db, { ...a, direction: a.direction === "out" ? "debit" : a.direction === "in" ? "credit" : undefined }, a.limit ?? 200);
      if (a.account) {
        const s = a.account.toLowerCase();
        rows = rows.filter((r) => `${accountName(accts.get(r.account_id as string))} ${accts.get(r.account_id as string)?.bank ?? ""}`.toLowerCase().includes(s));
      }
      return text({ count: rows.length, transactions: rows.map((r) => txnView(r, accts)) });
    },
  );

  server.registerTool(
    "spending",
    {
      title: "Spending totals",
      description:
        "Totals for a date range grouped by category, sub-category, month, week, day, account or payee. Spending = payments minus money back, " +
        "transfers excluded. Also returns income for the range.",
      inputSchema: z.object({
        from: isoDate,
        to: isoDate,
        group_by: z.enum(["category", "subcategory", "month", "week", "day", "account", "payee"]),
        category: z.string().optional().describe("Only this category"),
      }),
      annotations: READ,
    },
    async ({ from, to, group_by, category }) => {
      const accts = await accounts(db);
      const rows = await transactions(db, { from, to, category }, 20000);
      const key = (r: Json): string => {
        const d = dateOf(r.occurred_at)!;
        switch (group_by) {
          case "category": return String(r.category ?? "other").split("/")[0];
          case "subcategory": return String(r.category ?? "other");
          case "month": return d.slice(0, 7);
          case "day": return d;
          case "week": {
            const dow = (new Date(`${d}T00:00:00Z`).getUTCDay() + 6) % 7; // Monday first
            return `week of ${addDays(d, -dow)}`;
          }
          case "account": return accountName(accts.get(r.account_id as string));
          case "payee": return String(r.payee ?? r.channel ?? "unknown");
        }
      };
      const groups = totalsBy(rows, key);
      if (["month", "week", "day"].includes(group_by)) groups.sort((a, b) => a.key.localeCompare(b.key));
      return text({ from, to, spent: spentTotal(rows), income: incomeTotal(rows), groups: groups.slice(0, 200) });
    },
  );

  server.registerTool(
    "accounts",
    {
      title: "Accounts and balances",
      description: "Accounts with their latest known balance (from bank SMS, or typed in by the user if newer).",
      inputSchema: z.object({}),
      annotations: READ,
    },
    async () => text({ accounts: await balances(db, await accounts(db)) }),
  );

  server.registerTool(
    "money_plans",
    {
      title: "Budgets, recurring payments, investments, wishlist",
      description:
        "Monthly budgets with this month's spending, recurring payments/income (rent, SIPs, subscriptions), investment holdings " +
        "(invested vs current value) and the wishlist.",
      inputSchema: z.object({}),
      annotations: READ,
    },
    async () => {
      const day = todayIst();
      const [budgets, recurring, holdings, wishes, month] = await Promise.all([
        items(db, "budget"), items(db, "recurring"), items(db, "holding"), items(db, "wish"),
        transactions(db, { from: `${day.slice(0, 8)}01`, to: day }, 5000),
      ]);
      const byCat = totalsBy(month, (r) => String(r.category ?? "other").split("/")[0]);
      const hv = holdings.map((h) => itemView(h));
      const invested = holdings.reduce((s, h) => s + Number((h.data as Json).investedMinor ?? 0), 0);
      const current = holdings.reduce((s, h) => s + Number((h.data as Json).currentMinor ?? 0), 0);
      return text({
        budgets: budgets.map((b) => {
          const v = itemView(b);
          return { ...v, spent_this_month: byCat.find((c) => c.key === (b.data as Json).category)?.spent ?? 0 };
        }),
        recurring: recurring.map((r) => itemView(r)),
        investments: { invested: rupees(invested), current_value: rupees(current), holdings: hv },
        wishlist: wishes.map((w) => itemView(w)),
      });
    },
  );

  server.registerTool(
    "tasks",
    {
      title: "Tasks and reminders",
      description:
        "Planner tasks with list, column, status, priority, due date, reminder, repeat, notes and checklist. " +
        "Filter by status, list name, text, due-date range or completion-date range.",
      inputSchema: z.object({
        status: z.enum(["open", "done", "all"]).optional().describe("Default open"),
        list: z.string().optional().describe("List name, e.g. Work"),
        search: z.string().optional().describe("Text in the title or notes"),
        due_from: isoDate.optional(),
        due_to: isoDate.optional(),
        completed_from: isoDate.optional(),
        completed_to: isoDate.optional(),
        limit: z.number().int().min(1).max(1000).optional().describe("Default 200"),
      }),
      annotations: READ,
    },
    async (a) => {
      const plans = await planNames(db);
      const { data: bs } = await db.from("buckets").select("id,name").is("deleted_at", null);
      const buckets = new Map((bs ?? []).map((b) => [b.id as string, b.name as string]));
      let q = db.from("tasks").select(TASK_COLS).is("deleted_at", null).order("created_at", { ascending: false }).limit(a.limit ?? 200);
      const status = a.status ?? "open";
      if (status === "open") q = q.neq("status", "done");
      if (status === "done") q = q.eq("status", "done");
      if (a.list) {
        const ids = [...plans].filter(([, n]) => n.toLowerCase().includes(a.list!.toLowerCase())).map(([id]) => id);
        if (!ids.length) return text({ count: 0, tasks: [], note: `No list matches "${a.list}". Lists: ${[...plans.values()].join(", ")}` });
        q = q.in("plan_id", ids);
      }
      if (a.search) {
        const s = a.search.replace(/[%_,()]/g, "");
        q = q.or(`title.ilike.%${s}%,notes.ilike.%${s}%`);
      }
      if (a.due_from) q = q.gte("due_at", startOf(a.due_from));
      if (a.due_to) q = q.lte("due_at", endOf(a.due_to));
      if (a.completed_from) q = q.gte("completed_at", startOf(a.completed_from));
      if (a.completed_to) q = q.lte("completed_at", endOf(a.completed_to));
      const { data, error } = await q;
      if (error) throw new Error(error.message);
      return text({ count: data?.length ?? 0, tasks: (data ?? []).map((t) => taskView(t as Json, plans, buckets)) });
    },
  );

  server.registerTool(
    "task_lists",
    {
      title: "Task lists",
      description: "The Planner lists and their columns, with how many tasks are open and done in each.",
      inputSchema: z.object({}),
      annotations: READ,
    },
    async () => {
      const [{ data: plans }, { data: bs }, { data: ts }] = await Promise.all([
        db.from("plans").select("id,name,archived,position").is("deleted_at", null).order("position"),
        db.from("buckets").select("id,name,plan_id,position").is("deleted_at", null).order("position"),
        db.from("tasks").select("plan_id,bucket_id,status").is("deleted_at", null).limit(10000),
      ]);
      return text({
        lists: (plans ?? []).map((p) => ({
          list: p.name,
          archived: p.archived || undefined,
          open: (ts ?? []).filter((t) => t.plan_id === p.id && t.status !== "done").length,
          done: (ts ?? []).filter((t) => t.plan_id === p.id && t.status === "done").length,
          columns: (bs ?? []).filter((b) => b.plan_id === p.id).map((b) => ({
            column: b.name,
            open: (ts ?? []).filter((t) => t.bucket_id === b.id && t.status !== "done").length,
          })),
        })),
      });
    },
  );

  server.registerTool(
    "reminder_history",
    {
      title: "Reminder answers",
      description: "How reminders were answered (done, snoozed, moved, dismissed), on which device, in a date range.",
      inputSchema: z.object({ from: isoDate, to: isoDate }),
      annotations: READ,
    },
    async ({ from, to }) => {
      const { data, error } = await db.from("reminder_acks").select("task_id,remind_at,action,snooze_until,device,acked_at")
        .gte("acked_at", startOf(from)).lte("acked_at", endOf(to)).order("acked_at", { ascending: false }).limit(1000);
      if (error) throw new Error(error.message);
      const ids = [...new Set((data ?? []).map((r) => r.task_id as string))];
      const { data: ts } = ids.length ? await db.from("tasks").select("id,title").in("id", ids) : { data: [] };
      const titles = new Map((ts ?? []).map((t) => [t.id as string, t.title as string]));
      return text({
        answers: (data ?? []).map((r) => ({
          task: titles.get(r.task_id as string) ?? "(deleted task)",
          reminder_was: ist(r.remind_at),
          answer: r.action,
          snoozed_until: ist(r.snooze_until),
          device: String(r.device ?? "").startsWith("laptop") ? "laptop" : r.device,
          at: ist(r.acked_at),
        })),
      });
    },
  );

  server.registerTool(
    "health",
    {
      title: "Health and fitness",
      description:
        "Health data by metric: steps (per day), sleep (per night, stages in minutes), heart (resting/avg/HRV/SpO2 per day), workouts " +
        "(strength sets, runs with distance, walks), weight, water, body_profile (height, goal weight), training_plans, routines, " +
        "health_reports (AI weekly reports).",
      inputSchema: z.object({
        metric: z.enum(Object.keys(HEALTH) as [keyof typeof HEALTH, ...Array<keyof typeof HEALTH>]),
        from: isoDate.optional(),
        to: isoDate.optional(),
        limit: z.number().int().min(1).max(1000).optional().describe("Default 120"),
      }),
      annotations: READ,
    },
    async ({ metric, from, to, limit }) => {
      const rows = await items(db, HEALTH[metric], { from, to, limit: limit ?? 120 });
      return text({ metric, count: rows.length, entries: rows.map((r) => itemView(r)) });
    },
  );

  server.registerTool(
    "life",
    {
      title: "Life records",
      description:
        "Everything else Buddy keeps: note, journal (mood, energy, gratitude, text), habit and habit_log (daily check-ins), goal, " +
        "reading (books/courses), wish, person (birthdays, keep-in-touch), vehicle and vehicle_log (fuel, service), insight (AI " +
        "observations), document (titles and expiry dates only), hum (music ideas, no audio), subcategory, budget, recurring, holding, money_back.",
      inputSchema: z.object({
        kind: z.enum(LIFE),
        from: isoDate.optional(),
        to: isoDate.optional(),
        search: z.string().optional(),
        limit: z.number().int().min(1).max(1000).optional().describe("Default 100"),
      }),
      annotations: READ,
    },
    async ({ kind, from, to, search, limit }) => {
      const rows = await items(db, kind, { from, to, search, limit: limit ?? 100 });
      let extra = (_r: Json): Json => ({});
      if (kind === "habit_log") {
        const habits = await items(db, "habit");
        const names = new Map(habits.map((h) => [h.id as string, (h.data as Json).name]));
        extra = (r) => ({ habit: names.get(r.ref_id as string) ?? null });
      }
      return text({ kind, count: rows.length, entries: rows.map((r) => itemView(r, extra(r))) });
    },
  );

  server.registerTool(
    "search",
    {
      title: "Search everything",
      description: "Finds text across task titles and notes, transaction payees and notes, and all life records (notes, journal, people…).",
      inputSchema: z.object({ text: z.string().min(2), limit: z.number().int().min(1).max(200).optional().describe("Per kind, default 30") }),
      annotations: READ,
    },
    async ({ text: needle, limit }) => {
      const n = limit ?? 30;
      const s = needle.replace(/[%_,()]/g, "");
      const plans = await planNames(db);
      const accts = await accounts(db);
      const [{ data: ts }, { data: tx }, { data: its }] = await Promise.all([
        db.from("tasks").select(TASK_COLS).is("deleted_at", null).or(`title.ilike.%${s}%,notes.ilike.%${s}%`).order("created_at", { ascending: false }).limit(n),
        db.from("transactions").select("id,occurred_at,amount_minor,direction,category,payee,channel,note,account_id,is_transfer").is("deleted_at", null)
          .or(`payee.ilike.%${s}%,note.ilike.%${s}%`).order("occurred_at", { ascending: false }).limit(n),
        db.from("items").select("id,kind,data,occurred_on,ref_id,created_at,updated_at").is("deleted_at", null)
          .in("kind", [...LIFE, ...Object.values(HEALTH)]).order("updated_at", { ascending: false }).limit(2000),
      ]);
      const low = needle.toLowerCase();
      const hits = ((its ?? []) as Json[]).filter((r) => JSON.stringify(itemView(r)).toLowerCase().includes(low)).slice(0, n);
      return text({
        tasks: (ts ?? []).map((t) => taskView(t as Json, plans)),
        transactions: (tx ?? []).map((r) => txnView(r as Json, accts)),
        records: hits.map((r) => ({ kind: r.kind, ...itemView(r) })),
      });
    },
  );

  return server;
}

Deno.serve(
  // Outside: OAuth discovery for MCP clients (answers before sign-in). Inside: verify the user's token and
  // give the tools a client scoped to them.
  withOAuthProtectedResource(
    withSupabase({ auth: "user" }, async (req, { supabase }) => {
      const handler = createMcpHandler(() => buildServer(supabase as unknown as Db));
      return handler.fetch(req);
    }),
  ),
);
