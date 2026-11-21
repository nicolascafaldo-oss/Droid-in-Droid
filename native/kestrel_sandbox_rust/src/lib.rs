use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

/// Translates a guest virtual path (e.g. "/sdcard/Download/app.apk") into the
/// isolated host sandbox rootfs path while preventing directory traversal.
pub fn translate_guest_path(rootfs: &str, guest_path: &str) -> String {
    let cleaned = guest_path
        .split('/')
        .filter(|seg| !seg.is_empty() && *seg != "." && *seg != "..")
        .collect::<Vec<_>>()
        .join("/");
    if guest_path.is_empty() {
        rootfs.to_string()
    } else if cleaned.is_empty() {
        format!("{}/", rootfs.trim_end_matches('/'))
    } else {
        format!("{}/{}", rootfs.trim_end_matches('/'), cleaned)
    }
}

#[cfg(test)]
mod tests {
    use super::translate_guest_path;

    #[test]
    fn test_translate_guest_path_empty() {
        let root = "/host/root";
        assert_eq!(translate_guest_path(root, ""), root.to_string());
    }

    #[test]
    fn test_translate_guest_path_simple() {
        let root = "/host/root";
        assert_eq!(translate_guest_path(root, "/sdcard/app.apk"),
                   "/host/root/sdcard/app.apk");
    }

    #[test]
    fn test_translate_guest_path_traversal() {
        let root = "/host/root";
        // Attempts to go up directories should be ignored
        assert_eq!(translate_guest_path(root, "../etc/passwd"),
                   "/host/root/etc/passwd");
        assert_eq!(translate_guest_path(root, "../../.."),
                   "/host/root/");
    }

    #[test]
    fn test_translate_guest_path_multiple_slashes() {
        let root = "/host/root";
        assert_eq!(translate_guest_path(root, "//sdcard//app.apk"),
                   "/host/root/sdcard/app.apk");
    }
}
