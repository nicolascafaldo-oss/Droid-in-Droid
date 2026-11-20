use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

#[derive(Debug, Clone)]
pub struct SandboxInstance {
    pub vm_id: String,
    pub rootfs_path: String,
    pub seccomp_mode: i32,
    pub running: bool,
    pub binder_transactions: u64,
}

static SANDBOX_TABLE: OnceLock<Mutex<HashMap<String, SandboxInstance>>> = OnceLock::new();

fn table() -> &'static Mutex<HashMap<String, SandboxInstance>> {
    SANDBOX_TABLE.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Translates a guest virtual path (e.g. "/sdcard/Download/app.apk") into the
/// isolated host sandbox rootfs path while preventing directory traversal.
pub fn translate_guest_path(rootfs: &str, guest_path: &str) -> String {
    let cleaned = guest_path
        .split('/')
        .filter(|seg| !seg.is_empty() && *seg != "." && *seg != "..")
        .collect::<Vec<_>>()
        .join("/");
    if cleaned.is_empty() {
        rootfs.to_string()
    } else {
        format!("{}/{}", rootfs.trim_end_matches('/'), cleaned)
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_engine_NativeEngineBridge_nativeGetBridgeVersion(
    env: JNIEnv,
    _cls: JClass,
) -> jstring {
    env.new_string("kestrel_sandbox-rs/0.1.0 (seccomp-bpf + binder-proxy)")
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_example_engine_NativeEngineBridge_nativeBootSandbox(
    mut env: JNIEnv,
    _cls: JClass,
    vm_id: JString,
    rootfs_dir: JString,
    seccomp_mode: jint,
) -> jint {
    let Ok(vm_id_str) = env.get_string(&vm_id).map(|s| s.to_string_lossy().into_owned()) else {
        return -1;
    };
    let Ok(rootfs_str) = env.get_string(&rootfs_dir).map(|s| s.to_string_lossy().into_owned()) else {
        return -2;
    };

    if let Ok(mut guard) = table().lock() {
        guard.insert(
            vm_id_str.clone(),
            SandboxInstance {
                vm_id: vm_id_str,
                rootfs_path: rootfs_str,
                seccomp_mode,
                running: true,
                binder_transactions: 0,
            },
        );
    }
    0
}

#[no_mangle]
pub extern "system" fn Java_com_example_engine_NativeEngineBridge_nativeStopSandbox(
    mut env: JNIEnv,
    _cls: JClass,
    vm_id: JString,
) -> jint {
    let Ok(vm_id_str) = env.get_string(&vm_id).map(|s| s.to_string_lossy().into_owned()) else {
        return -1;
    };
    if let Ok(mut guard) = table().lock() {
        if let Some(entry) = guard.get_mut(&vm_id_str) {
            entry.running = false;
        }
    }
    0
}

#[no_mangle]
pub extern "system" fn Java_com_example_engine_NativeEngineBridge_nativeTranslatePath(
    mut env: JNIEnv,
    _cls: JClass,
    vm_id: JString,
    guest_path: JString,
) -> jstring {
    let vm_id_str = env
        .get_string(&vm_id)
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_default();
    let guest_str = env
        .get_string(&guest_path)
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_default();

    let rootfs = table()
        .lock()
        .ok()
        .and_then(|g| g.get(&vm_id_str).map(|s| s.rootfs_path.clone()))
        .unwrap_or_else(|| format!("/data/local/kestrel/{}", vm_id_str));

    let translated = translate_guest_path(&rootfs, &guest_str);
    env.new_string(translated)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}
