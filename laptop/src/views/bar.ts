// The quick bar. Tap the hotkey to type; hold it to talk through Wispr Flow (it types into this box).
// Enter closes the bar at once and works out what you meant in the background. A small card then shows
// "Saving…" with Cancel for 2 seconds; only after that is anything saved. Cancel puts your text back.

import { listen, emit } from "@tauri-apps/api/event";
import { invoke } from "@tauri-apps/api/core";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { LogicalPosition, LogicalSize } from "@tauri-apps/api/dpi";
import { WebviewWindow } from "@tauri-apps/api/webviewWindow";
import { plan, describe, type Step } from "../lib/commands";
import { chipsFor, applyChip, type Draft } from "../lib/quick";
import { loadSettings } from "../lib/settings";
import { escapeHtml } from "../lib/format";

const SAVE_DELAY_MS = 2_000;
/** With fix-it chips on the card, a little longer to read them. */
const SAVE_DELAY_CHIPS_MS = 3_500;
const CONFIRM_MS = 2_200;
const HOLD_MS = 350;
const BAR_W = 600;
const BAR_H = 76;

export function mountBar(root: HTMLElement): void {
  const win = getCurrentWindow();
  let talking = false;
  let holdTimer: number | undefined;
  let busy = false;
  let pending = "";
  /** Ends the saving card's wait: "timeout", "cancel" or a chip id. */
  let finishWait: ((how: string) => void) | null = null;

  root.innerHTML = `
    <div class="bar-root">
      <div class="bar">
        <div class="bar-row">
          <span class="bar-icon" data-icon></span>
          <input type="text" placeholder="Add a reminder, a task or a note, or ask something" spellcheck="false" />
          <span class="hint" data-hint></span>
          <button class="pill-btn small" data-cancel>Cancel</button>
        </div>
        <div class="answer" hidden></div>
      </div>
    </div>`;
  const input = root.querySelector<HTMLInputElement>("input")!;
  const icon = root.querySelector<HTMLElement>("[data-icon]")!;
  const hint = root.querySelector<HTMLElement>("[data-hint]")!;
  const answer = root.querySelector<HTMLElement>(".answer")!;
  const barEl = root.querySelector<HTMLElement>(".bar")!;
  const cancelBtn = root.querySelector<HTMLButtonElement>("[data-cancel]")!;

  function waitForCard(ms: number): Promise<string> {
    return new Promise((resolve) => {
      const t = window.setTimeout(() => done("timeout"), ms);
      const done = (how: string) => {
        window.clearTimeout(t);
        finishWait = null;
        resolve(how);
      };
      finishWait = done;
    });
  }

  function setIcon(mode: "idle" | "wave" | "spin"): void {
    icon.innerHTML =
      mode === "wave"
        ? `<span class="wave"><span></span><span></span><span></span><span></span><span></span></span>`
        : mode === "spin"
          ? `<span class="spinner" style="width:18px;height:18px;border-width:2px"></span>`
          : `<span style="color:var(--accent);font-size:18px;line-height:1">✦</span>`;
  }

  function showHint(text: string | null): void {
    hint.innerHTML = text ? `<span class="kbd">${escapeHtml(text)}</span>` : "";
  }

  /** Sizes the window to its content, so the answer block fits. */
  async function fit(): Promise<void> {
    const h = Math.min(460, Math.max(BAR_H, barEl.scrollHeight + 2));
    await win.setSize(new LogicalSize(BAR_W, h));
  }

  async function placeTop(): Promise<void> {
    const x = Math.round((window.screen.availWidth - BAR_W) / 2);
    const y = Math.round(window.screen.availHeight / 5);
    await win.setPosition(new LogicalPosition(x, y));
  }

  async function openBar(): Promise<void> {
    if (busy) return;
    answer.hidden = true;
    answer.innerHTML = "";
    input.value = "";
    setIcon("idle");
    showHint(null);
    await win.setSize(new LogicalSize(BAR_W, BAR_H));
    await placeTop();
    await win.show();
    await win.setFocus();
    input.focus();
  }

  async function hideBar(): Promise<void> {
    await win.hide();
  }

  // ---------------------------------------------------------------- hotkey: tap or hold

  async function startTalk(): Promise<void> {
    talking = true;
    holdTimer = undefined;
    await openBar();
    setIcon("wave");
    showHint("listening");
    barEl.classList.add("listening");
    input.placeholder = "Listening… let go when you're done";
    // Give the box a moment to take focus, so Wispr types into it.
    await new Promise((r) => setTimeout(r, 180));
    await invoke("wispr_press").catch(() => undefined);
  }

  async function endTalk(): Promise<void> {
    talking = false;
    await invoke("wispr_release").catch(() => undefined);
    barEl.classList.remove("listening");
    input.placeholder = "Writing it down…";
    setIcon("idle");
    showHint(null);
    // Wispr's text lands a moment after the key is released. Wait for it to settle, then carry on.
    await new Promise((r) => setTimeout(r, 900));
    input.placeholder = "Add a reminder, a task or a note, or ask something";
    input.focus();
  }

  let downNow = false;
  void listen<string>("hotkey", ({ payload }) => {
    if (payload === "down") {
      if (downNow) return; // key repeat
      downNow = true;
      const s = loadSettings();
      if (talking) return;
      if (s.holdToTalk) {
        holdTimer = window.setTimeout(() => void startTalk(), HOLD_MS);
      }
    } else {
      downNow = false;
      if (talking) {
        void endTalk();
        return;
      }
      if (holdTimer !== undefined) {
        // Released before the hold time: a tap, so open the bar for typing.
        window.clearTimeout(holdTimer);
        holdTimer = undefined;
        void openBar();
      }
    }
  });

  void listen("open", () => void openBar());

  // ---------------------------------------------------------------- typing

  window.addEventListener("keydown", (e) => {
    if (e.key === "Escape") {
      e.preventDefault();
      if (busy) return;
      void hideBar();
    } else if (e.key === "Enter") {
      e.preventDefault();
      void submit();
    }
  });

  cancelBtn.addEventListener("click", () => void hideBar());

  // Clicking away closes the bar, except while you're talking or it's working.
  void win.onFocusChanged(({ payload: focused }) => {
    if (!focused && !talking && !busy && !downNow) void hideBar();
  });

  async function submit(): Promise<void> {
    const text = input.value.trim();
    if (!text || busy) return;
    pending = text;
    input.value = "";
    busy = true;
    await hideBar();

    let steps: Step[];
    try {
      steps = await plan(text);
    } catch {
      steps = [{ kind: "info", text: "Something went wrong. Try again?" }];
    }
    const saves = steps.filter((s): s is Extract<Step, { kind: "save" }> => s.kind === "save");
    if (saves.length === 0) {
      busy = false;
      await showAnswer(steps, text);
      return;
    }

    // Saving card: count down, then save. Cancel stops it before anything is stored; a chip fixes
    // what Buddy understood and saves at once.
    const single = saves.length === 1 ? saves[0] : null;
    let draft: Draft | undefined = single?.draft;
    const chips = draft ? chipsFor(draft) : [];
    const delay = chips.length ? SAVE_DELAY_CHIPS_MS : SAVE_DELAY_MS;
    const label = saves.length > 1 ? `${saves.length} items` : saves[0].heading;
    await emit("toast-state", {
      saving: true,
      heading: `Saving ${label.toLowerCase()}…`,
      body: saves.map((s) => [s.title, s.detail].filter(Boolean).join(" · ")).join("  ·  "),
      chips,
      seconds: delay / 1000,
    });
    await showToast(chips.length > 0);
    const how = await waitForCard(delay);
    if (how === "cancel") return;
    if (how !== "timeout" && draft) draft = applyChip(draft, how);

    const messages: string[] = [];
    for (const s of saves) {
      try {
        messages.push(await s.commit(s === single ? draft : undefined));
      } catch {
        messages.push(`Couldn't save "${s.title}"`);
      }
    }
    for (const s of steps) if (s.kind === "info") messages.push(s.text);
    const doneLabel = draft ? (draft.kind === "reminder" ? "Reminder" : "Task") : label;
    await emit("toast-state", {
      saving: false,
      heading: `${doneLabel} added`,
      body: draft ? `${draft.title} · ${describe(draft)}` : messages.join(" · "),
    });
    busy = false;
    await showToast(false);
    await new Promise((r) => setTimeout(r, CONFIRM_MS));
    if (!busy) {
      await emit("toast-state", null);
      await hideToast();
    }
  }

  // The saving card's Cancel button.
  void listen("toast-cancel", async () => {
    if (!busy || !finishWait) return;
    finishWait("cancel");
    busy = false;
    await emit("toast-state", null);
    await hideToast();
    input.value = pending;
    await openBar();
    input.value = pending;
    input.setSelectionRange(pending.length, pending.length);
  });

  // A chip on the saving card: "Task instead", "In 1 hr", …
  void listen<string>("toast-chip", ({ payload }) => {
    if (busy && finishWait) finishWait(payload);
  });

  async function showAnswer(steps: Step[], original: string): Promise<void> {
    const parts = steps.map((s) => {
      if (s.kind === "answer") return escapeHtml(s.text);
      if (s.kind === "info") return `<span style="color:var(--dim)">${escapeHtml(s.text)}</span>`;
      return "";
    });
    answer.innerHTML = `${parts.join("<br><br>")}<div class="foot">Esc to close</div>`;
    answer.hidden = false;
    setIcon("idle");
    input.value = original;
    await win.show();
    await placeTop();
    await fit();
    await win.setFocus();
    input.focus();
  }

  async function showToast(withChips: boolean): Promise<void> {
    const tw = await WebviewWindow.getByLabel("toast");
    if (!tw) return;
    const w = 420;
    const h = withChips ? 132 : 96;
    const x = Math.round((window.screen.availWidth - w) / 2);
    const y = Math.round(window.screen.availHeight - h - 24);
    await tw.setSize(new LogicalSize(w, h));
    await tw.setPosition(new LogicalPosition(x, y));
    await tw.show();
  }

  async function hideToast(): Promise<void> {
    const tw = await WebviewWindow.getByLabel("toast");
    await tw?.hide();
  }

  // Start hidden: the bar only appears on the hotkey, a tap, or the pill.
  void hideBar();
}
