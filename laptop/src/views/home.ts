// The main window: sign in, Today, Upcoming and Settings. Opened from the tray icon.

import { listen, emit } from "@tauri-apps/api/event";
import { invoke } from "@tauri-apps/api/core";
import { getVersion } from "@tauri-apps/api/app";
import { enable as autostartOn, disable as autostartOff } from "@tauri-apps/plugin-autostart";
import * as cloud from "../lib/cloud";
import type { EngineState } from "../lib/engine";
import { toTask, buildAgenda, type Task } from "../lib/tasks";
import { loadSettings, updateSettings, hotkeyLabel, type Settings, type ThemeMode } from "../lib/settings";
import { escapeHtml, greeting, timeOf, whenText } from "../lib/format";

type Tab = "today" | "upcoming" | "settings";

export function mountHome(root: HTMLElement): void {
  let state: EngineState = { tasks: [], rings: [], online: true, signedIn: false, email: null, queued: 0 };
  let tab: Tab = "today";
  let email: string | null = null;
  let signInError = "";
  let busy = false;
  let recording = false;
  let hotkeyError = "";
  let version = "";
  void getVersion().then((v) => {
    version = v;
    draw();
  });

  root.innerHTML = `<div class="main"></div>`;
  const main = root.querySelector<HTMLElement>(".main")!;

  async function loadEmail(): Promise<void> {
    const s = await cloud.currentSession();
    email = s?.user.email ?? null;
  }

  function draw(): void {
    if (!state.signedIn) {
      main.innerHTML = signInHtml();
      return;
    }
    const tasks: Task[] = state.tasks.map(toTask);
    const a = buildAgenda(tasks);
    main.innerHTML = `
      <div class="main-head">
        <div class="titles">
          <h1>${escapeHtml(greeting())}</h1>
          <div class="date">${escapeHtml(new Date().toLocaleDateString("en-GB", { weekday: "long", day: "numeric", month: "long" }))}</div>
        </div>
        ${state.online ? "" : `<span class="chip warn">Offline</span>`}
        ${state.queued ? `<span class="chip">${state.queued} to sync</span>` : ""}
        <button class="pill-btn primary" data-add>Add</button>
      </div>
      <div class="tabs">
        <button class="tab ${tab === "today" ? "on" : ""}" data-tab="today">Today</button>
        <button class="tab ${tab === "upcoming" ? "on" : ""}" data-tab="upcoming">Upcoming</button>
        <button class="tab ${tab === "settings" ? "on" : ""}" data-tab="settings">Settings</button>
      </div>
      <div class="scroll">${tab === "today" ? todayHtml(a) : tab === "upcoming" ? upcomingHtml(tasks) : settingsHtml()}</div>`;
  }

  function signInHtml(): string {
    return `
      <form class="signin" data-signin>
        <h1>Welcome to Buddy</h1>
        <p>Sign in with the same account as your phone</p>
        <input class="field" type="email" name="email" placeholder="Email" autocomplete="username" required />
        <input class="field" type="password" name="password" placeholder="Password" autocomplete="current-password" required />
        ${signInError ? `<div class="error">${escapeHtml(signInError)}</div>` : ""}
        <button class="pill-btn primary" type="submit" ${busy ? "disabled" : ""}>${busy ? "Signing in…" : "Sign in"}</button>
      </form>`;
  }

  function todayHtml(a: ReturnType<typeof buildAgenda>): string {
    const pct = a.total ? Math.round((a.done.length / a.total) * 100) : 0;
    const groups = [
      group("Overdue", a.overdue.map(toRow), true),
      group("Today", a.today.map(toRow)),
      group("Inbox · no date", a.inbox.map(toRow)),
      group("Done", a.done.map(toRow)),
    ].join("");
    return `
      ${a.total ? `<div style="display:flex;align-items:center;gap:10px;margin-bottom:12px"><div class="progress" style="flex:1;margin:0"><div style="width:${pct}%"></div></div><span style="color:var(--accent-text);font-size:12px">${a.done.length} of ${a.total} done</span></div>` : ""}
      ${a.left === 0 ? `<div class="card empty">${a.total === 0 ? "Nothing planned for today. Press the hotkey to add something." : "All done for today. Nice."}</div>` : ""}
      ${groups}`;
  }

  function upcomingHtml(tasks: Task[]): string {
    const start = new Date();
    start.setHours(0, 0, 0, 0);
    const days = new Map<string, Task[]>();
    for (const t of tasks) {
      if (t.isDone) continue;
      const when = t.remindAt ?? t.dueAt;
      if (when === null || when < start.getTime() + 86_400_000 || when > start.getTime() + 15 * 86_400_000) continue;
      const key = new Date(when).toDateString();
      days.set(key, [...(days.get(key) ?? []), t]);
    }
    if (days.size === 0) return `<div class="card empty">Nothing in the next two weeks.</div>`;
    return [...days.entries()]
      .sort(([a], [b]) => new Date(a).getTime() - new Date(b).getTime())
      .map(([key, list]) => {
        const d = new Date(key);
        const label = whenText(d.getTime(), false);
        return group(capitalise(label), list.map(toRow));
      })
      .join("");
  }

  function settingsHtml(): string {
    const s = loadSettings();
    const themes: Array<[ThemeMode, string]> = [
      ["by_time", "Light by day, dark in the evening"],
      ["light", "Always light"],
      ["dark", "Always dark"],
    ];
    return `
      <div class="card">
        ${row(
          "Hotkey",
          recording
            ? "Press the new shortcut now. Esc to cancel."
            : hotkeyError || "Tap to type. Hold to talk through Wispr Flow, then press Enter.",
          recording
            ? `<span class="kbd">Waiting…</span>`
            : `<span style="display:inline-flex;gap:8px;align-items:center"><span class="kbd">${escapeHtml(hotkeyLabel(s.hotkey))}</span><button class="pill-btn small" data-record>Change</button></span>`,
        )}
        ${row("Hold to talk", "Holding the hotkey also holds Wispr's talk key", toggle("holdToTalk", s.holdToTalk))}
        ${row("Today pill", "The small pill at the top of the screen", toggle("showPill", s.showPill))}
        ${row("Look", null, `<select class="field" data-set="theme">${themes.map(([v, l]) => `<option value="${v}" ${s.theme === v ? "selected" : ""}>${l}</option>`).join("")}</select>`)}
        ${s.theme === "by_time" ? row("Dark from", null, timeSelect("darkFrom", s.darkFrom)) + row("Light from", null, timeSelect("lightFrom", s.lightFrom)) : ""}
        ${row("Reminder sound", "A short notification sound when a reminder pops up", toggle("sound", s.sound))}
        ${row("Start with Windows", "Quietly in the tray when you sign in", toggle("startWithWindows", s.startWithWindows))}
        ${s.pillX !== null ? row("Pill position", "You moved it", `<button class="pill-btn small" data-reset-pill>Reset</button>`) : ""}
      </div>
      <div class="card" style="margin-top:12px">
        ${row("Signed in as", email ?? "", `<button class="pill-btn small" data-signout>Sign out</button>`)}
        ${row("Buddy", version ? `Version ${version}` : "", "")}
      </div>`;
  }

  function row(title: string, sub: string | null, control: string): string {
    return `<div class="setting"><div class="label">${escapeHtml(title)}${sub ? `<div class="sub">${escapeHtml(sub)}</div>` : ""}</div>${control}</div>`;
  }

  function toggle(key: keyof Settings, on: boolean): string {
    return `<button class="switch ${on ? "on" : ""}" data-toggle="${key}" aria-pressed="${on}" aria-label="${key}"></button>`;
  }

  function timeSelect(key: "darkFrom" | "lightFrom", value: string): string {
    const options: string[] = [];
    for (let h = 5; h <= 23; h++) for (const m of [0, 30]) {
      const v = `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}`;
      const label = timeOf(new Date(`2000-01-01T${v}`).getTime());
      options.push(`<option value="${v}" ${v === value ? "selected" : ""}>${label}</option>`);
    }
    return `<select class="field" data-set="${key}">${options.join("")}</select>`;
  }

  function group(title: string, rows: string[], late = false): string {
    if (rows.length === 0) return "";
    return `<div class="group"><h3 class="${late ? "late" : ""}">${escapeHtml(title)}</h3><div class="card">${rows.join("")}</div></div>`;
  }

  function toRow(t: Task): string {
    const meta = t.isDone
      ? ""
      : t.remindAt !== null
        ? `<span style="font-size:11px;color:var(--dim)"><span class="bell" title="Reminder">🔔</span> ${t.repeat ? "repeats · " : ""}${escapeHtml(whenText(t.remindAt))}</span>`
        : t.dueAt !== null
          ? `<span style="font-size:11px;color:var(--dim)">due ${escapeHtml(whenText(t.dueAt, !t.dueAllDay))}</span>`
          : "";
    return `<div class="item ${t.isDone ? "done" : ""}"><span class="check ${t.isDone ? "done" : ""}" data-check="${escapeHtml(t.id)}"></span><span class="title">${escapeHtml(t.title)}</span>${meta}</div>`;
  }

  // ---------------------------------------------------------------- recording a new hotkey

  const MODIFIER_CODES = ["ControlLeft", "ControlRight", "ShiftLeft", "ShiftRight", "AltLeft", "AltRight", "MetaLeft", "MetaRight"];
  let lastModifier: string | null = null;

  async function applyHotkey(acc: string): Promise<void> {
    try {
      await invoke("set_hotkey", { name: acc });
      updateSettings({ hotkey: acc });
      hotkeyError = "";
    } catch (err) {
      hotkeyError = String(err);
    }
    await invoke("suspend_hotkey", { suspend: false }).catch(() => undefined);
    recording = false;
    draw();
  }

  window.addEventListener("keydown", (e) => {
    if (!recording) return;
    e.preventDefault();
    if (e.key === "Escape") {
      recording = false;
      void invoke("suspend_hotkey", { suspend: false }).catch(() => undefined);
      draw();
      return;
    }
    if (MODIFIER_CODES.includes(e.code)) {
      lastModifier = e.code;
      return;
    }
    lastModifier = null;
    const parts: string[] = [];
    if (e.ctrlKey) parts.push("ctrl");
    if (e.altKey) parts.push("alt");
    if (e.shiftKey) parts.push("shift");
    if (e.metaKey) parts.push("super");
    if (parts.length === 0 && !/^F\d+$/.test(e.code)) {
      hotkeyError = "Add Ctrl, Alt, Shift or Win to it, so normal typing isn't caught.";
      draw();
      return;
    }
    void applyHotkey([...parts, e.code].join("+"));
  });

  // Right Ctrl or Right Alt on its own also works as a hotkey (press and let go).
  window.addEventListener("keyup", (e) => {
    if (!recording || !lastModifier) return;
    const solo = lastModifier;
    lastModifier = null;
    if (e.code === solo && (solo === "ControlRight" || solo === "AltRight")) void applyHotkey(solo);
  });

  // ---------------------------------------------------------------- events

  main.addEventListener("click", async (e) => {
    const el = e.target as HTMLElement;
    const tabBtn = el.closest<HTMLElement>("[data-tab]");
    if (tabBtn) {
      tab = tabBtn.dataset.tab as Tab;
      draw();
      return;
    }
    if (el.closest("[data-add]")) {
      await invoke("open_bar");
      return;
    }
    if (el.closest("[data-signout]")) {
      await cloud.signOut();
      await emit("buddy-action", { type: "refresh" });
      return;
    }
    if (el.closest("[data-record]")) {
      // Pause the current shortcut so pressing it here doesn't pop the bar open.
      await invoke("suspend_hotkey", { suspend: true }).catch(() => undefined);
      recording = true;
      hotkeyError = "";
      draw();
      return;
    }
    if (el.closest("[data-reset-pill]")) {
      updateSettings({ pillX: null, pillY: null });
      draw();
      return;
    }
    const check = el.closest<HTMLElement>("[data-check]");
    if (check) {
      const id = check.dataset.check!;
      const t = state.tasks.map(toTask).find((x) => x.id === id);
      if (t) await emit("buddy-action", { type: t.isDone ? "reopen" : "complete", taskId: id });
      return;
    }
    const toggleBtn = el.closest<HTMLElement>("[data-toggle]");
    if (toggleBtn) {
      const key = toggleBtn.dataset.toggle as keyof Settings;
      const cur = loadSettings();
      const next = updateSettings({ [key]: !cur[key] } as Partial<Settings>);
      if (key === "startWithWindows") await (next.startWithWindows ? autostartOn() : autostartOff());
      draw();
    }
  });

  main.addEventListener("change", async (e) => {
    const sel = e.target as HTMLSelectElement;
    const key = sel.dataset.set;
    if (!key) return;
    if (key === "theme") {
      updateSettings({ theme: sel.value as ThemeMode });
    } else {
      updateSettings({ [key]: sel.value } as Partial<Settings>);
    }
    draw();
  });

  main.addEventListener("submit", async (e) => {
    e.preventDefault();
    const form = e.target as HTMLFormElement;
    const data = new FormData(form);
    busy = true;
    signInError = "";
    draw();
    try {
      await cloud.signIn(String(data.get("email") ?? ""), String(data.get("password") ?? ""));
      await emit("buddy-action", { type: "refresh" });
    } catch {
      signInError = "That email and password didn't work. Try again.";
    } finally {
      busy = false;
      draw();
    }
  });

  void listen<EngineState>("buddy-state", async ({ payload }) => {
    const wasSignedIn = state.signedIn;
    state = payload;
    if (payload.signedIn && !wasSignedIn) await loadEmail();
    draw();
  });

  void (async () => {
    await loadEmail();
    draw();
  })();
  window.setInterval(draw, 60_000);
}

function capitalise(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1);
}
