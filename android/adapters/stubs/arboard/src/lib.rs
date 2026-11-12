//! Small Android-only compatibility implementation of the arboard API used by
//! horizon-client-input. The real clipboard lives in the original Android platform adapter.

use std::{error::Error as StdError, fmt};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Error {
    ContentNotAvailable,
    Other,
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::ContentNotAvailable => f.write_str("Android clipboard has no text"),
            Self::Other => f.write_str("Android clipboard operation failed"),
        }
    }
}

impl StdError for Error {}

pub struct Clipboard;

unsafe extern "C" {
    fn horizon_android_clipboard_len() -> isize;
    fn horizon_android_clipboard_copy(output: *mut u8, capacity: usize) -> bool;
    fn horizon_android_clipboard_set(input: *const u8, length: usize) -> bool;
}

impl Clipboard {
    pub fn new() -> Result<Self, Error> {
        Ok(Self)
    }

    pub fn get_text(&mut self) -> Result<String, Error> {
        let length = unsafe { horizon_android_clipboard_len() };
        if length < 0 {
            return Err(Error::ContentNotAvailable);
        }
        let mut bytes = vec![0u8; length as usize];
        let output = bytes.as_mut_ptr();
        if !unsafe { horizon_android_clipboard_copy(output, bytes.len()) } {
            return Err(Error::ContentNotAvailable);
        }
        String::from_utf8(bytes).map_err(|_| Error::Other)
    }

    pub fn set_text(&mut self, text: String) -> Result<(), Error> {
        if unsafe { horizon_android_clipboard_set(text.as_ptr(), text.len()) } {
            Ok(())
        } else {
            Err(Error::Other)
        }
    }
}
