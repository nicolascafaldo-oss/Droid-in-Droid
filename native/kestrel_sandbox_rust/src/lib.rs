mod binder_proxy;
mod seccomp;
mod supervisor;

use binder_proxy::BinderRouter;
use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use log::{info, LevelFilter};
use std::collections::HashMap;
use std::sync::{atomic::Ordering, Mutex, Once, OnceLock};
use supervisor::{json_escape, spawn_supervisor, SupervisorHandle};

pub struct SandboxInstance {
    pub vm_id: String,
    pub rootfs_path: String,
    pub seccomp_mode: i32,
    pub running: bool,
    pub supervisor: Option<SupervisorHandle>,
    pub binder: BinderRouter,
    pub path_translations: u64,
}

static SANDBOX_TABLE: OnceLock<Mutex<HashMap<String, SandboxInstance>>> = OnceLock::new();
static LOGGER_INIT: Once = Once::new();

fn ensure_logger_init() {
    LOGGER_INIT.call_once(|| {
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(LevelFilter::Info)
                .with_tag("KestrelSandbox"),
        );
    });
}

fn table() -> &'static Mutex<HashMap<String, SandboxInstance>> {
    SANDBOX_TABLE.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Translates a guest virtual path (e.g. "/sdcard/Download/app.apk") into the
/// isolated host sandbox rootfs path while preventing directory traversal and isolating Binder nodes.
pub fn translate_guest_path(rootfs: &str, guest_path: &str) -> String {
    supervisor::translate_guest_path(rootfs, guest_path)
}

#[no_mangle]
pub extern "system" fn Java_com_example_engine_NativeEngineBridge_nativeGetBridgeVersion(
    env: JNIEnv,
    _cls: JClass,
) -> jstring {
    ensure_logger_init();
    let label = format!(
        "kestrel_sandbox-rs/0.2.0 (seccomp-bpf+ptrace+binder/{})",
        seccomp::current_arch_name()
    );
    env.new_string(label)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_example_engine_NativeEngineBridge_nativeBootSandbox(
    mut env: JNIEnv,
    _cls: JClass,
    vm_id: JString,
    rootfs_dir: JString,
    native_lib_dir: JString,
    seccomp_mode: jint,
) -> jint {
    ensure_logger_init();
    let Ok(vm_id_str) = env.get_string(&vm_id).map(|s| s.to_string_lossy().into_owned()) else {
        return -1;
    };
    let Ok(rootfs_str) = env.get_string(&rootfs_dir).map(|s| s.to_string_lossy().into_owned()) else {
        return -2;
    };
    let Ok(native_lib_str) = env.get_string(&native_lib_dir).map(|s| s.to_string_lossy().into_owned()) else {
        return -3;
    };

    if let Ok(mut guard) = table().lock() {
        if let Some(existing) = guard.get_mut(&vm_id_str) {
            if let Some(mut old_sup) = existing.supervisor.take() {
                old_sup.stop();
            }
        }
        let sup = spawn_supervisor(&vm_id_str, &rootfs_str, &native_lib_str, seccomp_mode);
        let binder = BinderRouter::new(&vm_id_str);
        info!(
            "Sandbox booted vm={} mode={} bpf_insns={} init_elf={}",
            vm_id_str,
            seccomp::seccomp_mode_label(seccomp_mode),
            sup.bpf_instructions,
            sup.guest_init_found
        );
        guard.insert(
            vm_id_str.clone(),
            SandboxInstance {
                vm_id: vm_id_str,
                rootfs_path: rootfs_str,
                seccomp_mode,
                running: true,
                supervisor: Some(sup),
                binder,
                path_translations: 0,
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
    ensure_logger_init();
    let Ok(vm_id_str) = env.get_string(&vm_id).map(|s| s.to_string_lossy().into_owned()) else {
        return -1;
    };
    if let Ok(mut guard) = table().lock() {
        if let Some(entry) = guard.get_mut(&vm_id_str) {
            entry.running = false;
            if let Some(mut sup) = entry.supervisor.take() {
                sup.stop();
            }
            info!("Sandbox stopped vm={}", vm_id_str);
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
    ensure_logger_init();
    let vm_id_str = env
        .get_string(&vm_id)
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_default();
    let guest_str = env
        .get_string(&guest_path)
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_default();

    let mut translated = format!("/data/local/kestrel/{vm_id_str}");
    if let Ok(mut guard) = table().lock() {
        let rootfs = guard
            .get(&vm_id_str)
            .map(|s| s.rootfs_path.clone())
            .unwrap_or_else(|| format!("/data/local/kestrel/{vm_id_str}"));
        translated = translate_guest_path(&rootfs, &guest_str);
        if let Some(entry) = guard.get_mut(&vm_id_str) {
            entry.path_translations += 1;
            if let Some(sup) = &entry.supervisor {
                sup.shared.path_rewrites.fetch_add(1, Ordering::Relaxed);
                sup.shared
                    .push_trace(format!("PATH {} -> {}", guest_str, translated));
            }
        }
    }

    env.new_string(translated)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_example_engine_NativeEngineBridge_nativeBinderTransact(
    mut env: JNIEnv,
    _cls: JClass,
    vm_id: JString,
    service_name: JString,
    code: jint,
    payload: JString,
) -> jstring {
    ensure_logger_init();
    let vm_id_str = env
        .get_string(&vm_id)
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_default();
    let service_str = env
        .get_string(&service_name)
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_default();
    let payload_str = env
        .get_string(&payload)
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_default();

    let reply_json = if let Ok(mut guard) = table().lock() {
        let entry = guard.entry(vm_id_str.clone()).or_insert_with(|| SandboxInstance {
            vm_id: vm_id_str.clone(),
            rootfs_path: format!("/data/local/kestrel/{vm_id_str}"),
            seccomp_mode: 1,
            running: true,
            supervisor: None,
            binder: BinderRouter::new(&vm_id_str),
            path_translations: 0,
        });
        let reply = entry.binder.transact(&service_str, code, &payload_str);
        if let Some(sup) = &entry.supervisor {
            sup.shared.push_trace(format!(
                "BINDER {} code={} status={}",
                service_str, code, reply.status
            ));
        }
        format!(
            "{{\"status\":{},\"handle\":{},\"node\":\"{}\",\"response\":\"{}\"}}",
            reply.status,
            reply.handle,
            json_escape(&reply.isolated_node),
            json_escape(&reply.response)
        )
    } else {
        "{\"status\":-1,\"handle\":0,\"node\":\"\",\"response\":\"lock error\"}".to_string()
    };

    env.new_string(reply_json)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_example_engine_NativeEngineBridge_nativeGetSandboxTelemetry(
    mut env: JNIEnv,
    _cls: JClass,
    vm_id: JString,
) -> jstring {
    ensure_logger_init();
    let vm_id_str = env
        .get_string(&vm_id)
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_default();

    let json = if let Ok(guard) = table().lock() {
        if let Some(entry) = guard.get(&vm_id_str) {
            let (sup_state, bpf_insns, traps, sup_rewrites, child_pid, init_found, recent_trace) =
                match &entry.supervisor {
                    Some(sup) => {
                        let st = sup
                            .shared
                            .state
                            .lock()
                            .map(|g| g.clone())
                            .unwrap_or_else(|_| "UNKNOWN".to_string());
                        let pid = sup
                            .shared
                            .child_pid
                            .lock()
                            .ok()
                            .and_then(|g| *g)
                            .unwrap_or(-1);
                        let last = sup
                            .shared
                            .trace_log
                            .lock()
                            .ok()
                            .and_then(|g| g.front().cloned())
                            .unwrap_or_default();
                        (
                            st,
                            sup.bpf_instructions,
                            sup.shared.syscall_traps.load(Ordering::Relaxed),
                            sup.shared.path_rewrites.load(Ordering::Relaxed),
                            pid,
                            sup.guest_init_found,
                            last,
                        )
                    }
                    None => (
                        if entry.running { "ARMED" } else { "STOPPED" }.to_string(),
                        seccomp::build_filter(entry.seccomp_mode).len(),
                        0u64,
                        0u64,
                        -1i32,
                        false,
                        String::new(),
                    ),
                };

            let total_rewrites = entry.path_translations.max(sup_rewrites);

            format!(
                concat!(
                    "{{\"vmId\":\"{}\",\"running\":{},\"arch\":\"{}\",\"mode\":\"{}\",",
                    "\"supervisorState\":\"{}\",\"childPid\":{},\"guestInitFound\":{},",
                    "\"bpfInstructions\":{},\"syscallTraps\":{},\"pathTranslations\":{},",
                    "\"binderNode\":\"{}\",\"binderTransactions\":{},\"blockedHostEscapes\":{},",
                    "\"registeredServices\":{},\"lastTrace\":\"{}\"}}"
                ),
                json_escape(&entry.vm_id),
                entry.running,
                seccomp::current_arch_name(),
                seccomp::seccomp_mode_label(entry.seccomp_mode),
                json_escape(&sup_state),
                child_pid,
                init_found,
                bpf_insns,
                traps,
                total_rewrites,
                json_escape(&entry.binder.virtual_node),
                entry.binder.total_transactions,
                entry.binder.blocked_host_escapes,
                entry.binder.services.len(),
                json_escape(&recent_trace),
            )
        } else {
            format!(
                "{{\"vmId\":\"{}\",\"running\":false,\"arch\":\"{}\",\"supervisorState\":\"IDLE\"}}",
                json_escape(&vm_id_str),
                seccomp::current_arch_name()
            )
        }
    } else {
        "{}".to_string()
    };

    env.new_string(json)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[cfg(test)]
mod tests {
    use super::translate_guest_path;

    #[test]
    fn test_translate_guest_path_empty() {
        let root = "/host/root";
        assert_eq!(translate_guest_path(root, ""), root);
    }

    #[test]
    fn test_translate_guest_path_simple() {
        let root = "/host/root";
        assert_eq!(
            translate_guest_path(root, "/sdcard/app.apk"),
            "/host/root/sdcard/app.apk"
        );
    }

    #[test]
    fn test_translate_guest_path_traversal() {
        let root = "/host/root";
        assert_eq!(
            translate_guest_path(root, "../etc/passwd"),
            "/host/root/etc/passwd"
        );
        assert_eq!(translate_guest_path(root, "../../.."), "/host/root");
    }

    #[test]
    fn test_translate_guest_path_multiple_slashes() {
        let root = "/host/root";
        assert_eq!(
            translate_guest_path(root, "//sdcard//app.apk"),
            "/host/root/sdcard/app.apk"
        );
    }
}
