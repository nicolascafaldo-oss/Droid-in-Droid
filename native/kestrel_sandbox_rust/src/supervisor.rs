use crate::seccomp;
use log::{info, warn};
use nix::sys::ptrace;
use nix::sys::signal::{kill, Signal};
use nix::sys::wait::{waitpid, WaitPidFlag, WaitStatus};
use nix::unistd::Pid;
use std::collections::VecDeque;
use std::ffi::CString;
use std::fs::File;
use std::io::Read;
use std::os::raw::c_void;
use std::path::Path;
use std::sync::{
    atomic::{AtomicBool, AtomicU64, Ordering},
    Arc, Mutex,
};
use std::thread::JoinHandle;

const ELF_MAGIC: [u8; 4] = [0x7F, b'E', b'L', b'F'];

#[derive(Debug, Clone)]
pub struct SyscallRegs {
    pub nr: u64,
    pub arg0: u64,
}

#[derive(Debug)]
pub struct SupervisorShared {
    pub state: Mutex<String>,
    pub stop_requested: AtomicBool,
    pub child_pid: Mutex<Option<i32>>,
    pub syscall_traps: AtomicU64,
    pub path_rewrites: AtomicU64,
    pub trace_log: Mutex<VecDeque<String>>,
}

impl SupervisorShared {
    pub fn new(initial_state: &str) -> Self {
        Self {
            state: Mutex::new(initial_state.to_string()),
            stop_requested: AtomicBool::new(false),
            child_pid: Mutex::new(None),
            syscall_traps: AtomicU64::new(0),
            path_rewrites: AtomicU64::new(0),
            trace_log: Mutex::new(VecDeque::with_capacity(48)),
        }
    }

    pub fn push_trace(&self, line: String) {
        if let Ok(mut guard) = self.trace_log.lock() {
            if guard.len() >= 48 {
                guard.pop_back();
            }
            guard.push_front(line);
        }
    }
}

pub struct SupervisorHandle {
    pub shared: Arc<SupervisorShared>,
    pub bpf_instructions: usize,
    pub guest_init_found: bool,
    pub worker: Option<JoinHandle<()>>,
}

impl SupervisorHandle {
    pub fn stop(&mut self) {
        self.shared.stop_requested.store(true, Ordering::SeqCst);
        if let Ok(guard) = self.shared.child_pid.lock() {
            if let Some(pid) = *guard {
                let _ = kill(Pid::from_raw(pid), Signal::SIGKILL);
            }
        }
        if let Some(handle) = self.worker.take() {
            let _ = handle.join();
        }
        if let Ok(mut s) = self.shared.state.lock() {
            *s = "STOPPED".to_string();
        }
    }
}

/// Translates a guest virtual path into the isolated sandbox rootfs while
/// neutralizing directory traversal (`..`) and isolating host Binder nodes.
pub fn translate_guest_path(rootfs: &str, guest_path: &str) -> String {
    let trimmed_root = rootfs.trim_end_matches('/');
    let trimmed_guest = guest_path.trim();

    if trimmed_guest == "/dev/binder"
        || trimmed_guest == "/dev/hwbinder"
        || trimmed_guest == "/dev/vndbinder"
    {
        return format!("{trimmed_root}/dev/binder_proxy");
    }

    let cleaned = trimmed_guest
        .split('/')
        .filter(|seg| !seg.is_empty() && *seg != "." && *seg != "..")
        .collect::<Vec<_>>()
        .join("/");

    if cleaned.is_empty() {
        trimmed_root.to_string()
    } else {
        format!("{trimmed_root}/{cleaned}")
    }
}

fn is_valid_elf_binary(path: &Path) -> bool {
    let Ok(mut file) = File::open(path) else {
        return false;
    };
    let mut magic = [0u8; 4];
    file.read_exact(&mut magic).is_ok() && magic == ELF_MAGIC
}

fn find_guest_init(rootfs_dir: &str) -> Option<String> {
    let candidates = [
        format!("{}/system/bin/kestrel_init", rootfs_dir.trim_end_matches('/')),
        format!("{}/init", rootfs_dir.trim_end_matches('/')),
    ];
    candidates
        .into_iter()
        .find(|p| is_valid_elf_binary(Path::new(p)))
}

#[cfg(target_arch = "aarch64")]
fn read_syscall_regs(pid: Pid) -> Option<SyscallRegs> {
    let mut regs = [0u64; 34]; // user_pt_regs on aarch64: x0..x30, sp, pc, pstate
    let mut iov = libc::iovec {
        iov_base: regs.as_mut_ptr() as *mut c_void,
        iov_len: std::mem::size_of_val(&regs),
    };
    let rc = unsafe {
        libc::ptrace(
            libc::PTRACE_GETREGSET,
            pid.as_raw(),
            1usize as *mut c_void, // NT_PRSTATUS
            &mut iov as *mut libc::iovec as *mut c_void,
        )
    };
    if rc == 0 {
        Some(SyscallRegs {
            nr: regs[8],   // x8
            arg0: regs[0], // x0
        })
    } else {
        None
    }
}

#[cfg(target_arch = "arm")]
fn read_syscall_regs(pid: Pid) -> Option<SyscallRegs> {
    let mut regs = [0u32; 18]; // pt_regs on arm
    let mut iov = libc::iovec {
        iov_base: regs.as_mut_ptr() as *mut c_void,
        iov_len: std::mem::size_of_val(&regs),
    };
    let rc = unsafe {
        libc::ptrace(
            libc::PTRACE_GETREGSET,
            pid.as_raw(),
            1usize as *mut c_void, // NT_PRSTATUS
            &mut iov as *mut libc::iovec as *mut c_void,
        )
    };
    if rc == 0 {
        Some(SyscallRegs {
            nr: regs[7] as u64,   // r7
            arg0: regs[0] as u64, // r0
        })
    } else {
        None
    }
}

#[cfg(target_arch = "x86_64")]
fn read_syscall_regs(pid: Pid) -> Option<SyscallRegs> {
    let mut regs = [0u64; 27]; // user_regs_struct on x86_64
    let mut iov = libc::iovec {
        iov_base: regs.as_mut_ptr() as *mut c_void,
        iov_len: std::mem::size_of_val(&regs),
    };
    let rc = unsafe {
        libc::ptrace(
            libc::PTRACE_GETREGSET,
            pid.as_raw(),
            1usize as *mut c_void, // NT_PRSTATUS
            &mut iov as *mut libc::iovec as *mut c_void,
        )
    };
    if rc == 0 {
        Some(SyscallRegs {
            nr: regs[15],   // orig_rax
            arg0: regs[14], // rdi
        })
    } else {
        None
    }
}

#[cfg(not(any(target_arch = "aarch64", target_arch = "arm", target_arch = "x86_64")))]
fn read_syscall_regs(_pid: Pid) -> Option<SyscallRegs> {
    None
}

/// Launches the ptrace + seccomp-bpf container supervisor for `vm_id`.
/// When a guest ELF `/init` exists inside `rootfs_dir`, forks and ptraces it in a
/// background supervisor thread; otherwise arms the BPF policy and stands by for provisioning.
pub fn spawn_supervisor(vm_id: &str, rootfs_dir: &str, seccomp_mode: i32) -> SupervisorHandle {
    let bpf_len = seccomp::build_filter(seccomp_mode).len();
    let guest_init = find_guest_init(rootfs_dir);

    let Some(init_path) = guest_init else {
        let shared = Arc::new(SupervisorShared::new("ARMED_AWAITING_INIT"));
        shared.push_trace(format!(
            "BPF armed ({bpf_len} insns, {}); awaiting guest ELF /init in {}",
            seccomp::current_arch_name(),
            rootfs_dir
        ));
        info!(
            "KestrelVM[{}] seccomp-bpf armed ({} insns, {}) — no guest ELF init yet",
            vm_id,
            bpf_len,
            seccomp::current_arch_name()
        );
        return SupervisorHandle {
            shared,
            bpf_instructions: bpf_len,
            guest_init_found: false,
            worker: None,
        };
    };

    let Ok(c_prog) = CString::new(init_path.clone()) else {
        return SupervisorHandle {
            shared: Arc::new(SupervisorShared::new("INVALID_INIT_PATH")),
            bpf_instructions: bpf_len,
            guest_init_found: false,
            worker: None,
        };
    };
    let shared = Arc::new(SupervisorShared::new("FORKING_PTRACE"));
    let shared_worker = Arc::clone(&shared);
    let vm_name = vm_id.to_string();

    let worker = std::thread::Builder::new()
        .name(format!("kestrel-sup-{vm_id}"))
        .spawn(move || {
            let mut prebuilt_filter = seccomp::build_filter(seccomp_mode);
            let pid_raw = unsafe { libc::fork() };
            if pid_raw < 0 {
                if let Ok(mut s) = shared_worker.state.lock() {
                    *s = "FORK_FAILED".to_string();
                }
                warn!("KestrelVM[{}] fork() failed", vm_name);
                return;
            }
            if pid_raw == 0 {
                // Child process: async-signal-safe calls ONLY (zero allocations)
                unsafe {
                    libc::prctl(
                        libc::PR_SET_PDEATHSIG,
                        libc::SIGKILL as libc::c_ulong,
                        0 as libc::c_ulong,
                        0 as libc::c_ulong,
                        0 as libc::c_ulong,
                    );
                    if libc::ptrace(
                        libc::PTRACE_TRACEME,
                        0 as libc::pid_t,
                        std::ptr::null_mut::<c_void>(),
                        std::ptr::null_mut::<c_void>(),
                    ) != 0
                    {
                        libc::_exit(120);
                    }
                    if seccomp::install_prebuilt_seccomp(&mut prebuilt_filter).is_err() {
                        libc::_exit(121);
                    }
                    libc::raise(libc::SIGSTOP);
                    let argv = [c_prog.as_ptr(), std::ptr::null()];
                    let envp = [std::ptr::null()];
                    libc::execve(c_prog.as_ptr(), argv.as_ptr(), envp.as_ptr());
                    libc::_exit(127);
                }
            }

            let pid = Pid::from_raw(pid_raw);
            if let Ok(mut guard) = shared_worker.child_pid.lock() {
                *guard = Some(pid_raw);
            }
            if let Ok(mut s) = shared_worker.state.lock() {
                *s = format!("PTRACE_PID_{pid_raw}");
            }
            shared_worker.push_trace(format!(
                "Forked guest init pid={pid_raw} with seccomp-bpf ({bpf_len} insns)"
            ));

            // Wait for initial child SIGSTOP
            if matches!(waitpid(pid, None), Ok(WaitStatus::Stopped(_, Signal::SIGSTOP))) {
                let opts = ptrace::Options::PTRACE_O_TRACESYSGOOD
                    | ptrace::Options::PTRACE_O_TRACESECCOMP
                    | ptrace::Options::PTRACE_O_EXITKILL;
                let _ = ptrace::setoptions(pid, opts);
                let _ = ptrace::cont(pid, None);
            }

            loop {
                if shared_worker.stop_requested.load(Ordering::SeqCst) {
                    let _ = kill(pid, Signal::SIGKILL);
                    let _ = waitpid(pid, Some(WaitPidFlag::__WALL));
                    break;
                }
                match waitpid(pid, Some(WaitPidFlag::__WALL)) {
                    Ok(WaitStatus::PtraceEvent(p, _sig, _event)) => {
                        shared_worker.syscall_traps.fetch_add(1, Ordering::Relaxed);
                        if let Some(regs) = read_syscall_regs(p) {
                            let name = seccomp::syscall_name_for_nr(regs.nr);
                            shared_worker.push_trace(format!(
                                "SECCOMP_TRACE pid={} {}(nr={}, a0=0x{:x})",
                                p.as_raw(),
                                name,
                                regs.nr,
                                regs.arg0
                            ));
                        }
                        let _ = ptrace::cont(p, None);
                    }
                    Ok(WaitStatus::PtraceSyscall(p)) => {
                        shared_worker.syscall_traps.fetch_add(1, Ordering::Relaxed);
                        let _ = ptrace::cont(p, None);
                    }
                    Ok(WaitStatus::Stopped(p, sig)) => {
                        let pass = if sig == Signal::SIGSTOP || sig == Signal::SIGTRAP {
                            None
                        } else {
                            Some(sig)
                        };
                        let _ = ptrace::cont(p, pass);
                    }
                    Ok(WaitStatus::Exited(_, code)) => {
                        if let Ok(mut s) = shared_worker.state.lock() {
                            *s = format!("EXITED_{code}");
                        }
                        shared_worker.push_trace(format!("Guest init exited code={code}"));
                        break;
                    }
                    Ok(WaitStatus::Signaled(_, sig, _)) => {
                        if let Ok(mut s) = shared_worker.state.lock() {
                            *s = format!("SIGNALED_{sig:?}");
                        }
                        break;
                    }
                    Err(_) => break,
                    _ => {
                        let _ = ptrace::cont(pid, None);
                    }
                }
            }

            if let Ok(mut guard) = shared_worker.child_pid.lock() {
                *guard = None;
            }
        })
        .ok();

    SupervisorHandle {
        shared,
        bpf_instructions: bpf_len,
        guest_init_found: true,
        worker,
    }
}

pub fn json_escape(input: &str) -> String {
    let mut out = String::with_capacity(input.len());
    for ch in input.chars() {
        match ch {
            '\\' => out.push_str("\\\\"),
            '"' => out.push_str("\\\""),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => {}
            c => out.push(c),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn isolates_binder_nodes_and_blocks_traversal() {
        assert_eq!(
            translate_guest_path("/data/vm1", "/../../etc/passwd"),
            "/data/vm1/etc/passwd"
        );
        assert_eq!(
            translate_guest_path("/data/vm1/", "/dev/binder"),
            "/data/vm1/dev/binder_proxy"
        );
        assert_eq!(
            translate_guest_path("/data/vm1", "/dev/hwbinder"),
            "/data/vm1/dev/binder_proxy"
        );
    }
}
