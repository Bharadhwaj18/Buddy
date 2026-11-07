# Buddy — Design Document

Status: v1 built (2026-10-03) · Single-user, personal, Android-only

## 0. Build status (what exists today)

| Area | State |
|---|---|
| App shell | Kotlin + Compose, "Dark & bold" theme (near-black, lime accent, Plus Jakarta Sans), tabs: Today · Money · Tasks · Health · Buddy; checklist logo (adaptive + themed) |
| Sync | Offline-first Room + outbox → Supabase, per-table isolation. Tables: tasks, plans, buckets, accounts, transactions, items |
| Money | IndusInd/SBI SMS capture (live + scan), multi-account with last balances, categories, transfer detection, manual entry + Gemini receipt scan, upcoming debits, net worth, wishlist |
| Tasks | Planner-style plans/buckets board, My Day / Upcoming / All / Completed, progress, priority, due dates, checklists, notes |
| Health | Health Connect steps, habits (manual + auto steps / no-spend) with streaks, workout logger, GPS runs (foreground location service), meals with AI estimates, screen time |
| Life | Journal (mood/energy), notes, goals, people (birthdays/catch-ups), reading, vehicles (fuel/service/renewals), document vault (Supabase Storage), insights |
| AI | Edge Function `buddy-ai`: chat with read tools (redacted for the free tier), voice/quick-capture commands, receipt & meal vision, daily briefing, weekly review, insights; model auto-discovery + fallbacks |
| Capture | "Hey Buddy" (Vosk wake word, spike), quick-capture sheet, home widget, Quick Settings tile, share target |
| Delivery | R8 release (~52 MB), stable signing key, `scripts/release.ps1` → private `releases` bucket, in-app updater |

Design decision recorded during the build: v1.5/v2 "life" modules share one flexible synced `items` table
(`kind` + JSON `data` + `occurred_on` + `ref_id`) instead of a table per module — one migration, one sync path,
one AI tool. Finance and tasks keep dedicated relational tables.

Not built yet: recurring tasks, drag-and-drop between buckets, Google Calendar sync, Gemini categorisation
fallback for unknown merchants, statement import, gym geofence, phone step-counter fallback, audio-to-Gemini
voice (currently Vosk transcript → Gemini).

## 1. Goals and non-goals

### Goals
1. One app for finance, fitness, tasks, habits, journal, notes, documents and more, built for exactly one person.
2. **Automation first.** Data should arrive without typing: bank SMS, UPI notifications, Health Connect, GPS, geofences, usage stats. Every feature is tagged Passive, One-tap, or Proactive.
3. **Always-listening voice assistant** ("Hey Buddy ...") that can log an expense, create a task, log a workout, etc.
4. **Offline-first, always.** The phone's local DB is the working copy. Network is only needed for sync and AI.
5. **Gemini with access to my data**, through a controlled tool interface rather than raw DB access.
6. **Survives Google's sideloading/developer-verification rules.**

### Non-goals
- Multi-user, sharing, or a public release. No Play Store.
- iOS.
- Real bank-aggregator integrations (they require licensed entities). SMS and notification parsing is the route.
- Gmail receipt parsing for now (OAuth refresh tokens in "testing" mode expire every 7 days).

## 2. Distribution and the sideloading rules

- Android developer verification applies to apps installed on certified devices. For a personal app, register a **limited distribution account** (free, email only, no government ID, up to 20 devices). Register the package name and signing key once.
- Fallback if that ever changes: the advanced install flow (24h wait) still allows unverified apps.
- Sideloaded apps must be granted **"Allow restricted settings"** manually before SMS-read and Notification Listener access can be enabled. One-time setup, documented in onboarding.
- Keep the signing key backed up outside the repo. Losing it means uninstalling and losing local data, so cloud sync must always be healthy.
- Updates: **in-app updater** that downloads a signed APK from a private Supabase Storage bucket and hands off to the system installer. This needs the "install unknown apps" permission for Buddy, and the same signing key on every release.

## 3. Architecture overview

```
┌──────────────────────── Android app (Kotlin) ─────────────────────────┐
│ UI (Compose)   Widgets / QS tile / Share target / Command palette     │
│ ───────────────────────────────────────────────────────────────────── │
│ Capture layer (all feed one "Capture Inbox"):                         │
│   SMS receiver · Notification listener (UPI apps) · Voice (Buddy)     │
│   Quick-add · Receipt camera · Share sheet · Geofences · Health Conn. │
│ ───────────────────────────────────────────────────────────────────── │
│ Domain: Finance · Fitness · Tasks · Habits · Journal · Notes · Vault  │
│ ───────────────────────────────────────────────────────────────────── │
│ Room DB (personal.db)                                                  │
│ Sync engine (outbox + pull)                                            │
└───────────────┬───────────────────────────────────────────────────────┘
                │ HTTPS (JWT)
┌───────────────▼────────────── Supabase ───────────────────────────────┐
│ Auth (single user, signups disabled) · Postgres + RLS · Storage       │
│ Edge Functions: gemini-proxy, ai-tools, nightly-insights, briefing    │
│ pg_cron: nightly jobs                                                 │
└───────────────┬───────────────────────────────────────────────────────┘
                │ API key held ONLY here
           Gemini API (paid tier)
```

### Tech choices
| Concern | Choice | Notes |
|---|---|---|
| App | Kotlin, Jetpack Compose, Material 3 | Native is required for foreground services, SMS, notification listener, Health Connect, geofencing. One language, no bridge, and background work is reliable when the app is killed. |
| Local DB | Room (SQLite), FTS5 for search | Consider SQLCipher for `personal.db` (see §16). |
| DI / async | Manual `AppContainer` in M0 → Hilt once the graph grows; Coroutines/Flow, WorkManager | |
| Backend | Supabase (free tier to start) | Postgres, Auth, Storage, Edge Functions (Deno/TS). |
| Sync | Custom outbox + pull cursor (see §6) | Single user, mostly append-only data, so LWW is enough. Revisit PowerSync (has a Kotlin SDK + Supabase support) if sync becomes painful. |
| AI | Gemini via Edge Function proxy | Flash-tier for parsing/categorisation, Pro-tier for chat and insights. Model names pinned in config. |
| Wake word | **Free, on-device only.** Spike Vosk (Apache-2.0) with a grammar limited to "hey buddy"; fall back to openWakeWord (Apache-2.0, custom model trained free in Colab) if battery or accuracy is poor | Picovoice dropped (not free). Gemini cannot be the wake word: it would mean streaming the mic to the cloud 24/7. Gemini handles the command *after* the wake word. |
| Health | Health Connect client | Watch-agnostic (see §8). |
| Maps | MapLibre | Free-tier tile provider; v1 can draw the route without a basemap. |
| Repo layout | `shrey-app/android`, `shrey-app/supabase`, `shrey-app/docs` | Monorepo. |

> Revision notes: (1) An earlier idea was a Next.js PWA as the core. Given Android-only, offline-first, always-on voice and SMS capture, a native Android client is primary; a web dashboard is optional and later. (2) React Native (with a thin Kotlin layer for background work) was evaluated and rejected: the UI-volume advantage did not outweigh two toolchains, a bridge, and the shared-SQLite boundary. Decision: **pure Kotlin**.

### Module layout (Android)
Single Gradle app module to start, split by package; extract Gradle modules only when build times demand it.

| Package | Responsibility |
|---|---|
| `capture/` | SMS receiver, notification listener, Buddy voice service, run tracker service, geofences, share/widget/tile entry points. All write to `capture_inbox`. |
| `ingest/` | Parsers (per bank/app), dedupe, categorisation, Gemini fallbacks. Pure Kotlin and unit-testable on the JVM with sample messages. |
| `data/` | Room entities/DAOs, outbox, sync engine, Supabase client. |
| `domain/` | Use cases per module (finance, fitness, tasks, habits, ...). |
| `ui/` | Compose screens, navigation, widgets (Glance), theme. |
| `ai/` | `gemini-proxy` client, tool-call result handling, voice-action application. |

Because capture, ingest and storage live in one process and one language, a capture received while the app is "closed" is parsed, categorised and stored immediately.

## 4. Feature catalogue

Tier: **P** = Passive, **1** = One-tap confirm, **A** = Proactive.

### 4.1 Finance
| Feature | Tier |
|---|---|
| Transaction capture from bank SMS (SBI, IndusInd) | P |
| Transaction capture from UPI app notifications (GPay, PhonePe, etc.) | P |
| Cross-source dedupe and balance-gap detection (see §5) | P |
| Auto-categorisation: merchant rules, then Gemini fallback, then learn from corrections | P |
| Receipt scan, Gemini vision, itemised, linked to a transaction | 1 |
| Subscriptions / recurring / EMI / bill detection and renewal warnings | P/A |
| Budgets, anomaly alerts, month-end forecast | A |
| Net worth: holdings (MF/stocks/gold via price APIs), loans, EMI schedule | P |
| Split / owed money | 1 |
| Wishlist with cooling-off timer | 1 |
| Cash expenses via voice or quick-add | 1 |

### 4.2 Fitness
| Feature | Tier |
|---|---|
| Health Connect sync: steps, sleep, HR, weight, exercise sessions | P |
| Phone step-counter fallback until a watch is available | P |
| Gym check-in via geofence | P |
| Workout logger: templates, sets/reps/weight, rest timer, PRs | 1 |
| Run tracking: GPS, pace, splits, elevation, route | 1 |
| Progressive-overload suggestions | A |
| Recovery-aware daily suggestion (sleep/HR → lighter day) | A |
| Food photo → calorie estimate | 1 |
| Weekly training plan from Gemini | A |
| Body metrics: weight, measurements | P/1 |

### 4.3 Tasks (Planner-style)
Plans, buckets, Kanban board, My Day, calendar view, checklists, labels, priorities, recurrence, subtasks, share-sheet capture, voice capture, Google Calendar two-way sync, auto-created tasks (bill due, recurring events), AI day-planning (A).

### 4.4 Additional modules
- **Habits and streaks:** manual or auto-fed (steps from Health Connect, "no-spend day" from finance). P/1
- **Journal and mood:** 10-second evening check-in. 1
- **Sleep and screen time:** Usage Stats API, sleep from Health Connect. P
- **Places and routine:** place visits, commute time, gym frequency. P
- **Document vault:** IDs, warranties, insurance, OCR search, expiry reminders. 1/A
- **Notes / second brain:** with Gemini search. 1
- **Goals:** roll up from tasks, habits, finance and fitness. A
- **Reading and learning log.** 1
- **Vehicle:** fuel, service and insurance/PUC expiry. 1/A
- **People:** birthdays, "haven't spoken in a while" nudges. A

### 4.5 Cross-cutting
- **Buddy voice assistant** (§7)
- **Insights engine:** cross-domain correlations (spend vs. workouts vs. sleep vs. mood). A
- **Daily briefing** and **weekly review** (Gemini-written, with an offline template fallback). A
- **Global quick-add:** widget, Quick Settings tile, app shortcut, share target, and a command palette. All route into the Capture Inbox.
- **Unified search** (FTS5 across all modules).
- **Security:** biometric lock, RLS, AI access audit log.
- **Backup and full export.**

## 5. Finance capture design

### 5.1 Sources
| Source | Mechanism | Gap |
|---|---|---|
| IndusInd (planned primary) | SMS receiver, allowlisted sender headers | Few |
| SBI (secondary) | SMS receiver | **SBI rarely sends SMS for small debits** (reported under ₹5k) |
| UPI apps | Notification listener on their package names | Only works for transactions made in those apps |
| Cash / other | Voice, quick-add | Manual by nature |
| Statement import (later) | CSV/PDF for monthly reconciliation | Password-protected PDFs |

### 5.2 Pipeline
```
raw event ─► allowlist filter ─► parser (regex per bank/app) ─► [low confidence → Gemini parse]
          ─► normalise (amount_minor, direction, account, merchant_raw, ref, balance?)
          ─► fingerprint & dedupe across sources
          ─► categorise ─► insert transaction (status: confirmed | pending_review)
```
- **OTP and non-transaction messages are never processed.** Only allowlisted sender headers (to be captured from real messages) and messages matching a debit/credit pattern are parsed.
- **Raw SMS text stays on the device.** Only parsed fields and a hash sync to Supabase.
- **Dedupe:** a fingerprint of (amount, direction, account, UPI reference if present, time window ±10 min). A UPI notification and a bank SMS for the same payment merge into one transaction with both sources recorded.
- **Balance-gap detection:** when an SMS carries an available balance, compare it with the running balance. A mismatch (e.g. a missed ₹320 SBI debit) creates a "₹320 unaccounted, what was it?" one-tap prompt. This is the main answer to SBI's missing small-transaction SMS.
- **Learning:** corrections feed `category_rules`.
- Needs a corpus of real (redacted) sample messages from both banks before the parsers are written.

### 5.3 Findings from M0 spike (b) — a real (redacted) message sample, fully classified
- **Senders:** the 2-letter operator prefix and the suffix vary per message (`VM-`, `AD-`, `CP-`, `JD-`, `JX-`, `AX-`, `JK-`, `VA-`, `VK-`; `-S`/`-T`), so match on the 6-character entity header only. Observed: `INDUSB`; `SBIUPI`, `SBIINB`, `CBSSBI`; `SBIBNK`, `SBIPSG` are allowlisted but their messages are not yet verified. `INDUSO` (likely offers) is deliberately excluded.
- **Balance availability:** IndusInd UPI/card messages and SBI CBS messages include the balance; SBI UPI messages don't. Balance-gap detection therefore works for IndusInd and partially for SBI.
- **Upcoming debits:** both banks send AutoPay pre-debit notices ("… is scheduled on …") and the bank apps send "will be deducted on …". Parsed as `UpcomingDebit` → bill reminders and subscription detection.
- **Not-a-new-transaction messages:** the NEFT "credited to beneficiary" confirmation repeats a debit already captured; failed standing instructions moved no money. Both are ignored to avoid double counting.
- **Self-transfers:** many credits/debits are between the owner's own accounts. v1 needs transfer matching (own-VPA list + amount/time pairing across accounts) so these count as transfers, not income or spend.
- **Same amount, same minute ≠ duplicate:** e.g. two separate SIP debits in the same minute with different reference numbers. Transaction-level dedupe must key on the bank reference, not amount + time.
- **Parser changes apply retroactively:** captured raw text is kept locally, so every scan re-parses earlier captures.

## 6. Offline-first and sync

- Every table has: `id uuid` (client-generated), `user_id`, `created_at`, `updated_at` (client), `server_updated_at` (set by trigger), `deleted_at` (soft delete).
- **Writes** go to Room and to an **outbox** table in the same transaction.
- **Push:** WorkManager drains the outbox in batches through an upsert RPC. Conflict rule: last write wins by `updated_at`. Money and workout data are effectively append-only, so conflicts are rare.
- **Pull:** per-table cursor on `server_updated_at`; apply to Room; tombstones propagate deletes.
- **Triggers:** on app foreground, after local writes (debounced), and periodically.
- **Large blobs** (receipt images, GPS tracks as GPX, voice clips, documents) go to Supabase Storage. Rows hold the storage path, and uploads retry independently.
- Schema migrations: Supabase SQL migrations plus Room migrations, versioned together.
- **Supabase free tier:** projects pause after a week of inactivity. Daily sync prevents this, but a nightly cron job and an alert if sync has failed for 48h are cheap insurance. The free tier has no downloadable backups, so the app does a weekly full export (§12).

## 7. "Hey Buddy" — always-on voice

### 7.1 Flow
```
On-device wake word (Vosk keyword grammar → openWakeWord if needed)
  → chime + haptic
  → record until silence (VAD, ≤15 s)
  → ONLINE:  audio → gemini-proxy (audio-in, function-calling) → structured actions
     OFFLINE: save clip to Capture Inbox (+ best-effort on-device STT), process on reconnect
  → apply actions (log_expense, create_task, log_workout, add_habit_log, add_note, ...)
  → notification: "Logged ₹450 · Food · Undo"  (+ optional short TTS)
  → ambiguous? notification with action buttons / follow-up question
  → delete audio after processing
```
- Gemini's audio understanding handles Hindi/Hinglish better than most on-device recognisers, so audio goes straight to the model rather than through a separate STT step.
- Destructive or high-value actions (large amounts, deletes) require confirmation; routine ones are applied with Undo.

### 7.2 Android constraints (verify in the spike)
- Needs a **foreground service of type `microphone`** with a persistent notification. Android shows the **mic privacy indicator** whenever it's listening.
- Android 14+ restricts starting microphone foreground services from the background and from boot receivers, so after a reboot the user may need to open the app once. Treat "service died, tap to restart" as an expected state.
- Samsung's battery management is aggressive. Onboarding must walk through disabling battery optimisation for the app.
- The mic is silenced or pre-empted during phone calls and when another app records; the service must pause and resume cleanly.
- Battery cost of continuous wake-word detection must be measured on the real phone. Hard acceptance bar: under ~3–5%/day (to be confirmed).
- **Fallbacks that don't need the mic running:** home widget, Quick Settings tile, in-app mic button, and a floating button.

## 8. Fitness data and watch support

- **Health Connect is the abstraction.** The app reads Health Connect, never a vendor SDK, so any watch or tracker that writes to it works: Galaxy Fit3 (via Samsung Health → Health Connect), Garmin, Fitbit, Amazfit, and so on.
- Read via periodic WorkManager jobs plus Health Connect change tokens: steps, sleep sessions/stages, heart rate, resting HR, weight, exercise sessions, distance, calories.
- **Until the watch arrives:** the phone's step counter sensor and manual or GPS-tracked workouts.
- Source-aware storage: each sample records `source_app` so we can dedupe when two apps write overlapping data.
- Own GPS runs are written to the app's DB, and optionally back to Health Connect as exercise sessions so other apps can see them.
- Some vendor-specific metrics (body composition, stress) may not be exposed by Health Connect; accepted limitation.

## 9. (Removed)

## 10. Gemini integration

- **Key custody:** the Gemini API key lives only in Edge Function secrets. The app talks to `gemini-proxy` with the user's Supabase JWT.
- **Paid tier**, so data isn't used for training.
- **Tool interface, not raw DB access.** Edge Function tools run with the user's JWT, so RLS applies:
  - Read: `query_transactions`, `spend_summary`, `list_tasks`, `workout_history`, `health_summary`, `habit_status`, `search_notes`, `get_journal_range`.
  - Write: `create_task`, `log_expense`, `log_workout`, `log_habit`, `add_note`, `create_reminder`.
  - Writes from chat/voice are flagged `source=ai` and shown with Undo; high-impact writes need confirmation.
- **Audit log:** every tool call (tool, params, row count, timestamp) goes to `ai_audit_log`, visible in-app.
- **Sensitive-field policy:** no raw SMS text, account numbers, document images or OCR text for identity documents ever go to Gemini. The vault is excluded from AI tools unless explicitly enabled.
- Chat requires connectivity. Offline, the chat shows "waiting for network" and voice captures queue.

## 11. Data model (draft)

All tables carry the sync columns from §6. Money is stored as integer minor units (paise) plus currency.

```sql
-- Finance
accounts(id, name, bank, type, last4, is_primary, last_balance_minor, balance_seen_at)
categories(id, name, parent_id, icon)
merchants(id, name, default_category_id, aliases text[])
category_rules(id, pattern, merchant_id, category_id, priority)
transactions(
  id, account_id, amount_minor, currency, direction,        -- debit|credit
  occurred_at, merchant_raw, merchant_id, category_id, note,
  source,            -- sms|upi_notif|manual|voice|receipt|import
  source_ref, upi_ref, fingerprint, raw_hash,
  status,            -- confirmed|pending_review
  confidence, ai_source bool)
transaction_sources(transaction_id, source, source_ref, seen_at)
budgets(id, category_id, period, amount_minor)
recurring_items(id, merchant_id, amount_minor, cadence, next_due, kind) -- sub|bill|emi
holdings(id, kind, symbol, units, avg_cost_minor, ...)
loans(id, principal_minor, rate, emi_minor, start_date, tenure_months)
debts(id, counterparty, amount_minor, direction, settled_at)
wishlist(id, title, price_minor, url, cool_off_until)

-- Fitness
exercises(id, name, muscle_group, ...)
workout_templates(id, name) / template_exercises(...)
workouts(id, type, started_at, ended_at, source, notes)        -- strength|run|other
workout_sets(id, workout_id, exercise_id, set_no, reps, weight_g, rpe)
runs(workout_id, distance_m, duration_s, avg_pace, elevation_m, track_path)  -- GPX in Storage
health_samples(id, type, value, unit, start_at, end_at, source_app, hc_record_id)
sleep_sessions(id, start_at, end_at, stages jsonb, source_app)
body_metrics(id, measured_at, kind, value)
food_logs(id, logged_at, description, kcal, protein_g, photo_path, ai_estimated)

-- Tasks
plans(id, name, color) / buckets(id, plan_id, name, position)
tasks(id, plan_id, bucket_id, parent_id, title, notes, status, priority,
      start_at, due_at, rrule, my_day_date, source, position)
checklist_items(id, task_id, text, done) · labels · task_labels
entity_links(id, from_type, from_id, to_type, to_id)   -- task↔goal↔habit↔transaction...

-- Other modules
habits(id, name, cadence, target, auto_source) / habit_logs(id, habit_id, date, value)
journal_entries(id, date, mood, energy, text)
notes(id, title, body, tags) · goals(id, title, target, due) · documents(id, kind, title, storage_path, expires_on, ocr_text)
vehicles(id, ...) / vehicle_events(...) · people(id, name, birthday, last_contact) · reading_items(...)
places(id, name, lat, lng, radius, kind) / place_visits(id, place_id, arrived_at, left_at)
screen_time_daily(date, total_s, per_app jsonb)

-- Cross-cutting
capture_inbox(id, kind, payload, status, created_at, processed_at, result_ref)
insights(id, created_at, kind, title, body, evidence jsonb, dismissed_at)
briefings(id, date, kind, body)           -- daily|weekly
ai_conversations / ai_messages / ai_audit_log
sync_state(table_name, cursor)            -- local only
```
RLS on every Supabase table: `user_id = auth.uid()`. Signups disabled after the first user is created.

## 12. Security and privacy

- Single Supabase user; email+password (or passkey), signups disabled, strong password in a password manager.
- Biometric / device-credential app lock; auto-lock timeout.
- Local DB: `personal.db` relies on device encryption plus the app lock (no SQLCipher, see §16).
- Secrets: Gemini key in Edge Function secrets; Supabase anon key in the app is fine because RLS is enforced.
- Raw SMS and voice clips stay local and are deleted after processing (SMS: only the hash is kept).
- Least-privilege permissions, requested at the moment of need, each explained: SMS, notification access, microphone, location (foreground, then background only for geofences), activity recognition, usage access, Health Connect read scopes, camera.
- **Backup/export:** weekly full export (JSON + CSV, encrypted) to a user-chosen folder or Drive via the Storage Access Framework, plus a manual "Export everything" button.
- Release builds are signed with a key held offline and backed up.

## 13. Insights, briefing and review

- **Nightly (server):** `pg_cron` triggers an Edge Function that computes correlations over SQL aggregates (not raw rows) and asks Gemini to phrase the findings. Results land in `insights`, and the phone picks them up on its next sync and raises a **local notification** (no FCM required).
- **Daily briefing:** generated on-device-scheduled (WorkManager / exact alarm) by calling the briefing function. Contents: tasks due, calendar, budget left, planned workout, recovery note. Offline fallback: a template-based briefing with no AI.
- **Weekly review:** a Sunday summary across finance, fitness, habits, mood, tasks, with proposed next-week goals.
- Insight quality bar: every insight must show its evidence (the numbers behind it), and dismissing one teaches the engine to stop repeating that pattern.

## 14. Phasing

| Milestone | Scope | Exit criteria |
|---|---|---|
| **M0: Spikes** | (a) Wake word + mic FGS on your phone, with battery measured. (b) SMS/notification parsing against real redacted samples. (c) Health Connect read. (d) Sync skeleton with Supabase. | Each spike answers go/no-go and updates this doc. |
| **v1: Foundation** | Auth, offline DB, sync, Capture Inbox, quick-add (widget/tile/share), Finance auto-capture + categorisation + dedupe, Tasks board, Gemini chat with read tools, Health Connect steps/sleep/HR. | Daily-driver for expenses and tasks, with SMS capture running for 2 weeks. |
| **v1.5: Buddy** | Hey Buddy voice, workouts, GPS runs, habits, daily briefing, receipt scan, gym geofence. | Voice works reliably for a week without manual restarts. |
| **v2: Intelligence** | Insights engine, proactive nudges, weekly review, journal, notes, goals, documents vault, screen time, places. | Insights are mostly correct and not noisy. |
| **Later** | Vehicle, people, reading, wishlist, food photo, net worth, statement import, web dashboard, Fit3-specific polish. | |

## 15. Risks

| Risk | Mitigation |
|---|---|
| Always-on mic is killed by Android or Samsung, or drains battery | M0 spike; fallbacks (widget, tile, in-app mic); onboarding for battery settings |
| Bank SMS formats change or vary | Allowlist + regex per bank + Gemini fallback + `pending_review` state + balance-gap detection |
| A bank that skips SMS for small debits | Pick a primary account with complete SMS coverage; UPI notification source; balance-gap prompts |
| Sync bugs corrupt data | Soft deletes, append-mostly data, weekly encrypted export, sync tests with fake server |
| Google tightens sideloading further | Limited distribution account; keep data portable via export; a PWA companion remains possible |
| In-app updater: lost/changed signing key breaks upgrades | Back up the keystore offline; keep cloud sync healthy so a reinstall loses nothing |
| Supabase free-tier limits (500 MB DB, pausing, no backups) | Daily sync, health alert, weekly export, compact GPS storage via GPX files |
| Scope is huge for one person | Strict milestone gating; Capture Inbox as the one shared pipeline so new modules are cheap |

## 16. Decisions and open questions

### Decided
| # | Topic | Decision |
|---|---|---|
| 3 | `personal.db` encryption | **No SQLCipher for `personal.db`.** Rely on Android's device encryption plus the biometric app lock. Revisit only if the threat model changes. |
| 4 | Locale | IST and INR only. Multi-currency out of scope. |
| 5 | Fit3 | Confirm which Fit3 metrics reach Health Connect once the watch is bought; the app reads Health Connect only, so no code depends on this. |
| 6 | Updates | **In-app updater**: downloads a signed APK from a private Supabase Storage bucket and hands off to the system installer. Signing key must be the same across releases. |
| 7 | Name | **Buddy** (assistant name and wake word "Hey Buddy"). |

### Open, with a default
| # | Topic | Default / how it resolves |
|---|---|---|
| 2 | Wake-word engine | Must be **free**. Picovoice dropped. Spike **Vosk** with a "hey buddy" grammar first (official Android library, offline, no training). If battery or false triggers are poor, switch to **openWakeWord** (needs a custom model trained in a free Colab notebook and a Kotlin port of its audio pipeline). Decided by M0 spike (a). |
