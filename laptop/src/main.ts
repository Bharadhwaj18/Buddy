// One front end for every window. Each Tauri window opens index.html?view=<label>, and this picks its screen.
// The "cards" window also runs the reminder engine: one copy, always alive, even while hidden.

import "./styles.css";
import { invoke } from "@tauri-apps/api/core";
import { isEnabled as autostartIsOn, enable as autostartOn, disable as autostartOff } from "@tauri-apps/plugin-autostart";
import { applyLook, loadSettings } from "./lib/settings";
import { isEngine } from "./lib/cloud";
import { Engine } from "./lib/engine";
import { mountPill } from "./views/pill";
import { mountBar } from "./views/bar";
import { mountToast } from "./views/toast";
import { mountCards } from "./views/cards";
import { mountHome } from "./views/home";

const view = new URLSearchParams(location.search).get("view") ?? "main";
const root = document.getElementById("app")!;

// The small windows are see-through around their content.
document.documentElement.classList.toggle("transparent", ["pill", "bar", "toast", "cards"].includes(view));

// Light by day, dark in the evening: checked each minute, and when settings change in any window.
applyLook(loadSettings());
window.setInterval(() => applyLook(loadSettings()), 60_000);
window.addEventListener("storage", () => applyLook(loadSettings()));
window.addEventListener("buddy-settings", () => applyLook(loadSettings()));

switch (view) {
  case "pill":
    mountPill(root);
    break;
  case "bar":
    mountBar(root);
    break;
  case "toast":
    mountToast(root);
    break;
  case "cards":
    mountCards(root);
    break;
  default:
    mountHome(root);
}

if (isEngine) {
  void startEngine();
}

async function startEngine(): Promise<void> {
  const s = loadSettings();
  // The hotkey, start-with-Windows and the device name are set up once, here.
  await invoke("set_hotkey", { name: s.hotkey }).catch((e) => console.warn("hotkey", e));
  try {
    // Rewritten on every start, not just checked: an entry left by an older install can point at a
    // file that's gone, and Windows then quietly skips Buddy at sign-in.
    const on = await autostartIsOn();
    if (s.startWithWindows) {
      if (on) await autostartOff().catch(() => undefined);
      await autostartOn();
    } else if (on) await autostartOff();
  } catch (e) {
    console.warn("autostart", e);
  }
  const engine = new Engine(s.deviceId);
  await engine.start();
}
