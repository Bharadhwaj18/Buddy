// The small card at the bottom of the screen: "Saving…" with a Cancel button, then "Reminder added".
// The quick bar decides what it says and does the saving; this window only shows it.

import { listen, emit } from "@tauri-apps/api/event";
import { getCurrentWindow } from "@tauri-apps/api/window";

interface ToastState {
  saving: boolean;
  heading: string;
  body: string;
  /** One-tap fixes while saving, e.g. "Task instead", "In 1 hr". */
  chips?: { id: string; label: string }[];
  /** How long the countdown runs. */
  seconds?: number;
}

export function mountToast(root: HTMLElement): void {
  const win = getCurrentWindow();
  root.innerHTML = `
    <div class="toast" role="status">
      <div class="toast-row">
        <div data-mark></div>
        <div class="toast-text"><div class="heading"></div><div class="body"></div></div>
        <button class="pill-btn small" data-cancel hidden>Cancel</button>
      </div>
      <div class="toast-chips" hidden></div>
      <div class="bar-track" hidden><div></div></div>
    </div>`;
  const mark = root.querySelector<HTMLElement>("[data-mark]")!;
  const heading = root.querySelector<HTMLElement>(".heading")!;
  const body = root.querySelector<HTMLElement>(".body")!;
  const cancel = root.querySelector<HTMLButtonElement>("[data-cancel]")!;
  const track = root.querySelector<HTMLElement>(".bar-track")!;
  const fill = track.firstElementChild as HTMLElement;

  const chips = root.querySelector<HTMLElement>(".toast-chips")!;
  cancel.addEventListener("click", () => void emit("toast-cancel"));
  chips.addEventListener("click", (e) => {
    const b = (e.target as HTMLElement).closest<HTMLElement>("[data-chip]");
    if (b) void emit("toast-chip", b.dataset.chip);
  });

  void listen<ToastState | null>("toast-state", ({ payload }) => {
    if (!payload) {
      void win.hide();
      return;
    }
    heading.textContent = payload.heading;
    body.textContent = payload.body;
    body.title = payload.body;
    cancel.hidden = !payload.saving;
    track.hidden = !payload.saving;
    const list = payload.saving ? (payload.chips ?? []) : [];
    chips.hidden = list.length === 0;
    chips.replaceChildren(
      ...list.map((c) => {
        const b = document.createElement("button");
        b.dataset.chip = c.id;
        b.textContent = c.label;
        return b;
      }),
    );
    mark.innerHTML = payload.saving
      ? `<span class="spinner" style="width:22px;height:22px;border-width:3px"></span>`
      : `<span class="ok-badge">✓</span>`;
    if (payload.saving) {
      // Count down the 2 seconds: the bar fills from nothing to full.
      fill.style.transition = "none";
      fill.style.width = "0";
      void fill.offsetWidth;
      fill.style.transition = `width ${payload.seconds ?? 2}s linear`;
      fill.style.width = "100%";
    }
  });
}
