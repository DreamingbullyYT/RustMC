//! Android clipboard bridge. Kept in the adapter crate so upstream clipboard
//! semantics and desktop arboard support remain untouched.

use std::sync::OnceLock;

/// Platform callbacks installed by NativeActivity after JNI is available.
#[derive(Clone, Copy)]
pub struct ClipboardHooks {
    pub get: fn() -> Option<String>,
    pub set: fn(&str),
}

static HOOKS: OnceLock<ClipboardHooks> = OnceLock::new();

pub fn install_clipboard(hooks: ClipboardHooks) {
    let _ = HOOKS.set(hooks);
}

pub fn clipboard_get() -> Option<String> {
    HOOKS.get().and_then(|hooks| (hooks.get)())
}

pub fn clipboard_set(text: &str) {
    if !text.is_empty() {
        if let Some(hooks) = HOOKS.get() {
            (hooks.set)(text);
        }
    }
}
