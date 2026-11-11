// Android launcher UI split into focused implementation parts. These includes stay in
// one module so the parts share the existing private launcher state and helpers.
include!("launcher_pages/shared.rs");
include!("launcher_pages/navigation.rs");
include!("launcher_pages/content_pages.rs");
include!("launcher_pages/dialogs.rs");
include!("launcher_pages/settings.rs");
include!("launcher_pages/activation.rs");
include!("launcher_pages/input.rs");
include!("launcher_pages/text.rs");
