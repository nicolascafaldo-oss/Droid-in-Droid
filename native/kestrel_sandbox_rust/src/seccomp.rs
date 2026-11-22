// Test builds compile every architecture's table (for cross-arch coverage) but
// only the active arch's constants are referenced by production code, so the
// remaining table constants would trip `dead_code` under `cfg(test)` only.
// A normal (non-test) build compiles just the active module and stays warning-
// free without any allowance.
#![cfg_attr(test, allow(dead_code))]

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

// Per-architecture syscall tables. Each module is compiled for its own
// `target_arch` AND under `cfg(test)`, so the unit tests can cover all
// architectures from a single host while a normal (non-test) build only
// compiles the active one (avoids `dead_code` on the unused modules). The
// `arch` alias at the bottom selects the one matching `target_arch`;
// production code only ever references `arch::...`, exactly as before.
//
// All syscall numbers were verified against the NDK r27 headers by running
// them through the preprocessor:
//   aarch64: sysroot/usr/include/asm-generic/unistd.h (included by
//            aarch64-linux-android/asm/unistd.h)
//   arm:     sysroot/usr/include/arm-linux-androideabi/asm/unistd-eabi.h
//   x86_64:  sysroot/usr/include/x86_64-linux-android/asm/unistd_64.h
#[cfg(any(test, target_arch = "aarch64"))]
pub mod arch_aarch64 {
    pub const AUDIT_ARCH: u32 = 0xC000_00B7; // AUDIT_ARCH_AARCH64
    pub const ARCH_NAME: &str = "aarch64";
    pub const ALLOW_FAST: &[u32] = &[63, 64, 57, 98, 113, 222, 215, 214, 22]; // read, write, close, futex, clock_gettime, mmap, munmap, brk, epoll_pwait

    /// Phase 1 launcher + bionic libc runtime + liblog allow-list.
    ///
    /// Covers bionic process startup (which runs before `main`), the
    /// `kestrel_init` launcher itself, and liblog's logd socket traffic.
    /// Deny-by-default is preserved for process-control and privileged
    /// syscalls: `ptrace`, `clone`/`clone3`/`fork`/`vfork`, `mount`/`umount2`,
    /// `pivot_root`, `chroot`, `kexec_load`, `delete_module`, `init_module`,
    /// `bpf`, `perf_event_open`, `userfaultfd` and `seccomp` are never allowed.
    /// Filesystem-navigation and stat syscalls are deliberately left in
    /// `TRACE_CORE` so Phase 2 can rewrite/spoof them.
    ///
    /// Numbers verified against NDK r27 `asm-generic/unistd.h` (aarch64).
    pub const LAUNCHER_ALLOW: &[u32] = &[
        // process: prctl, getppid, getpid, gettid, tgkill, exit, exit_group,
        //          set_tid_address, set_robust_list
        167, 173, 172, 178, 131, 93, 94, 96, 99,
        // signals: rt_sigaction, rt_sigprocmask, rt_sigreturn, rt_sigsuspend
        134, 135, 139, 133,
        // memory: mprotect, madvise, mremap
        226, 233, 216,
        // stdio/io: writev, readv, lseek, fcntl, fstat
        66, 65, 62, 25, 80,
        // liblog -> logd unix socket: socket, sendto, sendmsg, setsockopt,
        // getsockopt
        198, 206, 211, 208, 209,
        // misc (bionic startup/allocator): getrandom, sched_getaffinity, ppoll,
        //                                  faccessat2, rseq, sched_yield
        278, 123, 73, 439, 293, 124,
    ];

    pub const TRACE_CORE: &[u32] = &[
        56, 48, 78, 34, 35, 38, 276, 221, 29, 203, 200, 49, 50, 17, 79, 291,
    ]; // openat, faccessat, readlinkat, mkdirat, unlinkat, renameat, renameat2, execve, ioctl, connect, bind, chdir, fchdir, getcwd, newfstatat, statx
    pub const TRACE_NS_EXTRA: &[u32] = &[174, 175, 160]; // getuid, geteuid, uname
}

#[cfg(any(test, target_arch = "arm"))]
pub mod arch_arm {
    pub const AUDIT_ARCH: u32 = 0x4000_0028; // AUDIT_ARCH_ARM
    pub const ARCH_NAME: &str = "arm";
    pub const ALLOW_FAST: &[u32] = &[3, 4, 6, 240, 263, 192, 91, 45, 346]; // read, write, close, futex, clock_gettime, mmap2, munmap, brk, epoll_pwait

    /// Phase 1 launcher + bionic libc runtime + liblog allow-list.
    ///
    /// Covers bionic process startup (which runs before `main`), the
    /// `kestrel_init` launcher itself, and liblog's logd socket traffic.
    /// Deny-by-default is preserved for process-control and privileged
    /// syscalls: `ptrace`, `clone`/`clone3`/`fork`/`vfork`, `mount`/`umount2`,
    /// `pivot_root`, `chroot`, `kexec_load`, `delete_module`, `init_module`,
    /// `bpf`, `perf_event_open`, `userfaultfd` and `seccomp` are never allowed.
    /// Filesystem-navigation and stat syscalls are deliberately left in
    /// `TRACE_CORE` so Phase 2 can rewrite/spoof them.
    ///
    /// lp32 keeps both the plain and 64-bit variants where bionic's
    /// `SYSCALLS.TXT` can emit either (`fcntl`/`fcntl64`, `fstat`/`fstat64`,
    /// `lseek`/`_llseek`). Numbers verified against NDK r27
    /// `arm-linux-androideabi/asm/unistd-eabi.h`.
    pub const LAUNCHER_ALLOW: &[u32] = &[
        // process: prctl, getppid, getpid, gettid, tgkill, exit, exit_group,
        //          set_tid_address, set_robust_list
        172, 64, 20, 224, 268, 1, 248, 256, 338,
        // signals: rt_sigaction, rt_sigprocmask, rt_sigreturn, rt_sigsuspend
        174, 175, 173, 179,
        // memory: mprotect, madvise, mremap
        125, 220, 163,
        // stdio/io: writev, readv, lseek + _llseek, fcntl + fcntl64,
        //           fstat + fstat64 (bionic lp32 issues the 64-bit variants)
        146, 145, 19, 140, 55, 221, 108, 197,
        // liblog -> logd unix socket: socket, sendto, sendmsg, setsockopt,
        // getsockopt
        281, 290, 296, 294, 295,
        // misc (bionic startup/allocator): getrandom, sched_getaffinity, ppoll,
        //                                  faccessat2, rseq, sched_yield
        384, 242, 336, 439, 398, 158,
    ];

    pub const TRACE_CORE: &[u32] = &[
        322, 334, 332, 323, 328, 329, 382, 11, 54, 283, 282, 12, 133, 183, 327, 397,
    ]; // openat, faccessat, readlinkat, mkdirat, unlinkat, renameat, renameat2, execve, ioctl, connect, bind, chdir, fchdir, getcwd, fstatat64, statx
    pub const TRACE_NS_EXTRA: &[u32] = &[199, 201, 122]; // getuid32, geteuid32, uname
}

#[cfg(any(test, target_arch = "x86_64"))]
pub mod arch_x86_64 {
    pub const AUDIT_ARCH: u32 = 0xC000_003E; // AUDIT_ARCH_X86_64
    pub const ARCH_NAME: &str = "x86_64";
    pub const ALLOW_FAST: &[u32] = &[0, 1, 3, 202, 228, 9, 11, 12, 281]; // read, write, close, futex, clock_gettime, mmap, munmap, brk, epoll_pwait

    /// Phase 1 launcher + bionic libc runtime + liblog allow-list.
    ///
    /// Covers bionic process startup (which runs before `main`), the
    /// `kestrel_init` launcher itself, and liblog's logd socket traffic.
    /// Deny-by-default is preserved for process-control and privileged
    /// syscalls: `ptrace`, `clone`/`clone3`/`fork`/`vfork`, `mount`/`umount2`,
    /// `pivot_root`, `chroot`, `kexec_load`, `delete_module`, `init_module`,
    /// `bpf`, `perf_event_open`, `userfaultfd` and `seccomp` are never allowed.
    /// Filesystem-navigation and stat syscalls are deliberately left in
    /// `TRACE_CORE` so Phase 2 can rewrite/spoof them.
    ///
    /// Numbers verified against NDK r27 `x86_64-linux-android/asm/unistd_64.h`.
    pub const LAUNCHER_ALLOW: &[u32] = &[
        // process: prctl, getppid, getpid, gettid, tgkill, exit, exit_group,
        //          set_tid_address, set_robust_list
        157, 110, 39, 186, 234, 60, 231, 218, 273,
        // signals: rt_sigaction, rt_sigprocmask, rt_sigreturn, rt_sigsuspend
        13, 14, 15, 130,
        // memory: mprotect, madvise, mremap
        10, 28, 25,
        // stdio/io: writev, readv, lseek, fcntl, fstat
        20, 19, 8, 72, 5,
        // liblog -> logd unix socket: socket, sendto, sendmsg, setsockopt,
        // getsockopt
        41, 44, 46, 54, 55,
        // misc (bionic startup/allocator): getrandom, sched_getaffinity, ppoll,
        //                                  faccessat2, rseq, sched_yield
        318, 204, 271, 439, 334, 24,
    ];

    pub const TRACE_CORE: &[u32] = &[
        257, 269, 267, 258, 263, 264, 316, 59, 16, 42, 49, 80, 81, 79, 262, 332,
    ]; // openat, faccessat, readlinkat, mkdirat, unlinkat, renameat, renameat2, execve, ioctl, connect, bind, chdir, fchdir, getcwd, newfstatat, statx
    pub const TRACE_NS_EXTRA: &[u32] = &[102, 107, 63]; // getuid, geteuid, uname
}

// The generic fallback mirrors the x86_64-shaped numbers, consistent with its
// pre-existing ALLOW_FAST = [0, 1, 3] and TRACE_CORE = [257, 59, 16].
#[cfg(any(
    test,
    not(any(target_arch = "aarch64", target_arch = "arm", target_arch = "x86_64"))
))]
pub mod arch_generic {
    pub const AUDIT_ARCH: u32 = 0xC000_003E;
    pub const ARCH_NAME: &str = "generic";
    pub const ALLOW_FAST: &[u32] = &[0, 1, 3];

    /// Phase 1 launcher + bionic libc runtime + liblog allow-list (x86_64-
    /// shaped numbers, like the rest of this fallback table).
    pub const LAUNCHER_ALLOW: &[u32] = &[
        // process: prctl, getppid, getpid, gettid, tgkill, exit, exit_group,
        //          set_tid_address, set_robust_list
        157, 110, 39, 186, 234, 60, 231, 218, 273,
        // signals: rt_sigaction, rt_sigprocmask, rt_sigreturn, rt_sigsuspend
        13, 14, 15, 130,
        // memory: mprotect, madvise, mremap
        10, 28, 25,
        // stdio/io: writev, readv, lseek, fcntl, fstat
        20, 19, 8, 72, 5,
        // liblog -> logd unix socket: socket, sendto, sendmsg, setsockopt,
        // getsockopt
        41, 44, 46, 54, 55,
        // misc (bionic startup/allocator): getrandom, sched_getaffinity, ppoll,
        //                                  faccessat2, rseq, sched_yield
        318, 204, 271, 439, 334, 24,
    ];

    pub const TRACE_CORE: &[u32] = &[257, 59, 16];
    pub const TRACE_NS_EXTRA: &[u32] = &[102, 63];
}

// Select the active architecture's table; every production path keeps using
// `arch::...` unchanged.
#[cfg(target_arch = "aarch64")]
pub use arch_aarch64 as arch;
#[cfg(target_arch = "arm")]
pub use arch_arm as arch;
#[cfg(target_arch = "x86_64")]
pub use arch_x86_64 as arch;
#[cfg(not(any(target_arch = "aarch64", target_arch = "arm", target_arch = "x86_64")))]
pub use arch_generic as arch;

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
/// - Fast-paths hot I/O & futex syscalls plus the Phase 1 launcher/bionic/liblog
///   `LAUNCHER_ALLOW` set with `SECCOMP_RET_ALLOW`.
/// - Traps filesystem, process, socket, and Binder `ioctl` syscalls with `SECCOMP_RET_TRACE`.
/// - Everything else falls through to `SECCOMP_RET_KILL_PROCESS` (deny-by-default).
pub fn build_filter(mode: i32) -> Vec<libc::sock_filter> {
    let mut allow_list: Vec<u32> = Vec::with_capacity(arch::ALLOW_FAST.len() + arch::LAUNCHER_ALLOW.len());
    allow_list.extend_from_slice(arch::ALLOW_FAST);
    allow_list.extend_from_slice(arch::LAUNCHER_ALLOW);
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

/// Maps a traced syscall number to a human-readable name by index into
/// `arch::TRACE_CORE`. The name table below is index-aligned with every
/// architecture's `TRACE_CORE`: appending an entry to a `TRACE_CORE` requires
/// appending the matching name at the same index here (see
/// `trace_core_names_stay_index_aligned`).
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
        // index 6 is renameat2; the resolver has always reported "renameat"
        // for both renameat and renameat2 (pre-existing label, preserved).
        "renameat"
    } else if code == arch::TRACE_CORE[7] {
        "execve"
    } else if code == arch::TRACE_CORE[8] {
        "ioctl"
    } else if code == arch::TRACE_CORE[9] {
        "connect"
    } else if code == arch::TRACE_CORE[10] {
        "bind"
    } else if code == arch::TRACE_CORE[11] {
        "chdir"
    } else if code == arch::TRACE_CORE[12] {
        "fchdir"
    } else if code == arch::TRACE_CORE[13] {
        "getcwd"
    } else if code == arch::TRACE_CORE[14] {
        // newfstatat (aarch64/x86_64) / fstatat64 (arm)
        "fstatat"
    } else if code == arch::TRACE_CORE[15] {
        "statx"
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

    /// The Phase 1 audit requires `ALLOW_FAST ∪ LAUNCHER_ALLOW` to cover
    /// bionic's pre-`main` startup, the launcher itself, and liblog on every
    /// architecture. Every number below was verified against the NDK r27
    /// headers by preprocessing them with
    /// `clang --target=<abi> -E -include asm/unistd.h`:
    ///   aarch64: sysroot/usr/include/asm-generic/unistd.h (pulled in by
    ///            aarch64-linux-android/asm/unistd.h)
    ///   arm:     sysroot/usr/include/arm-linux-androideabi/asm/unistd-eabi.h
    ///   x86_64:  sysroot/usr/include/x86_64-linux-android/asm/unistd_64.h
    ///
    /// Entries with more than one acceptable number mean the header defines
    /// several variants and bionic may issue either: on arm (lp32) bionic's
    /// `SYSCALLS.TXT` maps `fcntl -> fcntl64`, `fstat -> fstat64`, and ships
    /// both `lseek` and `_llseek`.
    ///
    /// Exceptions (symbols genuinely absent from an arch's headers): NONE.
    /// Every required symbol is defined on all three arches — notably
    /// `rt_sigsuspend` (bionic's `pause()` is `sigsuspend64()`, and bionic
    /// has no `pause` syscall stub at all) and `faccessat2` (bionic's
    /// `faccessat()` tries it first; seccomp KILL is not ENOSYS).
    #[test]
    fn launcher_allow_set_covers_required_syscalls() {
        const AARCH64_REQ: &[(&str, &[u32])] = &[
            ("prctl", &[167]),
            ("getppid", &[173]),
            ("getpid", &[172]),
            ("gettid", &[178]),
            ("tgkill", &[131]),
            ("exit", &[93]),
            ("exit_group", &[94]),
            ("rt_sigaction", &[134]),
            ("rt_sigprocmask", &[135]),
            ("rt_sigreturn", &[139]),
            ("set_tid_address", &[96]),
            ("set_robust_list", &[99]),
            ("mprotect", &[226]),
            ("madvise", &[233]),
            ("mremap", &[216]),
            ("writev", &[66]),
            ("lseek", &[62]),
            ("fcntl", &[25]),
            ("fstat", &[80]),
            ("rt_sigsuspend", &[133]),
            ("ppoll", &[73]),
            ("socket", &[198]),
            ("sendmsg", &[211]),
            ("faccessat2", &[439]),
            ("rseq", &[293]),
            ("sched_yield", &[124]),
        ];

        const ARM_REQ: &[(&str, &[u32])] = &[
            ("prctl", &[172]),
            ("getppid", &[64]),
            ("getpid", &[20]),
            ("gettid", &[224]),
            ("tgkill", &[268]),
            ("exit", &[1]),
            ("exit_group", &[248]),
            ("rt_sigaction", &[174]),
            ("rt_sigprocmask", &[175]),
            ("rt_sigreturn", &[173]),
            ("set_tid_address", &[256]),
            ("set_robust_list", &[338]),
            ("mprotect", &[125]),
            ("madvise", &[220]),
            ("mremap", &[163]),
            ("writev", &[146]),
            // plain or lp32 variant: __NR_lseek (19) / __NR__llseek (140)
            ("lseek", &[19, 140]),
            // plain or lp32 variant: __NR_fcntl (55) / __NR_fcntl64 (221)
            ("fcntl", &[55, 221]),
            // plain or lp32 variant: __NR_fstat (108) / __NR_fstat64 (197)
            ("fstat", &[108, 197]),
            ("rt_sigsuspend", &[179]),
            ("ppoll", &[336]),
            ("socket", &[281]),
            ("sendmsg", &[296]),
            ("faccessat2", &[439]),
            ("rseq", &[398]),
            ("sched_yield", &[158]),
        ];

        const X86_64_REQ: &[(&str, &[u32])] = &[
            ("prctl", &[157]),
            ("getppid", &[110]),
            ("getpid", &[39]),
            ("gettid", &[186]),
            ("tgkill", &[234]),
            ("exit", &[60]),
            ("exit_group", &[231]),
            ("rt_sigaction", &[13]),
            ("rt_sigprocmask", &[14]),
            ("rt_sigreturn", &[15]),
            ("set_tid_address", &[218]),
            ("set_robust_list", &[273]),
            ("mprotect", &[10]),
            ("madvise", &[28]),
            ("mremap", &[25]),
            ("writev", &[20]),
            ("lseek", &[8]),
            ("fcntl", &[72]),
            ("fstat", &[5]),
            ("rt_sigsuspend", &[130]),
            ("ppoll", &[271]),
            ("socket", &[41]),
            ("sendmsg", &[46]),
            ("faccessat2", &[439]),
            ("rseq", &[334]),
            ("sched_yield", &[24]),
        ];

        fn union(allow: &[u32], launcher: &[u32]) -> Vec<u32> {
            let mut v = allow.to_vec();
            v.extend_from_slice(launcher);
            v
        }

        fn assert_covers(arch: &str, union: &[u32], required: &[(&str, &[u32])]) {
            for (name, acceptable) in required {
                assert!(
                    acceptable.iter().any(|nr| union.contains(nr)),
                    "{arch}: ALLOW_FAST ∪ LAUNCHER_ALLOW is missing `{name}` \
                     (expected one of {acceptable:?})"
                );
            }
        }

        assert_covers(
            "aarch64",
            &union(arch_aarch64::ALLOW_FAST, arch_aarch64::LAUNCHER_ALLOW),
            AARCH64_REQ,
        );
        assert_covers(
            "arm",
            &union(arch_arm::ALLOW_FAST, arch_arm::LAUNCHER_ALLOW),
            ARM_REQ,
        );
        assert_covers(
            "x86_64",
            &union(arch_x86_64::ALLOW_FAST, arch_x86_64::LAUNCHER_ALLOW),
            X86_64_REQ,
        );
        // The generic fallback mirrors the x86_64 table by design.
        assert_covers(
            "generic",
            &union(arch_generic::ALLOW_FAST, arch_generic::LAUNCHER_ALLOW),
            X86_64_REQ,
        );
    }

    /// `syscall_name_for_nr` resolves names by *index* into `arch::TRACE_CORE`,
    /// so appending an entry to any `TRACE_CORE` without appending the matching
    /// name at the same index in `syscall_name_for_nr` shifts every later name.
    ///
    /// Only one arch module is selected as `arch` per compilation, so:
    ///  - the active arch's entries are driven through the production resolver
    ///    and must return their exact expected name, in order;
    ///  - every other compiled module must expose the same number of entries
    ///    (and no duplicates), which is what keeps index i meaning the same
    ///    conceptual syscall in each arch's table.
    #[test]
    fn trace_core_names_stay_index_aligned() {
        // Exact sequence `syscall_name_for_nr` must return for indices 0..15.
        // Index 6 is renameat2 but the resolver has always reported "renameat"
        // for both renameat and renameat2 (pre-existing label, preserved).
        const EXPECTED: [&str; 16] = [
            "openat",
            "faccessat",
            "readlinkat",
            "mkdirat",
            "unlinkat",
            "renameat",
            "renameat",
            "execve",
            "ioctl",
            "connect",
            "bind",
            "chdir",
            "fchdir",
            "getcwd",
            "fstatat",
            "statx",
        ];

        // Structural parity across the three real architectures.
        for (label, trace) in [
            ("aarch64", arch_aarch64::TRACE_CORE),
            ("arm", arch_arm::TRACE_CORE),
            ("x86_64", arch_x86_64::TRACE_CORE),
        ] {
            assert_eq!(
                trace.len(),
                EXPECTED.len(),
                "{label}: TRACE_CORE length drifted from the name table"
            );
            let mut sorted = trace.to_vec();
            sorted.sort_unstable();
            sorted.dedup();
            assert_eq!(
                sorted.len(),
                trace.len(),
                "{label}: duplicate syscall number in TRACE_CORE"
            );
        }

        // The generic fallback intentionally keeps its original 3 entries; on a
        // generic target its entries must still resolve through the first 3
        // names of the shared table.
        if arch::ARCH_NAME == "generic" {
            assert_eq!(arch::TRACE_CORE.len(), 3);
        } else {
            assert_eq!(arch::TRACE_CORE.len(), EXPECTED.len());
        }

        // Active arch: every entry must resolve through the production
        // resolver to the exact expected name for its index.
        for (i, &nr) in arch::TRACE_CORE.iter().enumerate() {
            let name = syscall_name_for_nr(u64::from(nr));
            assert!(!name.is_empty(), "TRACE_CORE[{i}] ({nr}) has an empty name");
            assert_ne!(
                name, "syscall",
                "TRACE_CORE[{i}] ({nr}) is unnamed — TRACE_CORE and syscall_name_for_nr drifted apart"
            );
            assert_eq!(
                name, EXPECTED[i],
                "TRACE_CORE[{i}] ({nr}) resolved to the wrong name (index shift)"
            );
        }
    }
}
