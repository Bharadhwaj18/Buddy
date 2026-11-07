// Buddy AI — Gemini proxy with read-only tools over the signed-in user's data (DESIGN.md §10).
//
// Modes:
//   { mode: "chat", messages: [{ role: "user"|"model", text }], today, tz }   -> { text, tools: [...] }
//   { mode: "command", text, today, accounts: [names], categories: [slugs] } -> { actions: [...], speech }
//   { mode: "models" }                                                      -> available Gemini models
//
// Privacy (free-tier key: Google may use prompts to improve products):
//   - Gemini only sees dates, amounts, categories, merchant names, account *types/banks* and task titles.
//   - Never: account numbers/last4, bank reference numbers, raw UPI IDs, notes, or names of people paid.
//   - Queries run with the caller's JWT, so Postgres RLS limits them to the caller's own rows.

import { createClient, SupabaseClient } from "jsr:@supabase/supabase-js@2";

const GEMINI_KEY = Deno.env.get("GEMINI_API_KEY") ?? "";
/** Who Buddy works for, e.g. "Shrey" (Edge Function secret OWNER_NAME). Kept out of the code. */
const OWNER = Deno.env.get("OWNER_NAME")?.trim() || "the user";
const OWNER_POSS = OWNER === "the user" ? "the user's" : `${OWNER}'s`;
// Optional override (supabase secrets set GEMINI_MODEL=...). Otherwise the newest Flash model
// this key can use is discovered at runtime, so model retirements don't break the app.
const MODEL_OVERRIDE = Deno.env.get("GEMINI_MODEL") ?? "";
const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SUPABASE_ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY")!;
const GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta";

type Json = Record<string, unknown>;

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "POST only" }, 405);
  if (!GEMINI_KEY) return json({ error: "GEMINI_API_KEY secret is not set" }, 500);

  const authHeader = req.headers.get("Authorization");
  if (!authHeader) return json({ error: "Not signed in" }, 401);
  const db = createClient(SUPABASE_URL, SUPABASE_ANON_KEY, {
    global: { headers: { Authorization: authHeader } },
    auth: { persistSession: false },
  });
  const { data: userData, error: userError } = await db.auth.getUser(authHeader.replace(/^Bearer\s+/i, ""));
  if (userError || !userData?.user) return json({ error: "Not signed in" }, 401);

  let body: Json;
  try {
    body = await req.json();
  } catch {
    return json({ error: "Invalid JSON" }, 400);
  }

  try {
    switch (body.mode) {
      case "chat":
        return json(await chat(db, body));
      case "chat_stream":
        return chatStream(db, body);
      case "command":
        return json(await command(body));
      case "models":
        return json(await listModels());
      case "vision":
        return json(await vision(body));
      case "meal_text":
        return json(await mealText(body));
      case "briefing":
        return json(await briefing(db, body));
      case "training_plan":
        return json(await trainingPlan(body));
      case "health_report":
        return json(await healthReport(body));
      default:
        return json({ error: `Unknown mode ${body.mode}` }, 400);
    }
  } catch (e) {
    console.error(e);
    return json({ error: e instanceof Error ? e.message : String(e) }, 502);
  }
});

// ---------------------------------------------------------------- Gemini

/**
 * "chat" andtype Kind = "chat" | "command" | "deep" | "plan";(Ask Buddy, voice, receipts) run on the fast tier: Flash-Lite with thinking off —
 * a few seconds, and the free tier allows more of them per minute. "deep" (training plans, weekly
 * reports) runs on full Flash with light thinking.
 */
type Kind = "chat" | "command" | "deep" | "plan";

let activeModel: Record<"fast" | "deep", string> = { fast: MODEL_OVERRIDE, deep: MODEL_OVERRIDE }; // per warm instance
const unavailable = new Set<string>(); // models this key was refused (404)

async function availableModels(): Promise<string[]> {
  const res = await fetch(`${GEMINI_BASE}/models?pageSize=500`, {
    headers: { "x-goog-api-key": GEMINI_KEY },
    signal: AbortSignal.timeout(5_000),
  });
  const data = await res.json();
  if (!res.ok) throw new Error(`Gemini ${res.status}: ${data?.error?.message ?? "could not list models"}`);
  return (data.models ?? [])
    .filter((m: Json) => (m.supportedGenerationMethods as string[] | undefined)?.includes("generateContent"))
    .map((m: Json) => (m.name as string).replace("models/", ""));
}

let candidateCache: { at: number; models: string[] } | null = null;
const tierOf = (kind: Kind) => (kind === "deep" || kind === "plan" ? "deep" : "fast");

/** Models to try, best first, for a tier. Fast = Flash-Lite family first; deep = full Flash first. */
async function candidates(tier: "fast" | "deep"): Promise<string[]> {
  if (MODEL_OVERRIDE) return [MODEL_OVERRIDE];
  if (!candidateCache || Date.now() - candidateCache.at > 6 * 60 * 60 * 1000) {
    candidateCache = { at: Date.now(), models: await availableModels() };
  }
  const models = candidateCache.models;
  const rank = (lite: boolean) =>
    models
      .map((name) => ({ name, m: new RegExp(`^gemini-(\\d+(?:\\.\\d+)?)-flash${lite ? "-lite" : ""}(-preview)?(?:-[\\d-]+)?$`).exec(name) }))
      .filter((x) => x.m)
      .map((x) => ({ name: x.name, version: parseFloat(x.m![1]), preview: Boolean(x.m![2]) }))
      .sort((a, b) => Number(a.preview) - Number(b.preview) || b.version - a.version)
      .map((x) => x.name);
  const lite = [...(models.includes("gemini-flash-lite-latest") ? ["gemini-flash-lite-latest"] : []), ...rank(true)];
  const flash = [...(models.includes("gemini-flash-latest") ? ["gemini-flash-latest"] : []), ...rank(false)];
  const order = tier === "fast" ? [...lite, ...flash] : [...flash, ...lite];
  const unique = [...new Set(order)].filter((m) => !unavailable.has(m));
  if (unique.length === 0) throw new Error(`No usable Gemini Flash model for this key (saw: ${models.slice(0, 15).join(", ")})`);
  return unique;
}

/** Thinking off for fast work, light for deep work. 1.x/2.x take thinkingBudget; 3+ take thinkingLevel. */
function tune(model: string, kind: Kind, config: Json): Json {
  const fast = tierOf(kind) === "fast";
  if (/^gemini-(1|2)\./.test(model)) {
    return { ...config, temperature: kind === "command" ? 0 : 0.3, thinkingConfig: { thinkingBudget: fast ? 0 : 512 } };
  }
  return { ...config, thinkingConfig: { thinkingLevel: fast ? "minimal" : "low" } };
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
const BUSY = new Set([408, 429, 500, 503, 504]);

/** Per-call limits: one slow model must never eat the whole request. */
// deep: two models get a go inside the budget. plan: long, detailed JSON — the app waits up to 2.5 min for these.
const CALL_MS: Record<Kind, number> = { chat: 12_000, command: 12_000, deep: 27_000, plan: 85_000 };
/** Whole-request budgets — comfortably inside the app's 60 s timeout. */
const BUDGET_MS: Record<Kind, number> = { chat: 25_000, command: 20_000, deep: 55_000, plan: 125_000 };

async function orderFor(kind: Kind): Promise<string[]> {
  const tier = tierOf(kind);
  const order = await candidates(tier);
  const last = activeModel[tier];
  if (last && order.includes(last)) {
    order.splice(order.indexOf(last), 1);
    order.unshift(last); // whichever worked last on this warm instance
  }
  return order.slice(0, 4);
}

/**
 * Calls Gemini with fallbacks, inside a time budget ([deadline] = epoch ms):
 *  - each call is cut off after CALL_MS (or when the budget runs out) and the next model is tried;
 *  - 404 (retired / not offered to this key): remember, move on;
 *  - 429/5xx/timeout: move straight to the next model (one short retry only if time allows);
 *  - 400 about thinking settings: retry once without them.
 */
async function gemini(kind: Kind, payload: Json, config: Json = {}, deadline = Date.now() + BUDGET_MS[kind]): Promise<Json> {
  let lastError = "no model available";
  for (const model of await orderFor(kind)) {
    for (let attempt = 0; attempt < 2; attempt++) {
      const left = deadline - Date.now();
      if (left < 1_500) throw new Error(`Gemini is busy right now (${lastError}). Try again in a minute.`);
      let generationConfig = tune(model, kind, config);
      let res = await post(model, { ...payload, generationConfig }, Math.min(CALL_MS[kind], left));
      if (res.status === 400 && /thinking/i.test(res.message)) {
        const { thinkingConfig: _ignored, ...rest } = generationConfig;
        generationConfig = rest;
        res = await post(model, { ...payload, generationConfig }, Math.min(CALL_MS[kind], deadline - Date.now()));
      }
      if (res.status === 200) {
        activeModel[tierOf(kind)] = model;
        return { ...res.data, _model: model };
      }
      lastError = `${res.status} ${model}: ${res.message}`;
      if (res.status === 404) {
        unavailable.add(model);
        break;
      }
      if (BUSY.has(res.status)) {
        // A quick second try only for a brief overload, and only with plenty of time left.
        if (attempt === 0 && res.status === 503 && deadline - Date.now() > 10_000) { await sleep(600); continue; }
        break;
      }
      throw new Error(`Gemini ${res.status} (${model}): ${res.message}`);
    }
  }
  throw new Error(`Gemini is busy right now (${lastError}). Try again in a minute.`);
}

async function post(model: string, body: Json, timeoutMs: number): Promise<{ status: number; data: Json; message: string }> {
  try {
    const res = await fetch(`${GEMINI_BASE}/models/${model}:generateContent`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-goog-api-key": GEMINI_KEY },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(Math.max(timeoutMs, 1_000)),
    });
    const data = await res.json().catch(() => ({}));
    return { status: res.status, data, message: data?.error?.message ?? res.statusText };
  } catch (e) {
    // Timed out / network hiccup: treat like "busy" so the caller tries the next model.
    return { status: 408, data: {}, message: e instanceof Error ? e.name : "timeout" };
  }
}

async function listModels(): Promise<Json> {
  return { fast: await orderFor("chat"), deep: await orderFor("deep"), override: MODEL_OVERRIDE || null, active: activeModel };
}

// ---------------------------------------------------------------- chat

const toolDeclarations = [
  {
    name: "query_transactions",
    description:
      "List the user's bank transactions in a date range (newest first). Amounts are in rupees. " +
      "Transfers between the user's own accounts are excluded unless include_transfers is true.",
    parameters: {
      type: "OBJECT",
      properties: {
        from: { type: "STRING", description: "Start date, YYYY-MM-DD (inclusive)" },
        to: { type: "STRING", description: "End date, YYYY-MM-DD (inclusive)" },
        category: { type: "STRING", description: "Optional category slug filter" },
        direction: { type: "STRING", enum: ["debit", "credit"], description: "debit = money out, credit = money in" },
        search: { type: "STRING", description: "Optional case-insensitive merchant/payee search" },
        include_transfers: { type: "BOOLEAN" },
        limit: { type: "INTEGER", description: "Max rows, default 50, max 200" },
      },
      required: ["from", "to"],
    },
  },
  {
    name: "spending_summary",
    description:
      "Totals for a date range grouped by category, month, account or merchant. Use this for 'how much' questions. " +
      "Excludes transfers unless include_transfers is true.",
    parameters: {
      type: "OBJECT",
      properties: {
        from: { type: "STRING", description: "YYYY-MM-DD" },
        to: { type: "STRING", description: "YYYY-MM-DD" },
        group_by: { type: "STRING", enum: ["category", "month", "account", "merchant"] },
        direction: { type: "STRING", enum: ["debit", "credit"], description: "Default debit (spending)" },
        include_transfers: { type: "BOOLEAN" },
      },
      required: ["from", "to", "group_by"],
    },
  },
  {
    name: "list_accounts",
    description: "The user's accounts (bank and type) with the last balance the bank reported, if any.",
    parameters: { type: "OBJECT", properties: {} },
  },
  {
    name: "query_items",
    description:
      "The user's personal tracking data. kinds: habit (definitions), habit_log (daily check-ins), workout " +
      "(strength/run/walk… with duration, distance for runs), meal (kcal/protein), goal (target/current), " +
      "reading (books/courses), wish (wishlist with prices), holding (investments: invested/current value), " +
      "journal (mood and energy 1-5 only), vehicle (expiry dates). Optional date range applies to dated kinds.",
    parameters: {
      type: "OBJECT",
      properties: {
        kind: { type: "STRING", enum: ["habit", "habit_log", "workout", "meal", "goal", "reading", "wish", "holding", "journal", "vehicle"] },
        from: { type: "STRING", description: "YYYY-MM-DD" },
        to: { type: "STRING", description: "YYYY-MM-DD" },
        limit: { type: "INTEGER" },
      },
      required: ["kind"],
    },
  },
  {
    name: "list_tasks",
    description:
      "The user's Planner tasks with progress (todo = not started, in_progress, done), priority, due date, " +
      "My Day date, plan and checklist progress.",
    parameters: {
      type: "OBJECT",
      properties: { status: { type: "STRING", enum: ["open", "todo", "in_progress", "done", "all"] } },
    },
  },
];

const PEOPLE_CHANNELS = new Set(["IMPS", "NEFT", "RTGS"]);

/** Never send names of people the user paid, raw UPI IDs or reference numbers to Gemini. */
function safePayee(row: Json): string {
  const payee = (row.payee as string | null) ?? "";
  if (row.category === "people" || row.category === "income" || PEOPLE_CHANNELS.has(row.channel as string)) return "a person";
  if (/^UPI ••\d{4}$/.test(payee)) return "a UPI contact";
  return payee || ((row.channel as string) ?? "unknown");
}

function safeAccountName(a: Json | undefined): string {
  if (!a) return "unknown account";
  const name = String(a.name ?? "").replace(/•+\s*\d+/g, "").replace(/\d{3,}/g, "").trim();
  return name || `${a.bank ?? ""} ${a.kind ?? "account"}`.trim();
}

const rupees = (minor: number) => Math.round(minor) / 100;

async function loadAccounts(db: SupabaseClient): Promise<Map<string, Json>> {
  const { data, error } = await db.from("accounts").select("id,name,bank,kind,include_in_totals,archived").is("deleted_at", null);
  if (error) throw new Error(error.message);
  return new Map((data ?? []).map((a) => [a.id as string, a as Json]));
}

async function loadTransactions(db: SupabaseClient, args: Json, max: number): Promise<Json[]> {
  let q = db
    .from("transactions")
    .select("occurred_at,amount_minor,direction,category,payee,channel,account_id,is_transfer")
    .is("deleted_at", null)
    .gte("occurred_at", `${args.from}T00:00:00+05:30`)
    .lte("occurred_at", `${args.to}T23:59:59+05:30`)
    .order("occurred_at", { ascending: false })
    .limit(max);
  if (!args.include_transfers) q = q.eq("is_transfer", false);
  if (args.category) q = q.eq("category", args.category as string);
  if (args.direction) q = q.eq("direction", args.direction as string);
  if (args.search) q = q.ilike("payee", `%${String(args.search).replace(/[%_]/g, "")}%`);
  const { data, error } = await q;
  if (error) throw new Error(error.message);
  return (data ?? []) as Json[];
}

async function runTool(db: SupabaseClient, name: string, args: Json): Promise<Json> {
  switch (name) {
    case "query_transactions": {
      const accounts = await loadAccounts(db);
      const limit = Math.min(Number(args.limit ?? 25), 60); // small results keep each round fast
      const rows = await loadTransactions(db, args, limit);
      return {
        count: rows.length,
        transactions: rows.map((r) => ({
          date: String(r.occurred_at).slice(0, 10),
          amount: rupees(r.amount_minor as number),
          direction: r.direction,
          category: r.category,
          merchant: safePayee(r),
          account: safeAccountName(accounts.get(r.account_id as string)),
          transfer: r.is_transfer,
        })),
      };
    }
    case "spending_summary": {
      const accounts = await loadAccounts(db);
      const rows = await loadTransactions(db, { direction: "debit", ...args }, 5000);
      const groups = new Map<string, { total: number; count: number }>();
      for (const r of rows) {
        const key =
          args.group_by === "month" ? String(r.occurred_at).slice(0, 7)
          : args.group_by === "account" ? safeAccountName(accounts.get(r.account_id as string))
          : args.group_by === "merchant" ? safePayee(r)
          : String(r.category);
        const g = groups.get(key) ?? { total: 0, count: 0 };
        g.total += r.amount_minor as number;
        g.count += 1;
        groups.set(key, g);
      }
      const totals = [...groups.entries()]
        .map(([key, g]) => ({ key, amount: rupees(g.total), transactions: g.count }))
        .sort((a, b) => b.amount - a.amount);
      return { total: rupees(rows.reduce((s, r) => s + (r.amount_minor as number), 0)), groups: totals.slice(0, 30) };
    }
    case "list_accounts": {
      const accounts = await loadAccounts(db);
      const out = [];
      for (const [id, a] of accounts) {
        if (a.archived) continue;
        const { data } = await db
          .from("transactions")
          .select("balance_after_minor,occurred_at")
          .eq("account_id", id)
          .not("balance_after_minor", "is", null)
          .order("occurred_at", { ascending: false })
          .limit(1);
        const latest = data?.[0];
        out.push({
          account: safeAccountName(a),
          type: a.kind,
          counted_in_totals: a.include_in_totals,
          last_reported_balance: latest ? rupees(latest.balance_after_minor as number) : null,
          balance_date: latest ? String(latest.occurred_at).slice(0, 10) : null,
        });
      }
      return { accounts: out };
    }
    case "query_items": {
      const kind = String(args.kind);
      let q = db.from("items").select("id,kind,data,occurred_on,ref_id").is("deleted_at", null).eq("kind", kind)
        .order("occurred_on", { ascending: false, nullsFirst: false }).limit(Math.min(Number(args.limit ?? 40), 120));
      if (args.from) q = q.gte("occurred_on", args.from as string);
      if (args.to) q = q.lte("occurred_on", args.to as string);
      const { data, error } = await q;
      if (error) throw new Error(error.message);
      let habitNames = new Map<string, string>();
      if (kind === "habit_log") {
        const { data: habits } = await db.from("items").select("id,data").eq("kind", "habit").is("deleted_at", null);
        habitNames = new Map((habits ?? []).map((h) => [h.id as string, String((h.data as Json).name ?? "habit")]));
      }
      return { count: data?.length ?? 0, items: (data ?? []).map((r) => redactItem(r as Json, habitNames)) };
    }
    case "list_tasks": {
      const { data: plans } = await db.from("plans").select("id,name").is("deleted_at", null);
      const planNames = new Map((plans ?? []).map((p) => [p.id as string, p.name as string]));
      let q = db
        .from("tasks")
        .select("title,status,priority,due_at,due_all_day,remind_at,repeat,my_day,plan_id,checklist,created_at,completed_at")
        .is("deleted_at", null)
        .order("created_at", { ascending: false })
        .limit(150);
      if (args.status === "open") q = q.neq("status", "done");
      else if (args.status && args.status !== "all") q = q.eq("status", args.status as string);
      const { data, error } = await q;
      if (error) throw new Error(error.message);
      return {
        tasks: (data ?? []).map((t) => {
          const items = (t.checklist as { done: boolean }[] | null) ?? [];
          return {
            title: t.title,
            status: t.status,
            priority: t.priority,
            due: t.due_at ? (t.due_all_day === false ? istLocal(String(t.due_at)) : istLocal(String(t.due_at)).slice(0, 10)) : null,
            reminder: t.remind_at ? istLocal(String(t.remind_at)) : null,
            repeats: t.repeat ?? null,
            in_my_day: t.my_day ?? null,
            plan: planNames.get(t.plan_id as string) ?? null,
            checklist: items.length ? `${items.filter((i) => i.done).length}/${items.length}` : null,
            completed: t.completed_at ? String(t.completed_at).slice(0, 10) : null,
          };
        }),
      };
    }
    default:
      return { error: `Unknown tool ${name}` };
  }
}

/** Timestamp → "YYYY-MM-DD HH:mm" in IST (the app is IST-only). */
function istLocal(ts: string): string {
  const d = new Date(new Date(ts).getTime() + 5.5 * 3600_000);
  return d.toISOString().slice(0, 16).replace("T", " ");
}

function chatSetup(body: Json) {
  const today = String(body.today ?? new Date().toISOString().slice(0, 10));
  const now = String(body.now ?? "");
  const messages = (body.messages as { role: string; text: string }[] | undefined) ?? [];
  const contents: Json[] = messages.slice(-12).map((m) => ({
    role: m.role === "model" ? "model" : "user",
    parts: [{ text: m.text }],
  }));
  // A compact snapshot of the user's numbers, built on the phone — answers most questions without tools.
  const snapshot = typeof body.snapshot === "string" ? body.snapshot.slice(0, 6000) : "";
  const system =
    `You are Buddy, the personal assistant inside ${OWNER_POSS} own all-in-one app (money, fitness, health, tasks). ` +
    `Today is ${today}${now ? `, ${now}` : ""}, timezone Asia/Kolkata. Currency is INR: write amounts like ₹1,23,456 (Indian grouping). ` +
    (snapshot ? `${OWNER_POSS} data right now (JSON):\n${snapshot}\nAnswer from this snapshot whenever it covers the question. ` : "") +
    `Use the tools only for details the snapshot doesn't have (specific merchants, older dates, individual transactions) — never guess numbers. ` +
    `Spending excludes transfers between ${OWNER_POSS} own accounts unless asked. ` +
    `Be warm, direct and brief: a sentence or two, or a short list. No markdown tables.`;
  return { contents, system };
}

async function runCalls(db: SupabaseClient, calls: { name: string; args?: Json }[], tools: string[]): Promise<Json[]> {
  return await Promise.all(calls.map(async (call) => {
    tools.push(call.name);
    let result: Json;
    try {
      result = await runTool(db, call.name, call.args ?? {});
    } catch (e) {
      result = { error: e instanceof Error ? e.message : String(e) };
    }
    return { functionResponse: { name: call.name, response: result } };
  }));
}

const MAX_ROUNDS = 3;

/** Non-streaming chat (voice questions, older app versions). */
async function chat(db: SupabaseClient, body: Json): Promise<Json> {
  const { contents, system } = chatSetup(body);
  const tools: string[] = [];
  const started = Date.now();
  const deadline = started + BUDGET_MS.chat;
  for (let round = 0; round < MAX_ROUNDS; round++) {
    // Running low on time or out of rounds: answer with what we have, no more tool calls.
    const lastChance = round === MAX_ROUNDS - 1 || deadline - Date.now() < 9_000;
    const res = await gemini("chat", {
      systemInstruction: { parts: [{ text: system }] },
      contents,
      ...(lastChance ? {} : { tools: [{ functionDeclarations: toolDeclarations }] }),
    }, {}, deadline);
    const content = (res.candidates as Json[] | undefined)?.[0]?.content as Json | undefined;
    const parts = (content?.parts as Json[] | undefined) ?? [];
    const calls = parts.filter((p) => p.functionCall).map((p) => p.functionCall as { name: string; args?: Json });
    if (calls.length === 0 || lastChance) {
      const text = parts.filter((p) => !p.thought && p.text).map((p) => p.text).join("").trim();
      return { text: text || "Sorry, I couldn't come up with an answer.", tools, model: res._model, ms: Date.now() - started };
    }
    contents.push(content!); // verbatim — it may carry thought signatures
    contents.push({ role: "user", parts: await runCalls(db, calls, tools) });
  }
  return { text: "Sorry, I couldn't come up with an answer.", tools, ms: Date.now() - started };
}

/**
 * Streaming chat: Server-Sent Events, so the first words show up in a second or two.
 *   data: {"t":"text chunk"}         … repeated
 *   data: {"status":"Checking transactions…"}  while a tool runs
 *   data: {"done":true,"model":"…","ms":1234,"tools":[…]}
 *   data: {"error":"…"}
 */
function chatStream(db: SupabaseClient, body: Json): Response {
  const enc = new TextEncoder();
  const stream = new ReadableStream({
    async start(controller) {
      const send = (obj: Json) => controller.enqueue(enc.encode(`data: ${JSON.stringify(obj)}\n\n`));
      const started = Date.now();
      const deadline = started + BUDGET_MS.chat;
      const tools: string[] = [];
      let model = "";
      try {
        const { contents, system } = chatSetup(body);
        for (let round = 0; round < MAX_ROUNDS; round++) {
          const lastChance = round === MAX_ROUNDS - 1 || deadline - Date.now() < 9_000;
          const payload = {
            systemInstruction: { parts: [{ text: system }] },
            contents,
            ...(lastChance ? {} : { tools: [{ functionDeclarations: toolDeclarations }] }),
          };
          const r = await streamRound(payload, deadline, (t) => send({ t }));
          model = r.model;
          const calls = r.parts.filter((p) => p.functionCall).map((p) => p.functionCall as { name: string; args?: Json });
          if (calls.length === 0 || lastChance) {
            if (!r.sentText) send({ t: "Sorry, I couldn't come up with an answer." });
            break;
          }
          send({ status: `Checking ${calls.map((c) => TOOL_LABEL[c.name] ?? c.name).join(", ")}…` });
          contents.push({ role: "model", parts: r.parts });
          contents.push({ role: "user", parts: await runCalls(db, calls, tools) });
        }
        send({ done: true, model, ms: Date.now() - started, tools });
      } catch (e) {
        send({ error: e instanceof Error ? e.message : String(e) });
      } finally {
        controller.close();
      }
    },
  });
  return new Response(stream, { headers: { "Content-Type": "text/event-stream", "Cache-Control": "no-cache" } });
}

const TOOL_LABEL: Record<string, string> = {
  query_transactions: "transactions", spending_summary: "spending totals", list_accounts: "accounts",
  list_tasks: "tasks", query_items: "your logs",
};

/**
 * One streamed model turn. Tries the fast models in order until one starts streaming (each must start
 * within the per-call limit). Text is forwarded as it arrives; function calls are collected.
 */
async function streamRound(payload: Json, deadline: number, onText: (t: string) => void): Promise<{ parts: Json[]; model: string; sentText: boolean }> {
  let lastError = "no model available";
  for (const model of await orderFor("chat")) {
    const left = deadline - Date.now();
    if (left < 1_500) break;
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), Math.min(CALL_MS.chat, left));
    const open = (generationConfig: Json) => fetch(`${GEMINI_BASE}/models/${model}:streamGenerateContent?alt=sse`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-goog-api-key": GEMINI_KEY },
      body: JSON.stringify({ ...payload, generationConfig }),
      signal: ctrl.signal,
    });
    let res: Response;
    try {
      res = await open(tune(model, "chat", {}));
      if (res.status === 400) {
        const msg = (await res.clone().json().catch(() => ({})))?.error?.message ?? "";
        // This model doesn't take our thinking settings: same model, defaults.
        if (/thinking/i.test(msg)) res = await open({});
      }
    } catch (_e) {
      clearTimeout(timer);
      lastError = `timeout ${model}`;
      continue;
    }
    if (!res.ok) {
      clearTimeout(timer);
      const msg = (await res.json().catch(() => ({})))?.error?.message ?? res.statusText;
      lastError = `${res.status} ${model}: ${msg}`;
      if (res.status === 404) { unavailable.add(model); continue; }
      if (BUSY.has(res.status)) continue;
      throw new Error(`Gemini ${res.status} (${model}): ${msg}`);
    }
    // Streaming has started: give it until the overall deadline to finish.
    clearTimeout(timer);
    const finish = setTimeout(() => ctrl.abort(), Math.max(deadline - Date.now(), 2_000));
    activeModel.fast = model;
    const parts: Json[] = [];
    let sentText = false;
    let buffer = "";
    const reader = res.body!.pipeThrough(new TextDecoderStream()).getReader();
    try {
      while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += value;
        let nl;
        while ((nl = buffer.indexOf("\n")) >= 0) {
          const line = buffer.slice(0, nl).trim();
          buffer = buffer.slice(nl + 1);
          if (!line.startsWith("data:")) continue;
          const chunk = JSON.parse(line.slice(5).trim()) as Json;
          const cParts = (((chunk.candidates as Json[] | undefined)?.[0]?.content as Json | undefined)?.parts as Json[] | undefined) ?? [];
          for (const p of cParts) {
            if (p.functionCall) parts.push(p);
            else if (p.text && !p.thought) {
              onText(p.text as string);
              sentText = true;
              const last = parts[parts.length - 1];
              if (last && last.text !== undefined && !last.functionCall) last.text = (last.text as string) + p.text;
              else parts.push({ text: p.text });
            }
          }
        }
      }
    } finally {
      clearTimeout(finish);
    }
    return { parts, model, sentText };
  }
  throw new Error(`Gemini is busy right now (${lastError}). Try again in a minute.`);
}

/** Only the fields Gemini may see for each kind (free-tier privacy). */
function redactItem(r: Json, habitNames: Map<string, string>): Json {
  const d = (r.data ?? {}) as Json;
  const date = r.occurred_on ?? null;
  switch (r.kind) {
    case "habit": return { name: d.name, target: d.target, auto: d.auto ?? null, archived: d.archived ?? false };
    case "habit_log": return { date, habit: habitNames.get(r.ref_id as string) ?? "habit", value: d.value };
    case "workout": {
      const run = d.run as Json | null | undefined;
      return {
        date, type: d.type, title: d.title, minutes: d.durationMin,
        exercises: ((d.exercises as Json[] | undefined) ?? []).map((e) => ({ name: e.name, sets: ((e.sets as Json[]) ?? []).length })),
        km: run ? Math.round(Number(run.distanceM ?? 0) / 10) / 100 : undefined,
      };
    }
    case "meal": return { date, kcal: d.kcal, protein_g: d.proteinG, slot: d.slot };
    case "goal": return { title: d.title, area: d.area, target: d.target, current: d.current, unit: d.unit, due: d.due, done: d.done };
    case "reading": return { title: d.title, type: d.type, status: d.status, progress: d.progress };
    case "wish": return { title: d.title, price: rupees(Number(d.priceMinor ?? 0)), status: d.status, think_until: d.coolOffUntil };
    case "holding": return { name: d.name, kind: d.kind, invested: rupees(Number(d.investedMinor ?? 0)), current: rupees(Number(d.currentMinor ?? 0)) };
    case "journal": return { date, mood: d.mood, energy: d.energy };
    case "vehicle": return { name: d.name, insurance_expiry: d.insuranceExpiry, puc_expiry: d.pucExpiry, service_due: d.serviceDue };
    default: return { kind: r.kind };
  }
}

// ---------------------------------------------------------------- vision & meals

const CATEGORY_SLUGS = [
  "food", "groceries", "shopping", "transport", "travel", "bills", "subscriptions", "health", "entertainment",
  "education", "rent", "people", "investments", "fees", "other",
];

const mealSchema = {
  type: "OBJECT",
  properties: {
    description: { type: "STRING" },
    kcal: { type: "INTEGER" },
    protein_g: { type: "INTEGER" },
  },
  required: ["description", "kcal", "protein_g"],
};

async function vision(body: Json): Promise<Json> {
  const purpose = String(body.purpose ?? "receipt");
  const image = String(body.image ?? "");
  if (!image) return { error: "No image" };
  const schema = purpose === "meal" ? mealSchema : {
    type: "OBJECT",
    properties: {
      merchant: { type: "STRING" },
      total_rupees: { type: "NUMBER" },
      date: { type: "STRING", description: "YYYY-MM-DD if printed, else empty" },
      category: { type: "STRING", enum: CATEGORY_SLUGS },
      items: { type: "ARRAY", items: { type: "OBJECT", properties: { name: { type: "STRING" }, amount_rupees: { type: "NUMBER" } } } },
    },
    required: ["merchant", "total_rupees", "category"],
  };
  const prompt = purpose === "meal"
    ? "Estimate this meal (Indian portions if it looks Indian): short description, total kcal and grams of protein."
    : "Read this receipt/bill. Return the merchant, the final amount paid in rupees (after taxes/discounts), the date if printed, the best category, and up to 15 line items.";
  const res = await gemini("command", {
    contents: [{ role: "user", parts: [{ inlineData: { mimeType: String(body.mime ?? "image/jpeg"), data: image } }, { text: prompt }] }],
  }, { responseMimeType: "application/json", responseSchema: schema });
  return parseJsonParts(res);
}

async function mealText(body: Json): Promise<Json> {
  const res = await gemini("command", {
    contents: [{ role: "user", parts: [{ text: `Estimate kcal and protein for: ${String(body.text ?? "")}. Assume typical Indian portions unless stated.` }] }],
  }, { responseMimeType: "application/json", responseSchema: mealSchema });
  return parseJsonParts(res);
}

// ---------------------------------------------------------------- Coach

/**
 * A week-by-week plan from the user's goal and current numbers. The app sends only aggregate numbers
 * (age, weight, recent weekly km, best 5k, gym frequency, top lifts) — nothing identifying.
 */
async function trainingPlan(body: Json): Promise<Json> {
  const session = {
    type: "OBJECT",
    properties: {
      day: { type: "STRING", enum: ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"] },
      type: { type: "STRING", enum: ["run", "strength", "cross", "rest", "mobility"] },
      title: { type: "STRING", description: "Short, e.g. 'Easy run', 'Intervals 6×400 m', 'Upper body'" },
      details: { type: "STRING", description: "2–4 lines. Runs: the exact structure with paces (e.g. '10 min easy @ 6:45/km, 5×3 min @ 5:30/km with 2 min jog, 10 min easy'). Strength: the intent and how to progress from last week." },
      warmup: { type: "STRING", description: "Specific warm-up, 1–2 lines" },
      cooldown: { type: "STRING", description: "Cool-down / stretches, 1 line" },
      intensity: { type: "STRING", description: "Effort target, e.g. 'RPE 6–7', 'Zone 2, conversational', 'leave 2 reps in reserve'" },
      duration_min: { type: "INTEGER" },
      distance_km: { type: "NUMBER" },
      exercises: {
        type: "ARRAY",
        items: {
          type: "OBJECT",
          properties: {
            name: { type: "STRING" }, sets: { type: "INTEGER" }, reps: { type: "STRING", description: "e.g. '8-10', '5', '30 s'" },
            rest_s: { type: "INTEGER", description: "Rest between sets in seconds" },
            load: { type: "STRING", description: "Starting weight or effort: a kg figure when his recent lifts are in the numbers, else e.g. 'RPE 7' or 'bodyweight'" },
            notes: { type: "STRING", description: "One short form cue or the progression rule" },
          },
          required: ["name", "sets", "reps"],
        },
      },
    },
    required: ["day", "type", "title"],
  };
  const schema = {
    type: "OBJECT",
    properties: {
      title: { type: "STRING" },
      summary: { type: "STRING", description: "2–3 sentences: approach and what to expect" },
      weeks: {
        type: "ARRAY",
        items: {
          type: "OBJECT",
          properties: { week: { type: "INTEGER" }, focus: { type: "STRING" }, sessions: { type: "ARRAY", items: session } },
          required: ["week", "focus", "sessions"],
        },
      },
      nutrition: { type: "STRING", description: "Daily calorie and protein targets worked out from his weight and goal, plus 2–3 practical pointers" },
      outline: { type: "ARRAY", items: { type: "STRING" }, description: "One line per week of the whole plan" },
      tips: { type: "ARRAY", items: { type: "STRING" } },
    },
    required: ["weeks"],
  };
  const weeks = Math.min(Math.max(Number(body.weeks ?? 8), 2), 16);
  // Built a few weeks at a time so every session can be detailed without one giant, slow answer.
  const from = Math.min(Math.max(Number(body.from_week ?? 1), 1), weeks);
  const to = Math.min(Math.max(Number(body.to_week ?? weeks), from), weeks);
  const block = from === 1 && to === weeks
    ? ""
    : `Write ONLY weeks ${from} to ${to} of the ${weeks} (number them ${from}..${to}). ` +
      (from > 1
        ? `The plan so far, to continue from without repeating — keep the same weekly layout, session titles and exercises unless progressing them: ${JSON.stringify(body.so_far ?? {})}. `
        : `Also write the title, summary, nutrition, tips and an "outline": one line per week for all ${weeks} weeks (focus + key numbers) so later weeks stay consistent. `);
  const prompt =
    `Create a ${weeks}-week training plan. ${block}`  +
    `Goal type: ${String(body.goal ?? "general fitness")}. ` +
    `Goal details: ${String(body.details ?? "")}. Days available per week: ${Number(body.days ?? 4)}. ` +
    `Experience: ${String(body.experience ?? "beginner")}. Equipment: ${String(body.equipment ?? "gym")}. ` +
    `Current numbers (JSON): ${JSON.stringify(body.stats ?? {})}. ` +
    `Rules: progress gradually (≤10% weekly running volume increase), include a lighter week every 3–4 weeks, ` +
    `at most ${Number(body.days ?? 4)} non-rest sessions a week, use common gym exercise names (e.g. "Barbell Bench Press", ` +
    `"Barbell Full Squat", "Romanian Deadlift"), easy runs at conversational pace, and if a weight goal is given keep the ` +
    `calorie deficit moderate (~500 kcal/day). Use metric units. ` +
    `Be specific and detailed — this is followed session by session: every session has a warm-up, cool-down and intensity; ` +
    `every run spells out its structure with target paces worked out from his recent runs (or by effort if there are none); ` +
    `every strength session lists 5–7 exercises in order (compounds first) with sets, a rep range, rest and a starting load, ` +
    `and says how to progress week to week (add reps, then weight). Keep the same session titles from week to week ` +
    `(e.g. "Upper A", "Lower A") so they can be saved as routines. Include one "rest" entry per rest day only if useful.`;
  const res = await gemini("plan", {
    systemInstruction: { parts: [{ text: "You are an experienced, safety-minded running and strength coach." }] },
    contents: [{ role: "user", parts: [{ text: prompt }] }],
  }, { responseMimeType: "application/json", responseSchema: schema });
  return parseJsonParts(res);
}

/** A short weekly health report from aggregated stats the app computed (no raw logs). */
async function healthReport(body: Json): Promise<Json> {
  const schema = {
    type: "OBJECT",
    properties: {
      headline: { type: "STRING", description: "One upbeat line summarising the week" },
      highlights: { type: "ARRAY", items: { type: "STRING" }, description: "2–4 wins, each one sentence" },
      watch: { type: "ARRAY", items: { type: "STRING" }, description: "0–3 things to keep an eye on" },
      next_week: { type: "ARRAY", items: { type: "STRING" }, description: "3 specific, small actions for next week" },
    },
    required: ["headline", "highlights", "next_week"],
  };
  const res = await gemini("deep", {
    systemInstruction: { parts: [{ text: "You are Buddy, a warm, practical health coach. Be specific with numbers, never preachy, no medical diagnoses." }] },
    contents: [{ role: "user", parts: [{ text: `Weekly health stats (this week vs the week before, JSON): ${JSON.stringify(body.stats ?? {})}` }] }],
  }, { responseMimeType: "application/json", responseSchema: schema });
  return parseJsonParts(res);
}

function parseJsonParts(res: Json): Json {
  const parts = ((res.candidates as Json[] | undefined)?.[0]?.content as Json | undefined)?.parts as Json[] | undefined;
  const out = parts?.filter((p) => !p.thought).map((p) => p.text ?? "").join("") ?? "{}";
  try {
    return JSON.parse(out);
  } catch {
    return { error: "Couldn't read that" };
  }
}

// ---------------------------------------------------------------- briefing / weekly review / insights

function addDays(iso: string, n: number): string {
  const d = new Date(`${iso}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
}

/** Aggregates (not raw rows) across money, tasks, habits, fitness and mood for the period. */
async function gatherContext(db: SupabaseClient, today: string, client: Json): Promise<Json> {
  const from60 = addDays(today, -60);
  const monthStart = today.slice(0, 8) + "01";
  const lastMonthStart = addDays(monthStart, -1).slice(0, 8) + "01";
  const dayOfMonth = Number(today.slice(8, 10));
  const lastMonthSamePoint = addDays(lastMonthStart, dayOfMonth - 1);

  const { data: txns } = await db.from("transactions")
    .select("occurred_at,amount_minor,direction,category,is_transfer")
    .is("deleted_at", null).eq("is_transfer", false)
    .gte("occurred_at", `${from60}T00:00:00+05:30`);
  const spend = (fromD: string, toD: string) => (txns ?? [])
    .filter((t) => t.direction === "debit" && String(t.occurred_at).slice(0, 10) >= fromD && String(t.occurred_at).slice(0, 10) <= toD)
    .reduce((s, t) => s + Number(t.amount_minor), 0);
  const byCat = new Map<string, number>();
  for (const t of txns ?? []) {
    if (t.direction === "debit" && String(t.occurred_at).slice(0, 10) >= monthStart) {
      byCat.set(String(t.category), (byCat.get(String(t.category)) ?? 0) + Number(t.amount_minor));
    }
  }

  const { data: tasks } = await db.from("tasks").select("title,status,due_at,my_day,completed_at,priority").is("deleted_at", null);
  const open = (tasks ?? []).filter((t) => t.status !== "done");
  const due = open.filter((t) => t.due_at && istLocal(String(t.due_at)).slice(0, 10) <= today)
    .map((t) => ({ title: t.title, due: istLocal(String(t.due_at)).slice(0, 10), priority: t.priority }));

  const { data: items } = await db.from("items").select("kind,data,occurred_on,ref_id,id")
    .is("deleted_at", null).in("kind", ["habit", "habit_log", "workout", "journal", "goal", "meal"]);
  const since7 = addDays(today, -7);
  const habits = (items ?? []).filter((i) => i.kind === "habit" && !(i.data as Json).archived);
  const logs = (items ?? []).filter((i) => i.kind === "habit_log" && String(i.occurred_on) > since7);
  const workouts = (items ?? []).filter((i) => i.kind === "workout" && String(i.occurred_on) > since7);
  const moods = (items ?? []).filter((i) => i.kind === "journal" && String(i.occurred_on) > since7)
    .map((i) => ({ date: i.occurred_on, mood: (i.data as Json).mood, energy: (i.data as Json).energy }));
  const meals = (items ?? []).filter((i) => i.kind === "meal" && String(i.occurred_on) > since7);
  const goals = (items ?? []).filter((i) => i.kind === "goal" && !(i.data as Json).done)
    .map((i) => ({ title: (i.data as Json).title, current: (i.data as Json).current, target: (i.data as Json).target, unit: (i.data as Json).unit }));

  return {
    today,
    money: {
      spent_this_month: rupees(spend(monthStart, today)),
      spent_same_point_last_month: rupees(spend(lastMonthStart, lastMonthSamePoint)),
      spent_yesterday: rupees(spend(addDays(today, -1), addDays(today, -1))),
      spent_last_7_days: rupees(spend(addDays(today, -6), today)),
      spent_previous_7_days: rupees(spend(addDays(today, -13), addDays(today, -7))),
      top_categories_this_month: [...byCat.entries()].sort((a, b) => b[1] - a[1]).slice(0, 5)
        .map(([c, v]) => ({ category: c, amount: rupees(v) })),
      upcoming_debits: client.upcoming ?? [],
    },
    tasks: {
      open: open.length,
      due_or_overdue: due.slice(0, 10),
      in_my_day_today: open.filter((t) => t.my_day === today).map((t) => t.title).slice(0, 10),
      completed_last_7_days: (tasks ?? []).filter((t) => t.completed_at && String(t.completed_at).slice(0, 10) > since7).length,
    },
    habits: habits.map((h) => ({
      name: (h.data as Json).name,
      days_done_last_7: new Set(logs.filter((l) => l.ref_id === h.id).map((l) => l.occurred_on)).size,
    })),
    fitness: {
      workouts_last_7_days: workouts.map((w) => ({ date: w.occurred_on, type: (w.data as Json).type, minutes: (w.data as Json).durationMin })),
      steps_last_7_days: client.steps ?? null,
      avg_kcal_logged: meals.length ? Math.round(meals.reduce((s, m) => s + Number((m.data as Json).kcal ?? 0), 0) / 7) : null,
    },
    mood_last_7_days: moods,
    goals,
    screen_time_minutes_yesterday: client.screenMinutes ?? null,
  };
}

async function briefing(db: SupabaseClient, body: Json): Promise<Json> {
  const kind = String(body.kind ?? "daily");
  const today = String(body.today ?? new Date().toISOString().slice(0, 10));
  const context = await gatherContext(db, today, body);
  const voice = `You are Buddy, ${OWNER_POSS} personal assistant. Currency INR (₹, Indian grouping). Warm, specific, no fluff, no markdown headings.`;

  if (kind === "insights") {
    const res = await gemini("deep", {
      systemInstruction: { parts: [{ text: voice }] },
      contents: [{ role: "user", parts: [{ text:
        "From this data, find up to 3 genuinely useful, non-obvious patterns that connect different areas " +
        "(e.g. spending vs workouts, mood vs sleep/steps, habits vs tasks). Each must cite the numbers that support it " +
        "and end with one concrete suggestion. Skip anything you can't support with the data.\n\n" + JSON.stringify(context) }] }],
    }, {
      responseMimeType: "application/json",
      responseSchema: {
        type: "OBJECT",
        properties: { insights: { type: "ARRAY", items: { type: "OBJECT", properties: { title: { type: "STRING" }, body: { type: "STRING" } }, required: ["title", "body"] } } },
        required: ["insights"],
      },
    });
    return parseJsonParts(res);
  }

  const ask = kind === "weekly"
    ? `Write ${OWNER_POSS} Sunday weekly review in under 170 words: wins, money this week vs the previous week, fitness and habit consistency, mood trend, then 3 short, specific suggestions for next week.`
    : `Write ${OWNER_POSS} morning briefing in under 90 words: a one-line greeting, money snapshot (month so far vs same point last month, any upcoming debits), what's due today, and one nudge for habits or fitness.`;
  const res = await gemini("deep", {
    systemInstruction: { parts: [{ text: voice }] },
    contents: [{ role: "user", parts: [{ text: `${ask}\n\nData:\n${JSON.stringify(context)}` }] }],
  });
  const parts = ((res.candidates as Json[] | undefined)?.[0]?.content as Json | undefined)?.parts as Json[] | undefined;
  const text = parts?.filter((p) => !p.thought).map((p) => p.text ?? "").join("").trim() ?? "";
  return { title: kind === "weekly" ? "Your week" : "Good morning", body: text };
}

// ---------------------------------------------------------------- voice command -> actions

async function command(body: Json): Promise<Json> {
  const text = String(body.text ?? "").trim();
  if (!text) return { actions: [], speech: "I didn't catch that." };
  const today = String(body.today ?? new Date().toISOString().slice(0, 10));
  const categories = (body.categories as string[] | undefined) ?? ["other"];
  const accounts = ((body.accounts as string[] | undefined) ?? []).map((a) => a.replace(/•+\s*\d+/g, "").trim());

  const schema = {
    type: "OBJECT",
    properties: {
      actions: {
        type: "ARRAY",
        items: {
          type: "OBJECT",
          properties: {
            type: { type: "STRING", enum: ["log_expense", "log_income", "create_task", "create_note", "question", "unknown"] },
            amount_rupees: { type: "NUMBER" },
            payee: { type: "STRING" },
            category: { type: "STRING", enum: categories },
            account: { type: "STRING", description: "One of the user's accounts if they named one, e.g. 'cash'" },
            title: { type: "STRING" },
            due_date: { type: "STRING", description: "YYYY-MM-DD" },
            due_time: { type: "STRING", description: "HH:mm, 24-hour, only if a time was said" },
            remind_at: { type: "STRING", description: "YYYY-MM-DDTHH:mm local time the reminder should ring" },
            repeat: {
              type: "STRING",
              description: "iCalendar RRULE, e.g. FREQ=DAILY, FREQ=WEEKLY;BYDAY=MO,WE,FR, FREQ=WEEKLY;INTERVAL=2, FREQ=MONTHLY;BYMONTHDAY=-1",
            },
            question: { type: "STRING" },
          },
          required: ["type"],
        },
      },
      speech: { type: "STRING", description: "Very short confirmation to show the user" },
    },
    required: ["actions", "speech"],
  };

  const res = await gemini("command", {
    systemInstruction: {
      parts: [{
        text:
          `Convert what ${OWNER} said to their phone assistant into actions. Today is ${today} (Asia/Kolkata). ` +
          `The text comes from offline speech recognition: expect mistakes, homophones and numbers spelled as words ` +
          `("four fifty" = 450, "two k" = 2000). Currency is rupees. ` +
          `log_expense/log_income need amount_rupees; pick the closest category from the list; if he said when it happened ("yesterday", "last friday", "at 2 pm") set due_date/due_time to that past date/time. ` +
          `"Note…", "make a note…", "write down…" is create_note with title (a few words) and question holding the full note text. ` +
          `create_task needs a short title; resolve relative dates ("tomorrow", "friday") to due_date and a said time to due_time. ` +
          `"Remind me…", "wake me…", "call me…" or "alert me…" also set remind_at: at the said time, or "in 20 minutes" from now ` +
          `(it's ${String(body.now ?? "unknown")} now); with no time given, use 09:00 on the due date (today if none, or tomorrow if 09:00 has passed). ` +
          `Repeats set repeat as an RRULE: "every day" FREQ=DAILY, "weekdays" FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR, ` +
          `"every Mon and Thu" FREQ=WEEKLY;BYDAY=MO,TH, "every 2 weeks" FREQ=WEEKLY;INTERVAL=2, "last day of the month" FREQ=MONTHLY;BYMONTHDAY=-1; ` +
          `due_date is the first occurrence and due_time the time it happens. ` +
          `Questions about his data ("how much did I spend on food") are type question with the cleaned-up question. ` +
          `Accounts he has: ${accounts.join(", ") || "unknown"}. ` +
          `If it's unintelligible, return one action of type unknown.`,
      }],
    },
    contents: [{ role: "user", parts: [{ text }] }],
  }, { responseMimeType: "application/json", responseSchema: schema });
  const raw = ((res.candidates as Json[] | undefined)?.[0]?.content as Json | undefined)?.parts as Json[] | undefined;
  const out = raw?.filter((p) => !p.thought).map((p) => p.text ?? "").join("") ?? "{}";
  try {
    return JSON.parse(out);
  } catch {
    return { actions: [{ type: "unknown" }], speech: "Sorry, I couldn't understand that." };
  }
}
