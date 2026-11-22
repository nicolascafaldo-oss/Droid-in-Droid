/*
 * kestrel_init.c — Phase 1 bootstrap launcher.
 *
 * Safer variant: it surveys the guest rootfs, logs one line per probe path,
 * then pauses forever. It does NOT execute any guest code.
 *
 * The parent-death signal (PR_SET_PDEATHSIG) is cleared by the kernel across
 * execve, so this program must be re-exec'd from an already-exec'd context so
 * that PR_SET_PDEATHSIG is re-established here (the supervisor set it before
 * forking).
 *
 * Exit codes:
 *   64 - usage error (missing/empty arguments)
 *   65 - bad rootfs (contains "..", unresolvable, or chdir failed)
 *         also: supervisor already gone at startup (getppid() == 1)
 * Reserved for a later phase (not implemented in Phase 1):
 *   66 - invalid ELF
 *   67 - dynamic ELF rejected
 *   68 - mmap failure
 *   69 - stack failure
 */

#define _GNU_SOURCE
#include <sys/prctl.h>
#include <sys/types.h>
#include <signal.h>
#include <unistd.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <limits.h>
#include <errno.h>
#include <dirent.h>
#include <stdarg.h>
#include <android/log.h>

// Diagnostics logger. Tag must be exactly "KestrelInit" so
// `adb logcat -s KestrelInit` picks every line up; also mirrors to
// stdout+stderr so nothing is lost if logd is unavailable.
static void klog(const char *fmt, ...)
{
    char buf[512];
    va_list ap;

    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    __android_log_print(ANDROID_LOG_INFO, "KestrelInit", "%s", buf);
    /* also mirror to stdout+stderr so nothing is lost if logd is unavailable */
    printf("%s\n", buf);
    fprintf(stderr, "%s\n", buf);
    fflush(stdout);
    fflush(stderr);
}

// Return 1 if the raw path contains ".." as a path component.
static int has_dotdot_component(const char *path)
{
    size_t n;

    if (strcmp(path, "..") == 0)
        return 1;
    if (strncmp(path, "../", 3) == 0)
        return 1;

    n = strlen(path);
    if (n >= 3 && strcmp(path + n - 3, "/..") == 0)
        return 1;

    if (strstr(path, "/../") != NULL)
        return 1;

    return 0;
}

int main(int argc, char **argv)
{
    static const char *probes[] = {
        "init",
        "system/build.prop",
        "system/bin/sh",
        "system/bin/toybox",
        "system/lib64",
        "dev",
        "data",
        "proc",
        "sys",
        "system/framework",
        "system/bin/app_process64",
        "system/bin/servicemanager",
    };
    const char *rootfs;
    const char *vm_id;
    char resolved[PATH_MAX];
    size_t i;

    // Validate argv FIRST: argv[1] = rootfs path, argv[2] = vm_id. The
    // PDEATHSIG block below logs vm_id, so validation must precede it.
    if (argc < 3 || argv[1] == NULL || argv[2] == NULL ||
        argv[1][0] == '\0' || argv[2][0] == '\0') {
        klog("KestrelInit: usage: %s <rootfs> <vm_id>",
             argc > 0 ? argv[0] : "kestrel_init");
        return 64;
    }
    rootfs = argv[1];
    vm_id = argv[2];

    // Re-establish the parent-death signal; the kernel cleared it across execve.
    prctl(PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0);

    /* If the supervisor already died between fork() and now, we have just set
     * PDEATHSIG against init and would never be reaped. Bail out. */
    if (getppid() == 1) {
        klog("KestrelInit: supervisor already gone (ppid=1) vm=%s -> exit 65", vm_id);
        return 65;
    }

    // Refuse any ".." path component in the raw path, then canonicalize.
    if (has_dotdot_component(rootfs)) {
        klog("KestrelInit: refusing '..' in rootfs path: %s", rootfs);
        return 65;
    }
    if (realpath(rootfs, resolved) == NULL) {
        klog("KestrelInit: realpath(%s) failed: %s",
             rootfs, strerror(errno));
        return 65;
    }

    if (chdir(resolved) != 0) {
        klog("KestrelInit: chdir(%s) failed: %s",
             resolved, strerror(errno));
        return 65;
    }

    // Survey the rootfs. This runs unconditionally: it drives the seccomp
    // "Syscall Traps" counter and satisfies the exit check.
    for (i = 0; i < sizeof(probes) / sizeof(probes[0]); i++) {
        const char *p = probes[i];
        int present = (access(p, F_OK) == 0) ? 1 : 0;

        klog("KestrelInit: survey root=%s vm=%s path=%s present=%d",
             resolved, vm_id, p, present);
    }

    // Every klog() call must happen BEFORE the fd close loop below: liblog
    // owns a socket fd >= 3, and closing it would break later logging.
    // There is no later logging by design — keep it that way.
    klog("KestrelInit: survey complete vm=%s -> pause()", vm_id);

    // Drop inherited file descriptors >= 3; keep 0/1/2.
    for (int fd = 3; fd < 1024; fd++)
        close(fd);

    // No guest ELF execution in Phase 1: stay alive forever so the traced
    // process remains observable.
    while (1)
        pause();

    return 0; // unreachable
}
