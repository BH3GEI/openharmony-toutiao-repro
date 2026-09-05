/*
 * libwestlake_stackgrow.so  (v2)
 *
 * 1) Original behaviour: pre-touch the main thread stack up to RLIMIT_STACK so
 *    musl's lazy stack growth never bites the forked app children.
 *
 * 2) New: ART's Thread::InitStackHwm() derives tlsPtr_.stack_begin/stack_end
 *    from pthread_getattr_np(pthread_self()).  On an ffrt worker the JNI
 *    AttachCurrentThread happens inside a coroutine (CoStartEntry), i.e. the
 *    CPU is running on an ffrt-allocated co-stack while pthread_getattr_np
 *    still reports the *worker pthread's* stack.  ART then asserts
 *        CHECK_GT(FindStackTop(), tlsPtr_.stack_end)
 *    and aborts, because the current frame lies far below the region it was
 *    told about.  We interpose pthread_getattr_np: when the caller asks about
 *    itself and the returned region does not contain the caller's frame, we
 *    substitute the mapping that actually does.
 */
#define _GNU_SOURCE
#include <pthread.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/resource.h>

#define TOUCH_FRAME 8192

static int (*g_real_getattr_np)(pthread_t, pthread_attr_t *);
static int (*g_ffrt_costack)(void **, size_t *);
static int g_ffrt_costack_looked_up;
static int g_log_budget = 8;

/* Leave this much of the low end of a coroutine mapping alone when we have to
 * fall back on /proc/self/maps: ffrt keeps its CoRoutine header (including the
 * stack canary CoStackCheck() validates) at the base of the mapping, so ART
 * must never be told it may grow down into it. */
#define COSTACK_FALLBACK_RESERVE (64u * 1024u)

/* ---- 1. main-thread stack pre-touch -------------------------------------- */

static volatile char g_touch_sink;

/*
 * musl's pthread_getattr_np() reports the main stack as the *currently mapped*
 * extent (it probes downward with mremap), so ART sees whatever the stack has
 * actually grown to.  Walk it down in one-page-ish frames so the kernel faults
 * the whole region in.  The frame must be live after the recursive call or the
 * compiler turns this into a tail-call loop that reuses a single frame and
 * touches nothing.
 */
__attribute__((noinline, optnone))
static void touch_down(size_t remaining)
{
    volatile char frame[TOUCH_FRAME];
    frame[0] = (char)remaining;
    frame[TOUCH_FRAME - 1] = (char)remaining;
    if (remaining > TOUCH_FRAME) {
        touch_down(remaining - TOUCH_FRAME);
    }
    g_touch_sink = (char)(frame[0] + frame[TOUCH_FRAME - 1]);
}

__attribute__((constructor)) static void wl_stackgrow_init(void)
{
    struct rlimit rl;
    size_t rlimit_kb = 0;
    size_t target = 0;

    if (getrlimit(RLIMIT_STACK, &rl) == 0 && rl.rlim_cur != RLIM_INFINITY) {
        rlimit_kb = (size_t)(rl.rlim_cur / 1024);
        if (rl.rlim_cur > (256u * 1024u)) {
            target = (size_t)rl.rlim_cur - (128u * 1024u);
        }
    }
    if (target > 0) {
        touch_down(target);
    }
    fprintf(stderr, "[wl_stackgrow] main-thread stack pre-touched to %zu KB (rlimit %zu KB)\n",
            target / 1024, rlimit_kb);
}

/* ---- 2. coroutine-aware pthread_getattr_np ------------------------------- */

static int find_vma(uintptr_t addr, uintptr_t *lo_out, uintptr_t *hi_out)
{
    int fd = open("/proc/self/maps", O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        return 0;
    }

    char buf[4096];
    char line[512];
    size_t linelen = 0;
    ssize_t n;
    int found = 0;

    while (!found && (n = read(fd, buf, sizeof(buf))) > 0) {
        for (ssize_t i = 0; i < n; i++) {
            if (buf[i] != '\n') {
                if (linelen < sizeof(line) - 1) {
                    line[linelen++] = buf[i];
                }
                continue;
            }
            line[linelen] = '\0';
            linelen = 0;

            unsigned long lo = 0, hi = 0;
            if (sscanf(line, "%lx-%lx", &lo, &hi) == 2 &&
                addr >= (uintptr_t)lo && addr < (uintptr_t)hi) {
                *lo_out = (uintptr_t)lo;
                *hi_out = (uintptr_t)hi;
                found = 1;
                break;
            }
        }
    }
    close(fd);
    return found;
}

int pthread_getattr_np(pthread_t t, pthread_attr_t *a)
{
    if (g_real_getattr_np == NULL) {
        g_real_getattr_np = (int (*)(pthread_t, pthread_attr_t *))
            dlsym(RTLD_NEXT, "pthread_getattr_np");
        if (g_real_getattr_np == NULL) {
            return ENOSYS;
        }
    }

    int rc = g_real_getattr_np(t, a);
    if (rc != 0 || !pthread_equal(t, pthread_self())) {
        return rc;
    }

    void *base = NULL;
    size_t size = 0;
    if (pthread_attr_getstack(a, &base, &size) != 0) {
        return rc;
    }

    uintptr_t sp = (uintptr_t)__builtin_frame_address(0);
    uintptr_t lo = (uintptr_t)base;
    uintptr_t hi = lo + size;
    if (sp >= lo && sp < hi) {
        return rc;   /* consistent: a plain pthread, leave it alone */
    }

    /* Preferred source of truth: ask ffrt for the coroutine's own stack. */
    if (!g_ffrt_costack_looked_up) {
        g_ffrt_costack_looked_up = 1;
        g_ffrt_costack = (int (*)(void **, size_t *))
            dlsym(RTLD_DEFAULT, "ffrt_get_current_coroutine_stack");
    }

    uintptr_t vlo = 0, vhi = 0;
    const char *src = "maps";

    if (g_ffrt_costack != NULL) {
        void *caddr = NULL;
        size_t csize = 0;
        if (g_ffrt_costack(&caddr, &csize) && caddr != NULL && csize > 0) {
            uintptr_t clo = (uintptr_t)caddr;
            uintptr_t chi = clo + csize;
            if (sp >= clo && sp < chi) {
                vlo = clo;
                vhi = chi;
                src = "ffrt";
            }
        }
    }

    if (vlo == 0) {
        if (!find_vma(sp, &vlo, &vhi)) {
            return rc;
        }
        /* Keep ART off ffrt's CoRoutine header at the base of the mapping. */
        if (vhi - vlo > COSTACK_FALLBACK_RESERVE) {
            vlo += COSTACK_FALLBACK_RESERVE;
        }
        if (sp < vlo) {
            return rc;
        }
    }

    pthread_attr_setstack(a, (void *)vlo, (size_t)(vhi - vlo));

    if (g_log_budget > 0) {
        g_log_budget--;
        fprintf(stderr,
                "[wl_stackgrow] costack fixup(%s) tid=%d sp=%p pthread=[%p,%p) -> real=[%p,%p) %zu KB\n",
                src, (int)gettid(), (void *)sp, (void *)lo, (void *)hi,
                (void *)vlo, (void *)vhi, (size_t)(vhi - vlo) / 1024);
    }
    return rc;
}

/* ---- 3. ART fatal-message capture ---------------------------------------
 *
 * When the ART runtime aborts during Runtime::Init (bad boot image, BCP
 * checksum mismatch, ...) the child dies before AppSpawnXInit has redirected
 * stderr to adapter_child_<pid>.stderr, and this board has no tombstoned, so
 * LOG(FATAL) text is lost entirely -- DfxSignalHandler only reports "signo(6)".
 * We are already LD_PRELOADed ahead of libart, so intercept the three places
 * the message can surface and tee them to a file the child is allowed to
 * write.  Purely diagnostic; every hook chains to the real implementation.
 */

#include <stdarg.h>
#include <sys/stat.h>

#define WL_FATAL_LOG "/data/service/el1/public/appspawnx/wl_artfatal.log"

static int g_fatal_fd = -1;

static void wl_fatal_open(void)
{
    if (g_fatal_fd >= 0) return;
    g_fatal_fd = open(WL_FATAL_LOG, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0666);
}

/* Open per call.  The cached descriptor cannot be trusted: AppSpawnXInit
 * re-plumbs the child's fds after we run, so by crash time g_fatal_fd may name
 * something else or nothing at all, and the writes vanish silently. */
static void wl_fatal_raw(const char *buf, size_t len)
{
    int fd = open(WL_FATAL_LOG, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0666);
    if (fd < 0) return;
    (void)!write(fd, buf, len);
    close(fd);
}

static void wl_fatal_write(const char *tag, const char *msg)
{
    char line[2600];
    int n = snprintf(line, sizeof(line), "\n[wl-fatal pid=%d tid=%d] %s: %s\n",
                     (int)getpid(), (int)gettid(), tag, msg ? msg : "");
    if (n > 0) wl_fatal_raw(line, (size_t)(n < (int)sizeof(line) ? n : (int)sizeof(line) - 1));
}

/* Point fd 2 at the capture file for the early window.  AppSpawnXInit
 * re-redirects it to the per-child stderr later, which is fine -- by then the
 * runtime is up and its own logging works. */
__attribute__((constructor(101)))
static void wl_fatal_init(void)
{
    wl_fatal_open();
    if (g_fatal_fd >= 0) {
        wl_fatal_write("boot", "preload active, stderr teed here until AppSpawnXInit takes over");
        dup2(g_fatal_fd, 2);
    }
}

/* ART calls this immediately before abort(); it carries the full
 * "Check failed: ..." / "Could not ..." text. */
void android_set_abort_message(const char *msg)
{
    static void (*real)(const char *);
    wl_fatal_write("abort_message", msg);
    if (!real) real = (void (*)(const char *))dlsym(RTLD_NEXT, "android_set_abort_message");
    if (real) real(msg);
}

/* libart's LOG(FATAL) text never reaches hilog on this adapter (its liblog
 * resolves inside the sealed.child namespace, out of reach of LD_PRELOAD), so
 * recover the abort site instead: walk the aarch64 frame-pointer chain and
 * print each return address as module+offset, resolvable offline with
 * llvm-symbolizer against the on-board libart.so. */
struct wl_frame { struct wl_frame *fp; void *lr; };

#define WL_ABORT_PARK_SECONDS 600

void wl_dump_maps_for(uintptr_t pc, char *out, size_t outsz)
{
    int fd; static char buf[512*1024]; ssize_t n, total = 0; char *line, *save;
    out[0] = '\0';
    fd = open("/proc/self/maps", O_RDONLY);
    if (fd < 0) return;
    /* /proc/self/maps for an ART child runs to hundreds of KB; a short read
     * silently truncates before libart.so and every frame resolves to "". */
    while ((n = read(fd, buf + total, sizeof(buf) - 1 - (size_t)total)) > 0) {
        total += n;
        if ((size_t)total >= sizeof(buf) - 1) break;
    }
    close(fd);
    if (total <= 0) return;
    buf[total] = '\0';
    for (line = strtok_r(buf, "\n", &save); line; line = strtok_r(NULL, "\n", &save)) {
        unsigned long lo, hi; char perms[8]; unsigned long off;
        char path[512]; path[0] = '\0';
        if (sscanf(line, "%lx-%lx %7s %lx %*s %*s %511s", &lo, &hi, perms, &off, path) < 4) continue;
        if (pc >= lo && pc < hi) {
            snprintf(out, outsz, "%s+0x%lx", path[0] ? path : "?", (unsigned long)(pc - lo + off));
            return;
        }
    }
}

void abort(void)
{
    static void (*real)(void);
    struct wl_frame *f;
    uintptr_t lo_guard = 0;
    char line[640], where[560];
    int i, n;

    wl_fatal_write("abort", "abort() called -- aarch64 frame-pointer unwind follows");
    f = (struct wl_frame *)__builtin_frame_address(0);
    for (i = 0; i < 40 && f; i++) {
        uintptr_t pc = (uintptr_t)f->lr;
        if (pc < 0x1000) break;
        wl_dump_maps_for(pc, where, sizeof(where));
        n = snprintf(line, sizeof(line), "  #%02d  pc=0x%lx  %s\n", i, (unsigned long)pc, where);
        if (n > 0) wl_fatal_raw(line, (size_t)n);
        /* frame pointers must climb monotonically or we are off the rails */
        if ((uintptr_t)f->fp <= (uintptr_t)f || (uintptr_t)f->fp - (uintptr_t)f > (16u << 20)) break;
        if (lo_guard && (uintptr_t)f->fp <= lo_guard) break;
        lo_guard = (uintptr_t)f;
        f = f->fp;
    }

    /* Do NOT die here.  When the ART runtime aborts during Runtime::Init the
     * app has not called attachApplication yet, so AMS never learns the
     * process is gone: its AppRunningRecord stays pinned in state BEGIN and
     * every later `aa start` is silently dropped, with only a reboot to clear
     * it (`aa force-stop` refuses because kill(pid) gives ESRCH).  Parking the
     * thread keeps the pid alive so `aa force-stop` can reap the record
     * normally.  Bounded so a forgotten child cleans itself up. */
    wl_fatal_write("abort", "parked -- run: aa force-stop com.ss.android.article.news");
    for (i = 0; i < WL_ABORT_PARK_SECONDS; i++) sleep(1);

    if (!real) real = (void (*)(void))dlsym(RTLD_NEXT, "abort");
    if (real) real();
    _exit(134);
}

/* liblog path: ART's LOG(FATAL)/LOG(ERROR) land here on this adapter.
 *
 * FATAL only.  This adapter's libart is built with very chatty ERROR-level
 * class_linker diagnostics ([VTLEN]/[IFACE-BITS]/... on *every* class load), so
 * teeing WARN-and-above produced a 1.26 GB log and put an open/write/close on a
 * multi-hundred-MB file into the class-loading hot path -- which badly skewed
 * startup timing.  We only ever needed the fatal text. */
#define WL_PRIO_ERROR 7

int __android_log_write(int prio, const char *tag, const char *text)
{
    static int (*real)(int, const char *, const char *);
    if (prio >= WL_PRIO_ERROR) wl_fatal_write(tag ? tag : "?", text);
    if (!real) real = (int (*)(int, const char *, const char *))dlsym(RTLD_NEXT, "__android_log_write");
    return real ? real(prio, tag, text) : 0;
}

int __android_log_print(int prio, const char *tag, const char *fmt, ...)
{
    static int (*real)(int, const char *, const char *, const char *);
    char buf[2048];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (prio >= WL_PRIO_ERROR) wl_fatal_write(tag ? tag : "?", buf);
    /* Chain through the 3-arg writer: the real vararg entry cannot be
     * forwarded portably and the text is already formatted. */
    if (!real) real = (int (*)(int, const char *, const char *, const char *))dlsym(RTLD_NEXT, "__android_log_write");
    return real ? ((int (*)(int, const char *, const char *))real)(prio, tag, buf) : 0;
}

/* libbase routes every LOG() through these two liblog entry points (see
 * `llvm-nm --undefined-only libbase.so`), not __android_log_print, which is why
 * ART's "Check failed: ..." text never showed up.  Tee them. */
struct wl_log_message {
    size_t struct_size;
    int32_t buffer_id;
    int32_t priority;
    const char *tag;
    const char *file;
    uint32_t line;
    const char *message;
};

static void wl_tee_log_message(const struct wl_log_message *m)
{
    char hdr[256];
    if (!m || m->priority < WL_PRIO_ERROR) return;   /* FATAL only -- see note above */
    snprintf(hdr, sizeof(hdr), "prio=%d tag=%s %s:%u",
             (int)m->priority, m->tag ? m->tag : "?",
             m->file ? m->file : "?", (unsigned)m->line);
    wl_fatal_write(hdr, m->message);
}

void __android_log_write_log_message(struct wl_log_message *m)
{
    static void (*real)(struct wl_log_message *);
    wl_tee_log_message(m);
    if (!real) real = (void (*)(struct wl_log_message *))dlsym(RTLD_NEXT, "__android_log_write_log_message");
    if (real) real(m);
}

void __android_log_logd_logger(const struct wl_log_message *m)
{
    static void (*real)(const struct wl_log_message *);
    wl_tee_log_message(m);
    if (!real) real = (void (*)(const struct wl_log_message *))dlsym(RTLD_NEXT, "__android_log_logd_logger");
    if (real) real(m);
}

/* ---- 4. native crash capture -------------------------------------------
 * The board has no processdump, so a SIGSEGV in the app is reported only as
 * "exit with signal:11" by appspawn.  Install a chained handler (we are
 * preloaded ahead of libart, so ART's own fault handler sits above us and only
 * reaches here for faults it does not claim) and record the faulting pc plus a
 * frame-pointer unwind. */
#include <signal.h>
#include <ucontext.h>

static struct sigaction g_prev_sa[8];

static void wl_crash_handler(int sig, siginfo_t *si, void *ctx)
{
    ucontext_t *uc = (ucontext_t *)ctx;
    char line[700], where[560];
    struct wl_frame *f;
    uintptr_t pc, lo_guard = 0;
    int i, n;

    snprintf(line, sizeof(line), "signal=%d code=%d addr=%p tid=%d",
             sig, si ? si->si_code : -1, si ? si->si_addr : NULL, (int)gettid());
    wl_fatal_write("CRASH", line);

    if (uc) {
        pc = (uintptr_t)uc->uc_mcontext.pc;
        wl_dump_maps_for(pc, where, sizeof(where));
        n = snprintf(line, sizeof(line), "  PC   pc=0x%lx  %s\n", (unsigned long)pc, where);
        if (n > 0) wl_fatal_raw(line, (size_t)n);
        pc = (uintptr_t)uc->uc_mcontext.regs[30];          /* lr */
        wl_dump_maps_for(pc, where, sizeof(where));
        n = snprintf(line, sizeof(line), "  LR   pc=0x%lx  %s\n", (unsigned long)pc, where);
        if (n > 0) wl_fatal_raw(line, (size_t)n);
        f = (struct wl_frame *)uc->uc_mcontext.regs[29];   /* fp */
        for (i = 0; i < 40 && f; i++) {
            pc = (uintptr_t)f->lr;
            if (pc < 0x1000) break;
            wl_dump_maps_for(pc, where, sizeof(where));
            n = snprintf(line, sizeof(line), "  #%02d  pc=0x%lx  %s\n", i, (unsigned long)pc, where);
            if (n > 0) wl_fatal_raw(line, (size_t)n);
            if ((uintptr_t)f->fp <= (uintptr_t)f || (uintptr_t)f->fp - (uintptr_t)f > (16u << 20)) break;
            if (lo_guard && (uintptr_t)f->fp <= lo_guard) break;
            lo_guard = (uintptr_t)f;
            f = f->fp;
        }
    }

    /* chain to whoever was installed before us, else default */
    if (sig < 8 && g_prev_sa[sig].sa_sigaction &&
        (g_prev_sa[sig].sa_flags & SA_SIGINFO)) {
        g_prev_sa[sig].sa_sigaction(sig, si, ctx);
        return;
    }
    signal(sig, SIG_DFL);
    raise(sig);
}

__attribute__((constructor(102)))
static void wl_crash_init(void)
{
    struct sigaction sa;
    int sigs[] = { SIGSEGV, SIGBUS, SIGILL, SIGFPE };
    unsigned k;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = wl_crash_handler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);
    for (k = 0; k < sizeof(sigs) / sizeof(sigs[0]); k++)
        sigaction(sigs[k], &sa, &g_prev_sa[sigs[k]]);
}

/* ---- 5. bypass musl's fdsan on close() ----------------------------------
 *
 * ByteDance's libnpth.so (crash/APM monitor) installs a bionic-shaped fdsan
 * error callback.  musl's close() routes through fdsan_close_with_tag, which
 * calls that callback with a different stack contract, and NPTH smashes its
 * own canary:
 *
 *   Tid:...,Name:npth-dumper-thr / listener_thread
 *   #00 ld-musl-aarch64.so.1(__stack_chk_fail+4)
 *   #01 libnpth.so+0x13324
 *   #02 ld-musl-aarch64.so.1(fdsan_close_with_tag+744)
 *   #04 ld-musl-aarch64.so.1(close+20)
 *
 * killing the process ~25s into every run.  Deleting libnpth.so does not help:
 * the adapter's "app_librarian" re-extracts it from base.apk on each launch.
 * Go straight to the syscall so the fdsan path -- and therefore NPTH's
 * callback -- is never entered.  fdsan is only a double-close debugging aid.
 */
#include <sys/syscall.h>

int close(int fd)
{
    return (int)syscall(SYS_close, fd);
}
