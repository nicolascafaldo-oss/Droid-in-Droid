use std::os::raw::{c_int, c_ulong};

// Classic BPF opcodes & seccomp return actions
const BPF_LD: u16 = 0x00;
const BPF_W: u16 = 0x00;
const BPF_ABS: u16 = 0x20;
const BPF_JMP: u16 = 0x05;
const BPF_JEQ: u16 = 0x10;
const BPF_K: u16 = 0x00;
const BPF_RET: u16 = 0x06;

pub const SECCOMP_RET_KILL_PROCESS: u32 = 0x8000_0000;
pub const SECCOMP_RET_TRACE: u32 = 0x7FF0_0000;
pub const SECCOMP_RET_ALLOW: u32 = 0x7FFF_0000;

#[cfg(target_arch = "aarch64")]
mod arch {
    pub const AUDIT_ARCH: u32 = 0xC000_00B7; // AUDIT_ARCH_AARCH64
    pub const ARCH_NAME: &str = "aarch64";
    pub const ALLOW_FAST: &[u32] = &[63, 64, 57, 98, 113, 222, 215, 214, 22]; // read, write, close, futex, clock_gettime, mmap, munmap, brk, epoll_pwait
    pub const TRACE_CORE: &[u32] = &[56, 48, 78, 34, 35, 38, 276, 221, 29, 203, 200]; // openat, faccessat, readlinkat, mkdirat, unlinkat, renameat, renameat2, execve, ioctl, connect, bind
    pub const TRACE_NS_EXTRA: &[u32] = &[174, 175, 160]; // getuid, geteuid, uname
}

#[cfg(target_arch = "arm")]
mod arch {
    pub const AUDIT_ARCH: u32 = 0x4000_0028; // AUDIT_ARCH_ARM
    pub const ARCH_NAME: &str = "arm";
    pub const ALLOW_FAST: &[u32] = &[3, 4, 6, 240, 263, 192, 91, 45, 346]; // read, write, close, futex, clock_gettime, mmap2, munmap, brk, epoll_pwait
    pub const TRACE_CORE: &[u32] = &[322, 334, 332, 323, 328, 329, 382, 11, 54, 283, 282]; // openat..bind
    pub const TRACE_NS_EXTRA: &[u32] = &[199, 201, 122]; // getuid32, geteuid32, uname
}

#[cfg(target_arch = "x86_64")]
mod arch {
    pub const AUDIT_ARCH: u32 = 0xC000_003E; // AUDIT_ARCH_X86_64
    pub const ARCH_NAME: &str = "x86_64";
    pub const ALLOW_FAST: &[u32] = &[0, 1, 3, 202, 228, 9, 11, 12, 281]; // read, write, close, futex, clock_gettime, mmap, munmap, brk, epoll_pwait
    pub const TRACE_CORE: &[u32] = &[257, 269, 267, 258, 263, 264, 316, 59, 16, 42, 49]; // openat..bind
    pub const TRACE_NS_EXTRA: &[u32] = &[102, 107, 63]; // getuid, geteuid, uname
}

#[cfg(not(any(target_arch = "aarch64", target_arch = "arm", target_arch = "x86_64")))]
mod arch {
    pub const AUDIT_ARCH: u32 = 0xC000_003E;
    pub const ARCH_NAME: &str = "generic";
    pub const ALLOW_FAST: &[u32] = &[0, 1, 3];
    pub const TRACE_CORE: &[u32] = &[257, 59, 16];
    pub const TRACE_NS_EXTRA: &[u32] = &[102, 63];
}

#[inline]
const fn bpf_stmt(code: u16, k: u32) -> libc::sock_filter {
    libc::sock_filter {
        code,
        jt: 0,
        jf: 0,
        k,
    }
}

#[inline]
const fn bpf_jump(code: u16, k: u32, jt: u8, jf: u8) -> libc::sock_filter {
    libc::sock_filter { code, jt, jf, k }
}

pub fn current_arch_name() -> &'static str {
    arch::ARCH_NAME
}

pub fn seccomp_mode_label(mode: i32) -> &'static str {
    match mode {
        0 => "PTRACE_SYSCALL",
        2 => "USER_LAND_NS",
        _ => "SECCOMP_BPF + PTRACE",
    }
}

/// Builds a classic BPF filter (`struct sock_filter`) for Android Linux seccomp-bpf.
/// - Verifies `AUDIT_ARCH` first to prevent ABI confusion / 32-on-64 syscall smuggling.
/// - Fast-paths hot I/O & futex syscalls with `SECCOMP_RET_ALLOW`.
/// - Traps filesystem, process, socket, and Binder `ioctl` syscalls with `SECCOMP_RET_TRACE`.
pub fn build_filter(mode: i32) -> Vec<libc::sock_filter> {
    let allow_list = arch::ALLOW_FAST;
    let mut trace_list: Vec<u32> = arch::TRACE_CORE.to_vec();
    if mode == 2 {
        trace_list.extend_from_slice(arch::TRACE_NS_EXTRA);
    }

    let a_len = allow_list.len();
    let t_len = trace_list.len();
    let mut prog = Vec::with_capacity(6 + a_len + t_len);

    // 0: Load seccomp_data.arch (offset 4)
    prog.push(bpf_stmt(BPF_LD | BPF_W | BPF_ABS, 4));
    // 1: Jump over KILL if arch == AUDIT_ARCH
    prog.push(bpf_jump(BPF_JMP | BPF_JEQ | BPF_K, arch::AUDIT_ARCH, 1, 0));
    // 2: Kill if foreign syscall ABI detected
    prog.push(bpf_stmt(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS));
    // 3: Load seccomp_data.nr (offset 0)
    prog.push(bpf_stmt(BPF_LD | BPF_W | BPF_ABS, 0));

    // 4 .. 4+A-1: Fast-path allows
    for (i, &nr) in allow_list.iter().enumerate() {
        let jump_dist = ((a_len - 1 - i) + t_len) as u8;
        prog.push(bpf_jump(BPF_JMP | BPF_JEQ | BPF_K, nr, jump_dist, 0));
    }

    // 4+A .. 4+A+T-1: Ptrace-monitored syscalls
    for (j, &nr) in trace_list.iter().enumerate() {
        let jump_dist = (t_len - j) as u8;
        prog.push(bpf_jump(BPF_JMP | BPF_JEQ | BPF_K, nr, jump_dist, 0));
    }

    // ALLOW target
    prog.push(bpf_stmt(BPF_RET | BPF_K, SECCOMP_RET_ALLOW));
    // TRACE target
    prog.push(bpf_stmt(BPF_RET | BPF_K, SECCOMP_RET_TRACE));

    prog
}

/// Installs a pre-built BPF filter slice on the calling child process (async-signal-safe: zero heap allocations).
pub fn install_prebuilt_seccomp(filter: &mut [libc::sock_filter]) -> Result<usize, c_int> {
    let len = filter.len();
    let res_nnp = unsafe {
        libc::prctl(
            libc::PR_SET_NO_NEW_PRIVS,
            1 as c_ulong,
            0 as c_ulong,
            0 as c_ulong,
            0 as c_ulong,
        )
    };
    if res_nnp != 0 {
        return Err(std::io::Error::last_os_error()
            .raw_os_error()
            .unwrap_or(libc::EPERM));
    }
    let fprog = libc::sock_fprog {
        len: len as libc::c_ushort,
        filter: filter.as_mut_ptr(),
    };
    let res_sec = unsafe {
        libc::prctl(
            libc::PR_SET_SECCOMP,
            libc::SECCOMP_MODE_FILTER as c_ulong,
            &fprog as *const libc::sock_fprog as c_ulong,
            0 as c_ulong,
            0 as c_ulong,
        )
    };
    if res_sec != 0 {
        return Err(std::io::Error::last_os_error()
            .raw_os_error()
            .unwrap_or(libc::EINVAL));
    }
    Ok(len)
}

pub fn syscall_name_for_nr(nr: u64) -> &'static str {
    let code = nr as u32;
    if code == arch::TRACE_CORE[0] {
        "openat"
    } else if code == arch::TRACE_CORE[1] {
        "faccessat"
    } else if code == arch::TRACE_CORE[2] {
        "readlinkat"
    } else if code == arch::TRACE_CORE[3] {
        "mkdirat"
    } else if code == arch::TRACE_CORE[4] {
        "unlinkat"
    } else if code == arch::TRACE_CORE[5] || code == arch::TRACE_CORE[6] {
        "renameat"
    } else if code == arch::TRACE_CORE[7] {
        "execve"
    } else if code == arch::TRACE_CORE[8] {
        "ioctl"
    } else if code == arch::TRACE_CORE[9] {
        "connect"
    } else if code == arch::TRACE_CORE[10] {
        "bind"
    } else if arch::TRACE_NS_EXTRA.contains(&code) {
        "uid/uname"
    } else {
        "syscall"
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bpf_filter_jumps_land_on_allow_and_trace_returns() {
        let prog = build_filter(1);
        assert!(prog.len() >= 15);
        assert_eq!(prog[2].k, SECCOMP_RET_KILL_PROCESS);
        assert_eq!(prog[prog.len() - 2].k, SECCOMP_RET_ALLOW);
        assert_eq!(prog[prog.len() - 1].k, SECCOMP_RET_TRACE);

        // Verify fast-path allow instruction 4 lands exactly on SECCOMP_RET_ALLOW
        let idx4 = 4;
        let target_allow = idx4 + 1 + prog[idx4].jt as usize;
        assert_eq!(target_allow, prog.len() - 2);
    }

    #[test]
    fn user_ns_mode_adds_uid_and_uname_traps() {
        let std_prog = build_filter(1);
        let ns_prog = build_filter(2);
        assert_eq!(ns_prog.len(), std_prog.len() + arch::TRACE_NS_EXTRA.len());
    }
}
