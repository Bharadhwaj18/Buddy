// Reminder cards: a simple pop-up, bottom right, that stays until you answer it (here or on the phone).
// Done / 10 min / 1 hr / Tomorrow / close. Shows how long until the phone rings if you don't answer here.

import { listen } from "@tauri-apps/api/event";
import type { EngineState, Ring } from "../lib/engine";
import { escapeHtml, whenText, capitalise } from "../lib/format";

const PHONE_HEAD_START_MS = 30_000;

export function mountCards(root: HTMLElement): void {
  let rings: Ring[] = [];
  root.innerHTML = `<div class="cards"></div>`;
  const list = root.querySelector<HTMLElement>(".cards")!;

  function draw(): void {
    const now = Date.now();
    const shown = rings.slice(-3);
    const more = rings.length - shown.length;
    list.innerHTML =
      (more > 0 ? `<div class="more">+${more} more</div>` : "") +
      shown
        .map((r) => {
          const phoneIn = Math.max(0, Math.ceil((r.at + PHONE_HEAD_START_MS - now) / 1000));
          const sub = r.missed ? "" : phoneIn > 0 ? `Phone rings in ${phoneIn} s if you don't answer here` : "Ringing on your phone too";
          const bar = !r.missed && phoneIn > 0 ? `<div class="bar-track" style="height:3px;background:var(--divider)"><div style="height:100%;background:var(--accent);width:${Math.round((phoneIn * 1000 * 100) / PHONE_HEAD_START_MS)}%"></div></div>` : "";
          return `
          <div class="ring ${r.missed ? "missed" : ""}" data-task="${escapeHtml(r.taskId)}" data-base="${r.base}">
            <div class="inner">
              <div class="top">
                <span class="when">${r.missed ? "Missed · " : "Reminder · "}${escapeHtml(capitalise(whenText(r.at)))}</span>
                <button class="icon-btn" data-act="dismiss" aria-label="Close">✕</button>
              </div>
              <div class="title">${escapeHtml(r.title)}</div>
              ${sub ? `<div class="sub">${escapeHtml(sub)}</div>` : ""}
              <div class="btns">
                <button class="pill-btn small primary" data-act="done">Done</button>
                <button class="pill-btn small" data-act="snooze" data-min="10">10 min</button>
                <button class="pill-btn small" data-act="snooze" data-min="60">1 hr</button>
                <button class="pill-btn small" data-act="tomorrow">Tomorrow</button>
              </div>
            </div>
            ${bar}
          </div>`;
        })
        .join("");
  }

  list.addEventListener("click", (e) => {
    const btn = (e.target as HTMLElement).closest<HTMLElement>("[data-act]");
    if (!btn) return;
    const card = btn.closest<HTMLElement>(".ring")!;
    const ring = rings.find((r) => r.taskId === card.dataset.task && r.base === Number(card.dataset.base));
    if (!ring) return;
    const act = btn.dataset.act!;
    const type = act === "done" || act === "snooze" || act === "tomorrow" ? act : "dismiss";
    // Close it here at once, then let the engine (same window) save it.
    rings = rings.filter((r) => r !== ring);
    draw();
    const action = type === "snooze" ? { type, ring, minutes: Number(btn.dataset.min) } : { type, ring };
    window.dispatchEvent(new CustomEvent("buddy-action-local", { detail: action }));
  });

  void listen<EngineState>("buddy-state", ({ payload }) => {
    rings = payload.rings;
    draw();
  });
  // Keep the "phone rings in N s" countdown moving. Only the text and bar change, never the buttons,
  // so a click is never lost to a redraw.
  window.setInterval(() => {
    const now = Date.now();
    let ended = false;
    for (const card of list.querySelectorAll<HTMLElement>(".ring:not(.missed)")) {
      const r = rings.find((x) => x.taskId === card.dataset.task && x.base === Number(card.dataset.base));
      if (!r) continue;
      const phoneIn = Math.max(0, Math.ceil((r.at + PHONE_HEAD_START_MS - now) / 1000));
      const sub = card.querySelector<HTMLElement>(".sub");
      if (sub) sub.textContent = phoneIn > 0 ? `Phone rings in ${phoneIn} s if you don't answer here` : "Ringing on your phone too";
      const fill = card.querySelector<HTMLElement>(".bar-track > div");
      if (fill) fill.style.width = `${Math.round((phoneIn * 1000 * 100) / PHONE_HEAD_START_MS)}%`;
      if (phoneIn === 0 && fill) ended = true;
    }
    if (ended) draw();
  }, 1000);
}
