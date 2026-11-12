//! Android has no system trash. The launcher's `desktop_seams::to_trash` gets an error and keeps the file.
use std::fmt;
use std::path::Path;

#[derive(Default)]
pub struct TrashContext;

#[derive(Debug)]
pub struct Error;

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("the trash is not available on Android")
    }
}

impl TrashContext {
    pub fn delete<T: AsRef<Path>>(&self, _path: T) -> Result<(), Error> {
        Err(Error)
    }
}
