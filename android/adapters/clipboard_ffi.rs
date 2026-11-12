//! C ABI used by the Android-only arboard compatibility crate.

#[unsafe(no_mangle)]
pub extern "C" fn horizon_android_clipboard_len() -> isize {
    super::platform::clipboard_get().map_or(-1, |text| text.len() as isize)
}

#[unsafe(no_mangle)]
pub extern "C" fn horizon_android_clipboard_copy(output: *mut u8, capacity: usize) -> bool {
    let Some(text) = super::platform::clipboard_get() else {
        return false;
    };
    if text.len() > capacity || (output.is_null() && !text.is_empty()) {
        return false;
    }
    if !text.is_empty() {
        unsafe { std::ptr::copy_nonoverlapping(text.as_ptr(), output, text.len()) };
    }
    true
}

#[unsafe(no_mangle)]
pub extern "C" fn horizon_android_clipboard_set(input: *const u8, length: usize) -> bool {
    if input.is_null() && length != 0 {
        return false;
    }
    let bytes = if length == 0 {
        &[][..]
    } else {
        unsafe { std::slice::from_raw_parts(input, length) }
    };
    let Ok(text) = std::str::from_utf8(bytes) else {
        return false;
    };
    super::platform::clipboard_set_checked(text)
}
