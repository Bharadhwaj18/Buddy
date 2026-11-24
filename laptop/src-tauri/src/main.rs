// Stops a console window from opening next to Buddy on Windows.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    buddy_laptop_lib::run()
}
