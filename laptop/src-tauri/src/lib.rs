//! Buddy for Windows: the windows, the global hotkey, the tray icon, and the Wispr Flow key helper.
//! Everything else (tasks, reminders, sync, screens) lives in the web front end (src/).

use enigo::{Direction, Enigo, Key, Keyboard, Settings};
use std::sync::Mutex;
use tauri::menu::{Menu, MenuItem};
use tauri::tray::TrayIconBuilder;
use tauri::{AppHandle, Emitter, Manager, WebviewUrl, WebviewWindow, WebviewWindowBuilder, WindowEvent};
use tauri_plugin_autostart::MacosLauncher;
use tauri_plugin_global_shortcut::{Code, GlobalShortcutExt, Modifiers, Shortcut, ShortcutState};

/// Keys we are holding down for Wispr Flow, released together by [`wispr_release`].
#[derive(Default)]
struct Held(Mutex<Vec<Key>>);

/// The hotkey currently registered.
#[derive(Default)]
struct Current(Mutex<Option<Shortcut>>);

/// The hotkey from settings: any accelerator like "ctrl+Space" or "ctrl+shift+KeyB" (key names as in
/// KeyboardEvent.code), plus the older preset names saved by earlier versions.
fn shortcut_for(name: &str) -> Option<Shortcut> {
    match name {
        "ctrl_shift_space" => Some(Shortcut::new(Some(Modifiers::CONTROL | Modifiers::SHIFT), Code::Space)),
        "ctrl_alt_b" => Some(Shortcut::new(Some(Modifiers::CONTROL | Modifiers::ALT), Code::KeyB)),
        "f9" => Some(Shortcut::new(None, Code::F9)),
        // A single key on its own (the accelerator parser wants a modifier plus a key).
        "right_ctrl" | "ControlRight" => Some(Shortcut::new(None, Code::ControlRight)),
        "right_alt" | "AltRight" => Some(Shortcut::new(None, Code::AltRight)),
        other => other.parse::<Shortcut>().ok(),
    }
}

/// Wispr Flow's push-to-talk key, read from its own settings file (prefs.user.shortcuts: "combo" → "ptt").
/// Falls back to Wispr's default on Windows, Ctrl + Win.
fn wispr_keys() -> Vec<Key> {
    let fallback = vec![Key::Control, Key::Meta];
    let Some(appdata) = std::env::var_os("APPDATA") else { return fallback };
    let path = std::path::Path::new(&appdata).join("Wispr Flow").join("config.json");
    let Ok(text) = std::fs::read_to_string(path) else { return fallback };
    let Ok(json) = serde_json::from_str::<serde_json::Value>(&text) else { return fallback };
    let combo = json
        .pointer("/prefs/user/shortcuts")
        .and_then(|v| v.as_object())
        .and_then(|m| m.iter().find(|(_, v)| v.as_str() == Some("ptt")).map(|(k, _)| k.clone()));
    let Some(combo) = combo else { return fallback };
    let keys: Vec<Key> = combo
        .split('+')
        .filter_map(|p| p.trim().parse::<u32>().ok())
        .map(vk_to_key)
        .collect();
    if keys.is_empty() { fallback } else { keys }
}

fn vk_to_key(vk: u32) -> Key {
    match vk {
        160 | 161 => Key::Shift,
        162 | 163 => Key::Control,
        164 | 165 => Key::Alt,
        91 | 92 => Key::Meta,
        other => Key::Other(other),
    }
}

/// Holds Wispr's talk key down (the bar is open, so Wispr types into it).
#[tauri::command]
fn wispr_press(held: tauri::State<Held>) -> Result<(), String> {
    let keys = wispr_keys();
    let mut enigo = Enigo::new(&Settings::default()).map_err(|e| e.to_string())?;
    for k in &keys {
        enigo.key(*k, Direction::Press).map_err(|e| e.to_string())?;
    }
    *held.0.lock().map_err(|e| e.to_string())? = keys;
    Ok(())
}

/// Lets go of Wispr's talk key.
#[tauri::command]
fn wispr_release(held: tauri::State<Held>) -> Result<(), String> {
    let keys = std::mem::take(&mut *held.0.lock().map_err(|e| e.to_string())?);
    if keys.is_empty() {
        return Ok(());
    }
    let mut enigo = Enigo::new(&Settings::default()).map_err(|e| e.to_string())?;
    for k in keys.iter().rev() {
        enigo.key(*k, Direction::Release).map_err(|e| e.to_string())?;
    }
    Ok(())
}

/// Switches the global hotkey (the front end saves the choice). The new one is registered first, so if
/// another app already owns it, the old hotkey keeps working and the error says why.
#[tauri::command]
fn set_hotkey(app: AppHandle, current: tauri::State<Current>, name: String) -> Result<(), String> {
    let next = shortcut_for(&name).ok_or_else(|| format!("Buddy doesn't recognise the shortcut {name}"))?;
    let mut slot = current.0.lock().map_err(|e| e.to_string())?;
    if *slot == Some(next) {
        let gs = app.global_shortcut();
        if !gs.is_registered(next) {
            gs.register(next).map_err(|e| e.to_string())?;
        }
        return Ok(());
    }
    let gs = app.global_shortcut();
    gs.register(next)
        .map_err(|_| "That shortcut is already used by another app. Try a different one.".to_string())?;
    if let Some(old) = slot.take() {
        let _ = gs.unregister(old);
    }
    *slot = Some(next);
    Ok(())
}

/// Pauses the hotkey while Settings records a new one, then brings it back.
#[tauri::command]
fn suspend_hotkey(app: AppHandle, current: tauri::State<Current>, suspend: bool) -> Result<(), String> {
    let slot = current.0.lock().map_err(|e| e.to_string())?;
    if let Some(sc) = *slot {
        let gs = app.global_shortcut();
        if suspend {
            let _ = gs.unregister(sc);
        } else if !gs.is_registered(sc) {
            gs.register(sc).map_err(|e| e.to_string())?;
        }
    }
    Ok(())
}

/// The computer's name, for the device list ("laptop" in the reminder logic).
#[tauri::command]
fn device_name() -> String {
    std::env::var("COMPUTERNAME").unwrap_or_else(|_| "Laptop".to_string())
}

/// Plays Windows' own notification sound, once. (Short, not an alarm.)
#[tauri::command]
fn notify_sound() -> Result<(), String> {
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    std::process::Command::new("cmd")
        .args(["/C", "start", "", "ms-winsoundevent:Notification.Default"])
        .creation_flags(CREATE_NO_WINDOW)
        .spawn()
        .map(|_| ())
        .map_err(|e| e.to_string())
}

/// Shows the quick bar and gives it focus, so typing and Wispr go there.
#[tauri::command]
fn open_bar(app: AppHandle) -> Result<(), String> {
    show_focused(&app, "bar")
}

fn show_focused(app: &AppHandle, label: &str) -> Result<(), String> {
    let w = app.get_webview_window(label).ok_or_else(|| format!("no window {label}"))?;
    w.show().map_err(|e| e.to_string())?;
    let _ = w.unminimize();
    w.set_focus().map_err(|e| e.to_string())?;
    Ok(())
}

fn make_window(app: &AppHandle, label: &str, width: f64, height: f64, visible: bool) -> tauri::Result<WebviewWindow> {
    WebviewWindowBuilder::new(app, label, WebviewUrl::App(format!("index.html?view={label}").into()))
        .title("Buddy")
        .inner_size(width, height)
        // The main window is a normal window (title bar, minimise, close). The small ones are frameless and see-through.
        .decorations(label == "main")
        .transparent(label != "main")
        .shadow(label == "main")
        .always_on_top(label != "main")
        .skip_taskbar(label != "main")
        .resizable(label == "main")
        .focused(label == "main")
        .visible(visible)
        .build()
}

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            // A second launch just brings up the main window.
            let _ = show_focused(app, "main");
        }))
        .plugin(tauri_plugin_clipboard_manager::init())
        .plugin(tauri_plugin_autostart::init(MacosLauncher::LaunchAgent, Some(vec!["--background"])))
        .plugin(
            tauri_plugin_global_shortcut::Builder::new()
                .with_handler(|app, _shortcut, event| {
                    // The bar's own script decides what a press and a release mean (tap, hold, talk).
                    let state = match event.state() {
                        ShortcutState::Pressed => "down",
                        ShortcutState::Released => "up",
                    };
                    let _ = app.emit_to("bar", "hotkey", state);
                })
                .build(),
        )
        .manage(Held::default())
        .manage(Current::default())
        .invoke_handler(tauri::generate_handler![wispr_press, wispr_release, set_hotkey, open_bar, device_name, notify_sound, suspend_hotkey])
        .setup(|app| {
            let handle = app.handle();
            make_window(handle, "pill", 240.0, 36.0, true)?;
            make_window(handle, "bar", 600.0, 80.0, false)?;
            make_window(handle, "toast", 360.0, 80.0, false)?;
            make_window(handle, "cards", 340.0, 200.0, false)?;
            make_window(handle, "main", 480.0, 680.0, false)?;

            let add = MenuItem::with_id(app, "add", "Add…", true, None::<&str>)?;
            let open = MenuItem::with_id(app, "open", "Open Buddy", true, None::<&str>)?;
            let pill = MenuItem::with_id(app, "pill", "Show or hide today pill", true, None::<&str>)?;
            let quit = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&add, &open, &pill, &quit])?;
            let icon = app.default_window_icon().cloned();
            let mut tray = TrayIconBuilder::new().menu(&menu).tooltip("Buddy").on_menu_event(move |app, event| {
                match event.id.as_ref() {
                    "add" => {
                        let _ = app.emit_to("bar", "open", ());
                        let _ = show_focused(app, "bar");
                    }
                    "open" => {
                        let _ = show_focused(app, "main");
                    }
                    "pill" => {
                        if let Some(w) = app.get_webview_window("pill") {
                            if w.is_visible().unwrap_or(false) {
                                let _ = w.hide();
                            } else {
                                let _ = w.show();
                            }
                        }
                    }
                    "quit" => app.exit(0),
                    _ => {}
                }
            });
            if let Some(icon) = icon {
                tray = tray.icon(icon);
            }
            tray.build(app)?;

            // Ctrl+Space until the front end applies the saved choice.
            let _ = set_hotkey(handle.clone(), handle.state::<Current>(), "ctrl+Space".into());
            Ok(())
        })
        .on_window_event(|window, event| {
            // The main window hides to the tray instead of closing; the app keeps running.
            if let WindowEvent::CloseRequested { api, .. } = event {
                if window.label() == "main" {
                    api.prevent_close();
                    let _ = window.hide();
                }
            }
        })
        .run(tauri::generate_context!())
        .expect("error while running Buddy");
}

#[cfg(test)]
mod tests {
    use super::shortcut_for;

    #[test]
    fn hotkeys_parse() {
        for s in ["ctrl+Space", "ctrl+shift+KeyB", "alt+F9", "F9", "ControlRight", "AltRight", "ctrl_shift_space"] {
            assert!(shortcut_for(s).is_some(), "{s} should parse");
        }
        assert!(shortcut_for("nonsense+++").is_none());
    }
}
