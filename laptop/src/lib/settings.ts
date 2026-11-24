// Laptop settings (saved on this PC) and the light/dark look for the time of day.

export type ThemeMode = "by_time" | "light" | "dark";

export interface Settings {
  deviceId: string;
  /** Global shortcut, e.g. "ctrl+Space" or "ctrl+shift+KeyB" (key names as in KeyboardEvent.code). */
  hotkey: string;
  holdToTalk: boolean;
  sound: boolean;
  theme: ThemeMode;
  /** HH:mm */
  darkFrom: string;
  /** HH:mm */
  lightFrom: string;
  showPill: boolean;
  pillX: number | null;
  pillY: number | null;
  startWithWindows: boolean;
}

/** Shortcuts saved by earlier versions, by their old names. Ctrl + Shift + Space was the old default. */
const OLD_HOTKEYS: Record<string, string> = {
  ctrl_shift_space: "ctrl+Space",
  ctrl_alt_b: "ctrl+alt+KeyB",
  f9: "F9",
  right_ctrl: "ControlRight",
  right_alt: "AltRight",
};

/** "ctrl+shift+KeyB" → "Ctrl + Shift + B". */
export function hotkeyLabel(acc: string): string {
  const names: Record<string, string> = {
    ctrl: "Ctrl", control: "Ctrl", shift: "Shift", alt: "Alt", super: "Win", meta: "Win",
    ControlRight: "Right Ctrl", ControlLeft: "Left Ctrl", AltRight: "Right Alt", AltLeft: "Left Alt",
    ShiftRight: "Right Shift", ShiftLeft: "Left Shift", Space: "Space", Backquote: "`",
  };
  return acc
    .split("+")
    .map((p) => names[p] ?? names[p.toLowerCase()] ?? p.replace(/^Key/, "").replace(/^Digit/, "").replace(/^Numpad/, "Num "))
    .join(" + ");
}

const KEY = "buddy-settings";

function defaults(): Settings {
  return {
    deviceId: crypto.randomUUID(),
    hotkey: "ctrl+Space",
    holdToTalk: true,
    sound: true,
    theme: "by_time",
    darkFrom: "19:00",
    lightFrom: "07:00",
    showPill: true,
    pillX: null,
    pillY: null,
    startWithWindows: true,
  };
}

export function loadSettings(): Settings {
  const base = defaults();
  try {
    const saved = JSON.parse(localStorage.getItem(KEY) ?? "null");
    if (saved && typeof saved === "object") {
      const merged: Settings = { ...base, ...saved };
      if (OLD_HOTKEYS[merged.hotkey]) {
        merged.hotkey = OLD_HOTKEYS[merged.hotkey];
        saveSettings(merged);
      }
      return merged;
    }
  } catch {
    /* unreadable: start from defaults */
  }
  saveSettings(base);
  return base;
}

export function saveSettings(s: Settings): void {
  try {
    localStorage.setItem(KEY, JSON.stringify(s));
  } catch {
    /* storage full or blocked: settings still work for this session */
  }
  window.dispatchEvent(new Event("buddy-settings"));
}

export function updateSettings(patch: Partial<Settings>): Settings {
  const next = { ...loadSettings(), ...patch };
  saveSettings(next);
  return next;
}

export interface Look {
  dark: boolean;
}

/** Calm mist by day, Soft dusk in the evening (or whatever the user picked). */
export function lookFor(s: Settings, now = new Date()): Look {
  if (s.theme === "light") return { dark: false };
  if (s.theme === "dark") return { dark: true };
  const minutes = (hhmm: string) => {
    const [h, m] = hhmm.split(":").map(Number);
    return h * 60 + (m || 0);
  };
  const cur = now.getHours() * 60 + now.getMinutes();
  const darkFrom = minutes(s.darkFrom);
  const lightFrom = minutes(s.lightFrom);
  const dark = darkFrom > lightFrom ? cur >= darkFrom || cur < lightFrom : cur >= darkFrom && cur < lightFrom;
  return { dark };
}

/** Applies the look to the page: CSS variables from styles.css switch on [data-theme]. */
export function applyLook(s: Settings): void {
  document.documentElement.dataset.theme = lookFor(s).dark ? "dark" : "light";
}
