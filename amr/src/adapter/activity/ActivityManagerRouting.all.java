package adapter.activity;

import android.app.ContentProviderHolder;
import android.app.IApplicationThread;
import android.content.pm.ProviderInfo;
import android.os.RemoteException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Provider-aware / component-aware IActivityManager for the Westlake adapter.
 *
 * Two AMS behaviours the plain stub was missing, both of which the Toutiao plugin
 * frameworks (Mira, Tinker) depend on during Application startup:
 *
 * 1. getContentProvider() for an authority the app itself declares.
 *    AOSP builds the Application object before installContentProviders() runs, but apps
 *    call ContentResolver on their own authorities from Application.<init>/
 *    attachBaseContext.  The stub answered those with a synthetic OH DataShare bridge;
 *    ActivityThread registered that bridge in mProviderMap, so when the real provider
 *    was installed moments later AOSP saw the authority taken ("already published") and
 *    dropped it -- the dead bridge owned the authority forever.  Hand back the real
 *    ProviderInfo with provider == null instead, and ActivityThread.installProvider()
 *    instantiates it in-process, exactly as AMS does on a device.
 *
 * 2. ComponentInfo.processName on query results.
 *    The adapter's PackageManager leaves it null; on a device it always defaults to the
 *    application's process.  Mira keys a TreeMap by it
 *    (PluginActivityManagerProvider.c -> TreeMap.get(activityInfo.processName)), so a
 *    null there throws NPE and kills the thread the main thread is waiting on.
 *    Wrap ActivityThread.sPackageManager and fill the field in when it comes back null.
 */
public class ActivityManagerRouting extends ActivityManagerAdapter {

    private static volatile boolean sPmWrapped;
    private static volatile boolean sBackFillReported;

    public ActivityManagerRouting() {
        super();
        System.err.println("[WL-AMR] provider-aware IActivityManager active");
        ensureStubServices();
    }

    @Override
    public void attachApplication(IApplicationThread app, long startSeq)
            throws RemoteException {
        ensureStubServices();
        ensurePackageManagerWrapped();
        loadVelocityTrackerShim();
        installWebViewGuard();
        loadNativeShims();
        startTlsBootstrap();
        startViewTreeDumper();
        startInputPump();
        super.attachApplication(app, startSeq);
    }

    /* ------------------------------------------------------------------
     * TLS
     *
     * The adapter has no TLS at all: core-oj.jar carries the javax.net.ssl API
     * but not one line of sun.security.ssl, bouncycastle.jar is AOSP's crypto-only
     * bcprov, and conscrypt -- which security.properties still names as
     * security.provider.1/4 and as ssl.SocketFactory.provider -- is absent, classes
     * and libjavacrypto.so alike.  So SSLContext.getInstance("TLS") has no
     * implementation to find, every TTNet DoConnect dies, and the feed stays empty.
     *
     * westlake.tls.TlsBootstrap installs upstream BouncyCastle's pure-Java JSSE as
     * a real provider.  It lives in a separate dex loaded from disk rather than
     * being merged in here: this jar is reflectively injected as the adapter's
     * IActivityManager and wants to stay small, and keeping the TLS stack in its
     * own file means it can be iterated on without rebuilding this one.
     * ------------------------------------------------------------------ */

    /* ------------------------------------------------------------------
     * ALooper shim
     *
     * Toutiao's real network engine is cronet (libsscronet.so).  It never loads
     * here, and OpenHarmony's musl linker says exactly why:
     *
     *   MUSL-LDSO: relocating failed: symbol not found.
     *     dso=.../libsscronet.so  s=ALooper_prepare
     *
     * This adapter's libandroid.so exports 750 symbols but not one ALooper_*, and
     * libsscronet.so imports five of them (prepare/acquire/release/addFd/removeFd).
     * So System.loadLibrary("sscronet") throws, TTNet counts the failure in
     * chromium_boot_failures, and after a few tries disables cronet permanently and
     * falls back to okhttp -- which is why configuration traffic works over the TLS
     * bridge while the article feed never arrives.
     *
     * libwlalooper.so supplies those five by forwarding to android::Looper in
     * libutils, i.e. the same looper the Java MessageQueue drives.
     *
     * Loading it from here, rather than via LD_PRELOAD, is deliberate: preloading
     * put it into appspawn-x itself, which then failed to start and no app could
     * spawn at all.  Doing it in-process is scoped to this app, needs no config
     * change and no reboot, and cannot take the runtime down with it.
     * ------------------------------------------------------------------ */

    /* ------------------------------------------------------------------
     * SQLite probe
     *
     * The app has launched many times and has not one database file, and
     * liboh_android_runtime.so registers the android.database.sqlite JNI entry
     * points while containing no SQLite engine at all (zero sqlite3_* symbols,
     * zero PRAGMA/SQLITE_ strings, and no libsqlite*.so anywhere on the board).
     *
     * That makes "AppLog cannot create its event-queue database, so device
     * registration never runs, so device_id stays empty and the feed is never
     * requested" a strong inference -- but it is still an inference.  This turns
     * it into a measurement: open a database in-process and report exactly what
     * comes back, cause chain included, since an UnsatisfiedLinkError from the
     * missing engine would be buried there.
     *
     * Gated behind a marker file; costs nothing on a normal run.
     * ------------------------------------------------------------------ */

    private static final String SQLITE_PROBE_MARKER = "/data/local/tmp/wl-sqlite-probe";

    private static void probeSqlite() {
        if (!new java.io.File(SQLITE_PROBE_MARKER).isFile()) return;
        String path = "/data/app/el2/100/base/com.ss.android.article.news/databases/wl_probe.db";
        try {
            new java.io.File(path).getParentFile().mkdirs();
        } catch (Throwable ignored) { }
        try {
            Class<?> db = Class.forName("android.database.sqlite.SQLiteDatabase");
            Class<?> factory = Class.forName(
                    "android.database.sqlite.SQLiteDatabase$CursorFactory");
            Method m = db.getMethod("openOrCreateDatabase", String.class, factory);
            Object handle = m.invoke(null, path, null);
            System.err.println("[WL-SQLITE] openOrCreateDatabase OK -> " + handle);
            try {
                db.getMethod("execSQL", String.class)
                  .invoke(handle, "CREATE TABLE IF NOT EXISTS wl_probe(x INTEGER)");
                System.err.println("[WL-SQLITE] CREATE TABLE OK -- SQLite is functional");
            } catch (Throwable t) {
                System.err.println("[WL-SQLITE] execSQL failed: " + unwrap(t));
            }
        } catch (Throwable t) {
            System.err.println("[WL-SQLITE] openOrCreateDatabase FAILED: " + unwrap(t));
        }
        // CursorWindow is a separate native surface.  nativeExecuteForCursorWindow
        // has to write rows into one, and liboh_android_runtime.so exports only the
        // registration entry point, not the android::CursorWindow accessors -- so
        // whether CursorWindow works decides whether a SQLite shim can hand results
        // back through it or has to own that side too.
        try {
            Class<?> cw = Class.forName("android.database.CursorWindow");
            Object w = cw.getConstructor(String.class).newInstance("wl_probe");
            Object ok = cw.getMethod("setNumColumns", int.class).invoke(w, 1);
            System.err.println("[WL-CURSORWIN] CursorWindow works (setNumColumns -> " + ok + ")");
            try { cw.getMethod("close").invoke(w); } catch (Throwable ignored) { }
        } catch (Throwable t) {
            System.err.println("[WL-CURSORWIN] CursorWindow FAILED: " + unwrap(t));
        }
    }

    /** Full cause chain: the interesting failure is usually two levels down. */
    private static String unwrap(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable c = t;
        for (int i = 0; c != null && i < 6; i++) {
            if (i > 0) sb.append("  <- caused by: ");
            sb.append(c.getClass().getName()).append(": ").append(c.getMessage());
            if (c instanceof java.lang.reflect.InvocationTargetException) {
                c = ((java.lang.reflect.InvocationTargetException) c).getTargetException();
            } else {
                Throwable n = c.getCause();
                c = (n == c) ? null : n;
            }
        }
        return sb.toString();
    }

    private static volatile boolean sShimsTried;

    /**
     * Candidate locations, most specific first.
     *
     * The app's own native library directories come first, and that ordering is
     * the whole trick: this adapter's native loader only registers a linker
     * namespace ("native dependency domain") for the app's own library paths, so
     * loading the same file from /system/android/lib64 fails with
     *     UnsatisfiedLinkError: no native dependency domain for ClassLoader
     * even when the app's own ClassLoader is passed explicitly.  Sitting next to
     * libsscronet.so puts the shim inside the domain that already works.
     */
    private static final String[] ALOOPER_SHIMS = {
        "/data/app/el2/100/base/com.ss.android.article.news/app_librarian/"
                + "default.version.6986727972/libwlalooper.so",
        "/data/app/el1/bundle/public/com.ss.android.article.news/android/lib/"
                + "arm64-v8a/libwlalooper.so",
        "/system/android/lib64/libwlalooper.so",
        "/data/pr03-74e6-portable/android/lib64/libwlalooper.so",
    };

    /**
     * Load the native shims into the *app's* linker namespace, on a background thread.
     *
     * A plain System.load() from this class fails with
     *     UnsatisfiedLinkError: no native dependency domain for ClassLoader
     * because the namespace is derived from the calling class's loader, and this
     * jar's loader has none registered.  Runtime.nativeLoad() takes the loader
     * explicitly, so passing the app's gives the library a real namespace.
     *
     * The app's ClassLoader does not exist yet at attachApplication, hence the
     * poll.
     *
     * SQLite is loaded first and unconditionally.  It used to run only inside the
     * ALooper shim's success branch, which made the database engine depend on an
     * experiment that has since been abandoned: sealed.child will not grant a
     * locally loaded library the global symbol visibility cronet's dlopen needs
     * (§2.36), so that file is usually absent now -- and the old early return on
     * "no ALooper shim found" meant SQLite was never loaded at all.
     *
     * The ordering matters on its own account too.  Whoever opens the first
     * database has to find the natives already registered, and AppLog does that
     * early in Application start-up; losing that race would surface as the same
     * UnsatisfiedLinkError this shim exists to remove.
     */
    private static void loadNativeShims() {
        if (sShimsTried) return;
        sShimsTried = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                long t0 = System.currentTimeMillis();
                for (int i = 0; i < 400; i++) {         // up to ~12s, polled fast
                    ClassLoader cl = appClassLoader();
                    if (cl != null) {
                        System.err.println("[WL-SHIM] app ClassLoader after "
                                + (System.currentTimeMillis() - t0) + "ms");
                        loadSqliteShim(cl);
                        probeSqlite();
                        loadAlooperShim(cl);
                        return;
                    }
                    try { Thread.sleep(30); } catch (InterruptedException e) { return; }
                }
                System.err.println("[WL-SHIM] app ClassLoader never appeared; no shim loaded");
            }
        }, "wl-shims");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Best-effort ALooper_* provider for libsscronet.
     *
     * Loaded only if the file happens to be deployed, and its failure is not
     * allowed to affect anything else.  It cannot actually rescue cronet -- a
     * locally loaded library's symbols are not visible to a later dlopen in this
     * namespace -- so this is kept only so a deployed copy still gets a chance
     * and reports what happened.
     */
    private static void loadAlooperShim(ClassLoader cl) {
        for (int i = 0; i < ALOOPER_SHIMS.length; i++) {
            java.io.File f = new java.io.File(ALOOPER_SHIMS[i]);
            if (!f.isFile() || !f.canRead()) continue;
            String err = nativeLoad(ALOOPER_SHIMS[i], cl);
            System.err.println("[WL-ALOOPER] load " + ALOOPER_SHIMS[i]
                    + (err == null ? " OK" : " FAILED: " + err));
            return;
        }
    }

    /** Candidate locations for the SQLite JNI shim, app dirs first. */
    private static final String[] SQLITE_SHIMS = {
        "/data/app/el2/100/base/com.ss.android.article.news/app_librarian/"
                + "default.version.6986727972/libwlsqlite.so",
        "/data/app/el1/bundle/public/com.ss.android.article.news/android/lib/"
                + "arm64-v8a/libwlsqlite.so",
    };

    /**
     * Load the SQLite JNI shim with the app's ClassLoader.
     *
     * Its JNI_OnLoad attaches natives to android.database.sqlite.SQLiteConnection
     * through RegisterNatives.  Unlike symbol resolution for a later dlopen -- the
     * thing that defeated the ALooper shim -- RegisterNatives works fine from a
     * locally loaded library, so the namespace limits in section 2.36 do not apply.
     */
    private static void loadSqliteShim(ClassLoader cl) {
        for (int i = 0; i < SQLITE_SHIMS.length; i++) {
            java.io.File f = new java.io.File(SQLITE_SHIMS[i]);
            if (!f.isFile() || !f.canRead()) continue;
            String err = nativeLoad(SQLITE_SHIMS[i], cl);
            System.err.println("[WL-SQLITE] load " + SQLITE_SHIMS[i]
                    + (err == null ? " OK" : " FAILED: " + err));
            return;
        }
        System.err.println("[WL-SQLITE] no shim found in "
                + java.util.Arrays.toString(SQLITE_SHIMS));
    }

    /**
     * The app's own ClassLoader, or null if it does not exist yet.
     *
     * Prefer ActivityThread.mBoundApplication.info (the LoadedApk): it carries a
     * usable ClassLoader well before Application is constructed, and the previous
     * attempt -- which waited for currentApplication() -- only got one at ~11.8s
     * against cronet's ~14.7s, far too close for comfort.
     */
    private static ClassLoader appClassLoader() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object cur = at.getMethod("currentActivityThread").invoke(null);
            if (cur != null) {
                Object bound = readField(at, cur, "mBoundApplication");
                if (bound != null) {
                    Object info = readField(bound.getClass(), bound, "info");
                    if (info != null) {
                        Object cl = info.getClass()
                                .getMethod("getClassLoader").invoke(info);
                        if (cl != null) return (ClassLoader) cl;
                    }
                }
            }
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app == null) return null;
            return (ClassLoader) app.getClass().getMethod("getClassLoader").invoke(app);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * @return null on success, otherwise the linker's error message.  AOSP has
     *         carried two signatures for this over the years; try both.
     */
    private static String nativeLoad(String path, ClassLoader loader) {
        try {
            Method m;
            try {
                m = Runtime.class.getDeclaredMethod("nativeLoad",
                        String.class, ClassLoader.class);
                m.setAccessible(true);
                return (String) m.invoke(null, path, loader);
            } catch (NoSuchMethodException e) {
                m = Runtime.class.getDeclaredMethod("nativeLoad",
                        String.class, ClassLoader.class, Class.class);
                m.setAccessible(true);
                return (String) m.invoke(null, path, loader,
                        ActivityManagerRouting.class);
            }
        } catch (Throwable t) {
            return String.valueOf(t);
        }
    }

    /* ---- TLS gate ----------------------------------------------------
     *
     * The adapter installs its own provider, "TlsShim"
     * (com.android.internal.os.TlsShimProvider, "TLS construct-but-fake shim for
     * OH (no real networking)"), before this class is even constructed.  It
     * answers SSLContext.getInstance("TLS") with a context that builds fine and
     * then throws UnsupportedOperationException on first real use -- which is the
     * "TLS shim: no real networking" that every TTNet DoConnect died on.
     *
     * Registering a real provider at position 1 is not enough on its own:
     * measured against the view-tree probe's fixed 30/60/85s passes, TTNet's first
     * DoConnect lands before t=30s while loading BouncyCastle takes ~31s, and
     * okhttp caches the SSLSocketFactory it got from that first lookup -- so every
     * later request keeps using the shim even once the real provider is up.
     *
     * Rather than race, take the shim's place.  java.security.Provider is a Map,
     * so its service entries can simply be re-pointed at the classes below; JCA
     * then instantiates *these* through the shim provider's own class loader,
     * which is this jar.  Callers that arrive before BouncyCastle has finished
     * loading block on a latch instead of receiving a broken context, so there is
     * no longer a wrong answer to cache -- whoever asks first just waits.
     */

    private static final java.util.concurrent.CountDownLatch sTlsReady =
            new java.util.concurrent.CountDownLatch(1);
    /** The real JSSE provider, published by the bootstrap thread. */
    private static volatile java.security.Provider sRealJsse;
    /** Our working trust manager, published alongside it. */
    private static volatile javax.net.ssl.X509TrustManager sRealTrustManager;
    /** How long a caller will wait for the real stack before giving up. */
    private static final long TLS_WAIT_MS = 120000L;

    private static java.security.Provider awaitRealJsse() throws java.io.IOException {
        java.security.Provider p = sRealJsse;
        if (p != null) return p;
        try {
            long t0 = System.currentTimeMillis();
            if (!sTlsReady.await(TLS_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                throw new java.io.IOException("timed out waiting for the TLS provider");
            }
            long waited = System.currentTimeMillis() - t0;
            if (waited > 50) {
                System.err.println("[WL-TLS] gate: caller waited " + waited + "ms for the real provider");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.io.IOException("interrupted waiting for the TLS provider");
        }
        p = sRealJsse;
        if (p == null) throw new java.io.IOException("TLS provider unavailable");
        return p;
    }

    /**
     * Android's Network Security Config trust manager, which cannot work here.
     *
     * okhttp asks the platform for its trust managers and hands whatever it gets
     * to SSLContext.init().  On this adapter that is
     * android.security.net.config.RootTrustManager, whose checkServerTrusted()
     * builds a com.android.org.conscrypt.TrustManagerImpl -- a class the adapter
     * does not ship.  The result, mid-handshake:
     *
     *   java.lang.NoSuchMethodError: No direct method
     *     <init>(KeyStore;CertPinManager;ConscryptCertStore)V
     *     in class Lcom/android/org/conscrypt/TrustManagerImpl;
     *       at android.security.net.config.NetworkSecurityTrustManager.<init>
     *       at android.security.net.config.RootTrustManager.checkServerTrusted
     *       at okhttp3.internal.connection.RealConnection.connectTls
     *
     * That is an Error, not an Exception, so okhttp's `catch (Exception)` does not
     * catch it; it unwinds through connectTls() whose finally-block does
     * closeQuietly(sslSocket) -- surfacing as the user_canceled(90) alerts that
     * killed nearly every app connection.
     */
    private static final String ANDROID_ROOT_TRUST_MANAGER =
            "android.security.net.config.RootTrustManager";

    /**
     * Replace the platform trust manager with ours; leave anything else alone.
     *
     * A caller that brings its own anchors (certificate pinning, a private CA)
     * must keep them, so only the known-broken platform one is substituted.
     */
    private static javax.net.ssl.TrustManager[] sanitizeTrustManagers(
            javax.net.ssl.TrustManager[] tms) {
        javax.net.ssl.X509TrustManager ours = sRealTrustManager;
        if (ours == null) return tms;                 // nothing better to offer
        if (tms == null || tms.length == 0) {
            return new javax.net.ssl.TrustManager[] { ours };
        }
        javax.net.ssl.TrustManager[] out = null;
        for (int i = 0; i < tms.length; i++) {
            if (tms[i] == null) continue;
            if (ANDROID_ROOT_TRUST_MANAGER.equals(tms[i].getClass().getName())) {
                if (out == null) {
                    out = new javax.net.ssl.TrustManager[tms.length];
                    System.arraycopy(tms, 0, out, 0, tms.length);
                }
                out[i] = ours;
                System.err.println("[WL-TLS] replaced " + ANDROID_ROOT_TRUST_MANAGER
                        + " (needs conscrypt's TrustManagerImpl, absent here)"
                        + " with the bridge's trust manager");
            }
        }
        return out != null ? out : tms;
    }

    /** SSLContext.TLS: waits for the real provider, then is a plain delegate. */
    public static final class TlsGateSpi extends javax.net.ssl.SSLContextSpi {
        private volatile javax.net.ssl.SSLContext mDelegate;

        public TlsGateSpi() { }

        /** The delegate, default-initialised if the caller never called init(). */
        private javax.net.ssl.SSLContext ready() {
            javax.net.ssl.SSLContext d = mDelegate;
            if (d != null) return d;
            synchronized (this) {
                if (mDelegate == null) {
                    try {
                        javax.net.ssl.SSLContext c =
                                javax.net.ssl.SSLContext.getInstance("TLS", awaitRealJsse());
                        c.init(null, null, null);
                        mDelegate = c;
                    } catch (Throwable t) {
                        throw new IllegalStateException("TLS gate: no real provider", t);
                    }
                }
                return mDelegate;
            }
        }

        @Override
        protected void engineInit(javax.net.ssl.KeyManager[] km,
                                  javax.net.ssl.TrustManager[] tm,
                                  java.security.SecureRandom sr)
                throws java.security.KeyManagementException {
            try {
                javax.net.ssl.SSLContext c =
                        javax.net.ssl.SSLContext.getInstance("TLS", awaitRealJsse());
                c.init(km, sanitizeTrustManagers(tm), sr);
                mDelegate = c;
            } catch (java.security.KeyManagementException e) {
                throw e;
            } catch (Throwable t) {
                throw new java.security.KeyManagementException("TLS gate init failed", t);
            }
        }

        @Override protected javax.net.ssl.SSLSocketFactory engineGetSocketFactory() {
            return ready().getSocketFactory();
        }
        @Override protected javax.net.ssl.SSLServerSocketFactory engineGetServerSocketFactory() {
            return ready().getServerSocketFactory();
        }
        @Override protected javax.net.ssl.SSLEngine engineCreateSSLEngine() {
            return ready().createSSLEngine();
        }
        @Override protected javax.net.ssl.SSLEngine engineCreateSSLEngine(String host, int port) {
            return ready().createSSLEngine(host, port);
        }
        @Override protected javax.net.ssl.SSLSessionContext engineGetServerSessionContext() {
            return ready().getServerSessionContext();
        }
        @Override protected javax.net.ssl.SSLSessionContext engineGetClientSessionContext() {
            return ready().getClientSessionContext();
        }
        @Override protected javax.net.ssl.SSLParameters engineGetDefaultSSLParameters() {
            return ready().getDefaultSSLParameters();
        }
        @Override protected javax.net.ssl.SSLParameters engineGetSupportedSSLParameters() {
            return ready().getSupportedSSLParameters();
        }
    }

    /**
     * TrustManagerFactory.PKIX: the shim answered this with an accept-all trust
     * manager, so it has to be re-pointed too -- okhttp asks for the platform
     * trust managers here and hands them straight to SSLContext.init().
     */
    public static final class TrustGateSpi extends javax.net.ssl.TrustManagerFactorySpi {
        private volatile javax.net.ssl.TrustManagerFactory mDelegate;

        public TrustGateSpi() { }

        private javax.net.ssl.TrustManagerFactory delegate() throws Exception {
            javax.net.ssl.TrustManagerFactory f = mDelegate;
            if (f == null) {
                // Wait for the stack, then resolve through the normal provider
                // order rather than naming BouncyCastle: TlsBootstrap installs a
                // front-end ahead of it that answers the platform-trust-anchor
                // request from memory.  Asking BC directly would send it looking
                // for a trust store file the board does not have.
                awaitRealJsse();
                f = javax.net.ssl.TrustManagerFactory.getInstance("PKIX");
                mDelegate = f;
            }
            return f;
        }

        @Override protected void engineInit(java.security.KeyStore ks)
                throws java.security.KeyStoreException {
            try {
                delegate().init(ks);
            } catch (java.security.KeyStoreException e) {
                throw e;
            } catch (Exception e) {
                throw new java.security.KeyStoreException("TLS gate trust init failed", e);
            }
        }

        @Override protected void engineInit(javax.net.ssl.ManagerFactoryParameters spec)
                throws java.security.InvalidAlgorithmParameterException {
            try {
                delegate().init(spec);
            } catch (java.security.InvalidAlgorithmParameterException e) {
                throw e;
            } catch (Exception e) {
                throw new java.security.InvalidAlgorithmParameterException(e);
            }
        }

        @Override protected javax.net.ssl.TrustManager[] engineGetTrustManagers() {
            try {
                javax.net.ssl.TrustManagerFactory f = delegate();
                javax.net.ssl.TrustManager[] tms = f.getTrustManagers();
                if (tms == null || tms.length == 0) {
                    // Not initialised by the caller: PKIX with a null KeyStore
                    // picks up the anchors TlsBootstrap exported.
                    f.init((java.security.KeyStore) null);
                    tms = f.getTrustManagers();
                }
                return tms;
            } catch (Exception e) {
                throw new IllegalStateException("TLS gate: no trust managers", e);
            }
        }
    }

    /**
     * Re-point the adapter's shim provider at the gate.  Cheap and synchronous:
     * it only rewrites map entries, so it is safe to do on the attach path, and it
     * closes the window before any app code can obtain a broken SSLContext.
     */
    private static void hijackTlsShim() {
        try {
            java.security.Provider shim = java.security.Security.getProvider("TlsShim");
            if (shim == null) {
                System.err.println("[WL-TLS] no 'TlsShim' provider to hijack"
                        + " (adapter may have changed); relying on provider order");
                return;
            }
            String ctxSpi = TlsGateSpi.class.getName();
            String tmfSpi = TrustGateSpi.class.getName();
            int n = 0;
            // Snapshot the keys first: we mutate the map as we go.
            java.util.List<String> keys = new java.util.ArrayList<String>();
            for (Object k : shim.keySet()) {
                if (k instanceof String) keys.add((String) k);
            }
            for (int i = 0; i < keys.size(); i++) {
                String k = keys.get(i);
                if (k.startsWith("Alg.Alias.")) continue;   // aliases follow the real entry
                if (k.startsWith("SSLContext.")) { shim.put(k, ctxSpi); n++; }
                else if (k.startsWith("TrustManagerFactory.")) { shim.put(k, tmfSpi); n++; }
            }
            System.err.println("[WL-TLS] hijacked 'TlsShim' provider: " + n
                    + " service(s) re-pointed at the gate");
        } catch (Throwable t) {
            System.err.println("[WL-TLS] hijackTlsShim failed: " + t);
        }
    }

    /**
     * Says whether the android.net.ssl.SSLSockets stand-in we ship in this jar is
     * actually reachable from the loader okhttp resolves against.  This jar is not
     * on the boot classpath, so that is a real question, and the answer decides
     * whether the class can live here or has to go into base.apk as an extra dex.
     */
    private static void reportSslSocketsVisibility() {
        final String cls = "android.net.ssl.SSLSockets";
        ClassLoader mine = ActivityManagerRouting.class.getClassLoader();
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        System.err.println("[WL-TLS] loaders: amr=" + mine + " context=" + ctx);
        try {
            Class<?> c = Class.forName(cls, false, ctx);
            System.err.println("[WL-TLS] " + cls + " visible via context loader, defined by "
                    + c.getClassLoader());
        } catch (Throwable t) {
            System.err.println("[WL-TLS] " + cls + " NOT visible via context loader: " + t);
        }
    }

    private static volatile boolean sTlsStarted;

    /** Candidate locations for the TLS dex, in order of preference. */
    private static final String[] TLS_JARS = {
        "/data/local/tmp/wl-tls.jar",
        "/data/pr03-74e6-portable/android/framework/wl-tls.jar",
    };

    private static void startTlsBootstrap() {
        if (sTlsStarted) return;
        sTlsStarted = true;
        // Do this first and inline: it is only map writes, and until it has run
        // any app code that asks for TLS gets the adapter's broken shim and keeps
        // the result forever.
        hijackTlsShim();
        reportSslSocketsVisibility();
        /*
         * Off the calling thread.  Loading ~5400 BouncyCastle classes and standing
         * up a provider costs seconds even on a desktop JVM, and this board runs
         * -Xint, so doing it inline would add that straight onto process attach.
         * The app's own network stack does not come up until well into Lego's init
         * chain, tens of seconds later, so this comfortably wins the race -- and
         * WlSSLSocketFactory.install() is synchronized, so an early caller blocks
         * rather than seeing a half-built provider.
         */
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                long t0 = System.currentTimeMillis();
                try {
                    String jar = null;
                    for (int i = 0; i < TLS_JARS.length; i++) {
                        java.io.File f = new java.io.File(TLS_JARS[i]);
                        if (f.isFile() && f.canRead()) { jar = TLS_JARS[i]; break; }
                    }
                    if (jar == null) {
                        System.err.println("[WL-TLS] no wl-tls.jar found in "
                                + java.util.Arrays.toString(TLS_JARS));
                        return;
                    }
                    ClassLoader parent = ActivityManagerRouting.class.getClassLoader();
                    Class<?> dclClass = Class.forName("dalvik.system.DexClassLoader");
                    Object cl = dclClass.getConstructor(String.class, String.class,
                                    String.class, ClassLoader.class)
                            .newInstance(jar, tlsOptimizedDir(), null, parent);
                    Class<?> boot = ((ClassLoader) cl).loadClass("westlake.tls.TlsBootstrap");
                    String status = (String) boot.getMethod("install").invoke(null);
                    sRealJsse = (java.security.Provider)
                            boot.getMethod("jsseProvider").invoke(null);
                    sRealTrustManager = (javax.net.ssl.X509TrustManager)
                            boot.getMethod("trustManager").invoke(null);
                    System.err.println("[WL-TLS] install: " + status
                            + " loadMs=" + (System.currentTimeMillis() - t0));
                    maybeSelfTest(boot);
                } catch (Throwable e) {
                    System.err.println("[WL-TLS] bootstrap failed after "
                            + (System.currentTimeMillis() - t0) + "ms: " + e);
                    e.printStackTrace();
                } finally {
                    // Always release the gate: a caller blocked in awaitRealJsse()
                    // must get a clear failure rather than hang for two minutes.
                    sTlsReady.countDown();
                }
            }
        }, "wl-tls-bootstrap");
        t.setDaemon(true);
        t.start();
        System.err.println("[WL-TLS] bootstrap thread armed");
    }

    /** A writable scratch dir for the dex cache, or null to let ART decide. */
    private static String tlsOptimizedDir() {
        String[] cands = { "/data/local/tmp/wl-dexcache", System.getProperty("java.io.tmpdir") };
        for (int i = 0; i < cands.length; i++) {
            if (cands[i] == null) continue;
            java.io.File d = new java.io.File(cands[i]);
            if (d.isDirectory() ? d.canWrite() : d.mkdirs()) return d.getAbsolutePath();
        }
        return null;
    }

    /**
     * Opt-in end-to-end proof that TLS works in this process, kept behind a marker
     * file so a normal run never pays for it.
     */
    private static void maybeSelfTest(Class<?> boot) {
        if (!new java.io.File("/data/local/tmp/wl-tls-selftest").isFile()) return;
        try {
            String r = (String) boot.getMethod("selfTest", String.class, int.class, String.class)
                    .invoke(null, "dm.toutiao.com", 443, "https://dm.toutiao.com/");
            System.err.println("[WL-TLS] selftest: " + r);
        } catch (Throwable e) {
            System.err.println("[WL-TLS] selftest failed: " + e);
        }
    }

    /* ------------------------------------------------------------------
     * View-tree dump
     *
     * The MainActivity window composites as plain white and neither of the
     * usual tools can say why: `uitest dumpLayout` only walks SceneBoard's
     * ArkUI tree (the Android views are not in it, and the app's WindowScene
     * does not even appear), and `hidumper -s WindowManagerService` lists only
     * SceneBoard's own windows because the adapter attaches app windows as
     * scene sessions.  So dump the Android hierarchy from inside the process.
     *
     * Everything is reflective: this class is compiled against a handful of
     * hand-written stubs, not a real android.jar.
     * ------------------------------------------------------------------ */

    private static volatile boolean sDumperStarted;

    private static void startViewTreeDumper() {
        if (sDumperStarted) return;
        sDumperStarted = true;
        /*
         * Run off a plain daemon thread, NOT the main Handler.  MainActivity's
         * startup wedges the main looper ~12s in -- which is exactly why the
         * window never draws -- so a main-thread dumper stops reporting at the
         * moment it becomes interesting.  From here we can still print the main
         * thread's stack and see what it is stuck on.
         */
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                long[] whenMs = { 30000L, 60000L, 85000L, 110000L };
                long slept = 0;
                for (int i = 0; i < whenMs.length; i++) {
                    try { Thread.sleep(whenMs[i] - slept); } catch (InterruptedException e) { return; }
                    slept = whenMs[i];
                    dumpMainThreadStack(i + 1);
                    dumpAllWindows(i + 1);
                }
            }
        }, "wl-main-probe");
        t.setDaemon(true);
        t.start();
        System.err.println("[WL-VIEWTREE] off-main probe armed (4 passes)");
    }


    /* ------------------------------------------------------------------
     * VelocityTracker
     *
     * framework.jar declares android.view.VelocityTracker's seven native
     * methods and nothing implements them, so the first touch to reach any
     * scrolling container dies:
     *
     *   UnsatisfiedLinkError: No implementation found for
     *       long android.view.VelocityTracker.nativeInitialize(int)
     *     at android.view.VelocityTracker.obtain(VelocityTracker.java:230)
     *     at android.widget.HorizontalScrollView.initOrResetVelocityTracker(:540)
     *     at android.widget.HorizontalScrollView.onInterceptTouchEvent(:641)
     *
     * ViewRootImpl's input stages swallow it, which is exactly why touch looked
     * like it was arriving and then quietly doing nothing.  The channel row is a
     * HorizontalScrollView and the feed is a RecyclerView; both obtain() one.
     *
     * VelocityTracker is a boot-classpath class, so ART resolves its natives
     * only against libraries registered under the *boot* class loader.  Loading
     * the shim into the app namespace (the way libwlalooper.so goes in) would
     * have no effect at all -- it has to be nativeLoad'ed with a null loader.
     * ------------------------------------------------------------------ */

    private static final String[] VELTRACK_SHIMS = {
        "/data/app/el1/bundle/public/com.ss.android.article.news/android/lib/arm64-v8a/libwlveltrack.so",
        "/data/app/el2/100/base/com.ss.android.article.news/app_lib/libwlveltrack.so",
        "/data/local/tmp/libwlveltrack.so",
    };

    private static volatile boolean sVelTrackTried;

    private static void loadVelocityTrackerShim() {
        if (sVelTrackTried) return;
        sVelTrackTried = true;
        String found = null;
        for (int i = 0; i < VELTRACK_SHIMS.length; i++) {
            File f = new File(VELTRACK_SHIMS[i]);
            if (f.isFile() && f.canRead()) { found = VELTRACK_SHIMS[i]; break; }
        }
        if (found == null) {
            System.err.println("[WL-VELTRACK] no shim found in "
                    + java.util.Arrays.toString(VELTRACK_SHIMS)
                    + "; scrolling containers will throw on first touch");
            return;
        }
        final String so = found;
        // The boot class loader is not an option: this adapter's libnativeloader
        // only admits libopenjdk / libicu_jni / libjavacore there and answers
        // anything else with "system library is absent from the adapter
        // manifest".  Go in through the app namespace instead and let the
        // library's JNI_OnLoad RegisterNatives the methods explicitly, which
        // does not care which loader it came from.  The app ClassLoader does not
        // exist yet at attachApplication, so wait for it off-thread -- first
        // touch is ~80s away, this needs a couple of seconds.
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                long t0 = System.currentTimeMillis();
                for (int i = 0; i < 400; i++) {
                    ClassLoader cl = appClassLoader();
                    if (cl != null) {
                        String err = nativeLoad(so, cl);
                        long ms = System.currentTimeMillis() - t0;
                        if (err != null) {
                            System.err.println("[WL-VELTRACK] nativeLoad(" + so
                                    + ") failed after " + ms + "ms: " + err);
                        } else {
                            System.err.println("[WL-VELTRACK] loaded " + so
                                    + " into the app namespace after " + ms + "ms");
                            velocityTrackerSelfTest();
                        }
                        return;
                    }
                    try { Thread.sleep(30); } catch (InterruptedException e) { return; }
                }
                System.err.println("[WL-VELTRACK] app ClassLoader never appeared; shim not loaded");
            }
        }, "wl-veltrack");
        t.setDaemon(true);
        t.start();
    }

    /** Exercise the exact call chain HorizontalScrollView takes, once, at startup. */
    private static void velocityTrackerSelfTest() {
        try {
            Class<?> vt = Class.forName("android.view.VelocityTracker");
            Object t = vt.getMethod("obtain").invoke(null);
            vt.getMethod("computeCurrentVelocity", int.class).invoke(t, Integer.valueOf(1000));
            Object vx = vt.getMethod("getXVelocity").invoke(t);
            Object vy = vt.getMethod("getYVelocity").invoke(t);
            vt.getMethod("recycle").invoke(t);
            System.err.println("[WL-VELTRACK] self-test OK: obtain/compute/recycle,"
                    + " xVelocity=" + vx + " yVelocity=" + vy);
        } catch (Throwable th) {
            Throwable c = th;
            while (c instanceof InvocationTargetException
                    && ((InvocationTargetException) c).getTargetException() != null) {
                c = ((InvocationTargetException) c).getTargetException();
            }
            System.err.println("[WL-VELTRACK] self-test FAILED: " + c);
        }
    }

    /* ------------------------------------------------------------------
     * Input delivery
     *
     * Touch never reaches the app on this adapter.  It builds the *consumer*
     * half of an AOSP input path and leaves the producer half unimplemented
     * (evidence in docs/INPUT_PATH_ANALYSIS.md):
     *
     *   - WindowSessionAdapter.addToDisplay opens a socketpair and calls
     *     InputEventBridge.nativeRegisterInputChannel(session, channel);
     *   - that JNI does GetMethodID(InputChannel, "getFd", "()I").  This
     *     framework's android.view.InputChannel has no getFd, so the lookup
     *     returns null and it logs "no-op, getFd not available" -- the session
     *     is never registered, and the fd it would poll is never set either
     *     (OHInputBridge::registerOHInputFd has zero call sites);
     *   - OHInputBridge::subscribeMmi(int) is a bare `ret`, so the adapter
     *     never subscribes to OH multimodal input in the first place;
     *   - OHInputBridge::monitorOHInputEvents() polls the (always empty) fd
     *     set and, on data, only logs "OH input event received: %zd bytes".
     *
     * Everything downstream of that socket is intact: liboh_android_runtime's
     * OH_InputMotionWorker builds a MotionEvent and hands it to
     * InputEventBridge.dispatchOnMainThread(receiver, seq, event), which
     * reflects into InputEventReceiver.dispatchInputEvent -- the ordinary
     * ViewRootImpl pipeline.  So we re-enter at exactly that point and
     * synthesise events from a command file:
     *
     *     echo 'tap 400 250'                > /data/local/tmp/wl_input.cmd
     *     echo 'swipe 600 1400 600 500 400' > /data/local/tmp/wl_input.cmd
     *
     * That drives the UI from the shell, which is what interface sampling
     * needs.  It is not a substitute for MMI: real hardware input still has
     * no producer, and supplying one is board-side work.
     * ------------------------------------------------------------------ */

    private static final String INPUT_CMD_FILE = "/data/local/tmp/wl_input.cmd";
    private static final int ACTION_DOWN = 0;
    private static final int ACTION_UP = 1;
    private static final int ACTION_MOVE = 2;
    private static final int SOURCE_TOUCHSCREEN = 0x00001002;
    private static final int FIRST_SUB_WINDOW = 1000;

    private static volatile boolean sInputPumpStarted;
    private static volatile boolean sBridgeMissReported;
    private static int sInputSeq = 1;

    private static void startInputPump() {
        if (sInputPumpStarted) return;
        sInputPumpStarted = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                File f = new File(INPUT_CMD_FILE);
                long seenStamp = -1L;
                while (true) {
                    try { Thread.sleep(150); } catch (InterruptedException e) { return; }
                    try {
                        if (!f.isFile() || f.length() == 0) { seenStamp = -1L; continue; }
                        // The shell writes this file as root, so the app usually
                        // cannot unlink or truncate it -- deleting is best effort
                        // and a stale command would otherwise replay on every
                        // poll.  Key on (mtime, length) instead, which needs no
                        // write access at all.
                        long stamp = f.lastModified() * 1000L + f.length();
                        if (stamp == seenStamp) continue;
                        seenStamp = stamp;
                        List<String> lines = readLines(f);
                        f.delete();
                        for (int i = 0; i < lines.size(); i++) {
                            runInputCommand(lines.get(i).trim());
                        }
                    } catch (Throwable th) {
                        System.err.println("[WL-INPUT] pump: " + th);
                    }
                }
            }
        }, "wl-input-pump");
        t.setDaemon(true);
        t.start();
        System.err.println("[WL-INPUT] pump armed, watching " + INPUT_CMD_FILE);
    }

    private static List<String> readLines(File f) throws Exception {
        List<String> out = new ArrayList<String>();
        BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), "UTF-8"));
        try {
            for (String s = r.readLine(); s != null; s = r.readLine()) {
                if (s.trim().length() > 0) out.add(s);
            }
        } finally {
            r.close();
        }
        return out;
    }

    private static void runInputCommand(String line) {
        if (line.length() == 0 || line.charAt(0) == '#') return;
        String[] a = line.split("\\s+");
        try {
            if ("tap".equals(a[0]) && a.length >= 3) {
                injectTap(Float.parseFloat(a[1]), Float.parseFloat(a[2]));
            } else if ("swipe".equals(a[0]) && a.length >= 5) {
                int ms = a.length >= 6 ? Integer.parseInt(a[5]) : 300;
                injectSwipe(Float.parseFloat(a[1]), Float.parseFloat(a[2]),
                            Float.parseFloat(a[3]), Float.parseFloat(a[4]), ms);
            } else if ("fingerprint".equals(a[0])) {
                probeFingerprint();
            } else if ("webview".equals(a[0])) {
                probeWebView();
            } else if ("tapv".equals(a[0]) && a.length >= 3) {
                injectTapDirect(Float.parseFloat(a[1]), Float.parseFloat(a[2]));
            } else if ("key".equals(a[0]) && a.length >= 2) {
                injectKey(Integer.parseInt(a[1]));
            } else if ("stack".equals(a[0])) {
                dumpMainThreadStack(0);
            } else if ("dump".equals(a[0])) {
                dumpAllWindows(99);
            } else {
                System.err.println("[WL-INPUT] unknown command: " + line);
            }
        } catch (Throwable t) {
            System.err.println("[WL-INPUT] '" + line + "' failed: " + t);
        }
    }

    private static void injectTap(float x, float y) throws Exception {
        Object vri = topInputTarget();
        if (vri == null) {
            System.err.println("[WL-INPUT] tap dropped: no window with an input receiver");
            return;
        }
        long down = uptimeMillis();
        dispatch(vri, motionEvent(down, down, ACTION_DOWN, x, y));
        dispatch(vri, motionEvent(down, uptimeMillis(), ACTION_UP, x, y));
        System.err.println("[WL-INPUT] tap " + x + "," + y + " -> " + describeTarget(vri));
    }

    private static void injectSwipe(float x1, float y1, float x2, float y2, int durationMs)
            throws Exception {
        Object vri = topInputTarget();
        if (vri == null) {
            System.err.println("[WL-INPUT] swipe dropped: no window with an input receiver");
            return;
        }
        int steps = durationMs / 16;
        if (steps < 4) steps = 4;
        if (steps > 120) steps = 120;
        long down = uptimeMillis();
        dispatch(vri, motionEvent(down, down, ACTION_DOWN, x1, y1));
        for (int i = 1; i <= steps; i++) {
            float t = (float) i / steps;
            try { Thread.sleep(durationMs / steps); } catch (InterruptedException ignored) {}
            dispatch(vri, motionEvent(down, uptimeMillis(), ACTION_MOVE,
                    x1 + (x2 - x1) * t, y1 + (y2 - y1) * t));
        }
        dispatch(vri, motionEvent(down, uptimeMillis(), ACTION_UP, x2, y2));
        System.err.println("[WL-INPUT] swipe " + x1 + "," + y1 + " -> " + x2 + "," + y2
                + " in " + steps + " steps -> " + describeTarget(vri));
    }

    private static void injectKey(int keyCode) throws Exception {
        Object vri = topInputTarget();
        if (vri == null) {
            System.err.println("[WL-INPUT] key dropped: no window with an input receiver");
            return;
        }
        Class<?> ke = Class.forName("android.view.KeyEvent");
        java.lang.reflect.Constructor<?> ctor = ke.getConstructor(
                long.class, long.class, int.class, int.class, int.class);
        long down = uptimeMillis();
        Object downEv = ctor.newInstance(down, down, ACTION_DOWN, keyCode, 0);
        Object upEv = ctor.newInstance(down, uptimeMillis(), ACTION_UP, keyCode, 0);
        setSource(ke, downEv, 0x00000101 /* SOURCE_KEYBOARD */);
        setSource(ke, upEv, 0x00000101);
        dispatch(vri, downEv);
        dispatch(vri, upEv);
        System.err.println("[WL-INPUT] key " + keyCode + " -> " + describeTarget(vri));
    }

    private static Object motionEvent(long downTime, long eventTime, int action, float x, float y)
            throws Exception {
        Class<?> me = Class.forName("android.view.MotionEvent");
        Method obtain = me.getMethod("obtain", long.class, long.class, int.class,
                float.class, float.class, int.class);
        Object ev = obtain.invoke(null, downTime, eventTime, action, x, y, 0);
        // obtain() leaves source at SOURCE_UNKNOWN; without SOURCE_CLASS_POINTER
        // ViewPostImeInputStage routes to processGenericMotionEvent and the view
        // hierarchy never sees the touch.
        setSource(me, ev, SOURCE_TOUCHSCREEN);
        return ev;
    }

    private static void setSource(Class<?> cls, Object event, int source) {
        Method m = findMethod(cls, "setSource", int.class);
        if (m == null) return;
        try {
            m.setAccessible(true);
            m.invoke(event, source);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Hand the event to the same entry point liboh_android_runtime's
     * OH_InputMotionWorker uses, falling back to ViewRootImpl directly.
     */
    private static void dispatch(Object vri, Object event) throws Exception {
        Object receiver = readFieldValue(vri, "mInputEventReceiver");
        if (receiver != null) {
            try {
                Class<?> bridge = Class.forName("adapter.window.InputEventBridge");
                Method m = bridge.getMethod("dispatchOnMainThread",
                        Class.forName("android.view.InputEventReceiver"),
                        int.class,
                        Class.forName("android.view.InputEvent"));
                m.invoke(null, receiver, sInputSeq++, event);
                return;
            } catch (Throwable t) {
                if (!sBridgeMissReported) {
                    sBridgeMissReported = true;
                    System.err.println("[WL-INPUT] InputEventBridge.dispatchOnMainThread "
                            + "unusable (" + t + "); using ViewRootImpl.enqueueInputEvent");
                }
            }
        }
        enqueueOnMain(vri, event);
    }

    private static void enqueueOnMain(final Object vri, final Object event) throws Exception {
        final Method enqueue = findMethod(vri.getClass(), "enqueueInputEvent",
                Class.forName("android.view.InputEvent"));
        if (enqueue == null) {
            System.err.println("[WL-INPUT] ViewRootImpl has no enqueueInputEvent(InputEvent)");
            return;
        }
        enqueue.setAccessible(true);
        runOnMain(new Runnable() {
            @Override public void run() {
                try {
                    enqueue.invoke(vri, event);
                } catch (Throwable t) {
                    System.err.println("[WL-INPUT] enqueueInputEvent failed: " + t);
                }
            }
        });
    }

    private static void runOnMain(Runnable r) throws Exception {
        Class<?> looperCls = Class.forName("android.os.Looper");
        Object main = looperCls.getMethod("getMainLooper").invoke(null);
        Class<?> handlerCls = Class.forName("android.os.Handler");
        Object handler = handlerCls.getConstructor(looperCls).newInstance(main);
        handlerCls.getMethod("post", Runnable.class).invoke(handler, r);
    }

    /**
     * Alternate route: hand the MotionEvent straight to the decor view instead
     * of going through ViewRootImpl's input stages.
     *
     * Also a diagnostic.  `tap` goes in at the receiver, exactly where
     * OH_InputMotionWorker would; if that changes nothing but `tapv` does, the
     * event is being accepted and then dropped somewhere in the stage chain
     * rather than by the app.
     */
    private static void injectTapDirect(final float x, final float y) throws Exception {
        Object vri = topInputTarget();
        if (vri == null) {
            System.err.println("[WL-INPUT] tapv dropped: no window with an input receiver");
            return;
        }
        final Object view = readFieldValue(vri, "mView");
        if (view == null) {
            System.err.println("[WL-INPUT] tapv dropped: ViewRootImpl has no view");
            return;
        }
        final Method dispatch = findMethod(view.getClass(), "dispatchTouchEvent",
                Class.forName("android.view.MotionEvent"));
        if (dispatch == null) {
            System.err.println("[WL-INPUT] tapv dropped: no dispatchTouchEvent on "
                    + view.getClass().getName());
            return;
        }
        dispatch.setAccessible(true);
        long down = uptimeMillis();
        final Object ev1 = motionEvent(down, down, ACTION_DOWN, x, y);
        final Object ev2 = motionEvent(down, down + 40, ACTION_UP, x, y);
        runOnMain(new Runnable() {
            @Override public void run() {
                try {
                    Object r1 = dispatch.invoke(view, ev1);
                    Object r2 = dispatch.invoke(view, ev2);
                    System.err.println("[WL-INPUT] tapv " + x + "," + y + " on "
                            + view.getClass().getName() + " -> down=" + r1 + " up=" + r2);
                } catch (Throwable t) {
                    Throwable c = t;
                    while (c instanceof InvocationTargetException
                            && ((InvocationTargetException) c).getTargetException() != null) {
                        c = ((InvocationTargetException) c).getTargetException();
                    }
                    System.err.println("[WL-INPUT] tapv dispatchTouchEvent threw: " + c);
                    StackTraceElement[] st = c.getStackTrace();
                    int n = st.length > 12 ? 12 : st.length;
                    for (int i = 0; i < n; i++) {
                        System.err.println("[WL-INPUT]   at " + st[i]);
                    }
                }
            }
        });
    }

    /* ------------------------------------------------------------------
     * WebView crash guard
     *
     * Probed on device (`echo webview > wl_input.cmd`):
     *
     *   WebViewFactory.getProvider() -> OK: com.bytedance.lynx.webview.glue.TTWebProviderWrapper
     *   new WebView(context)         -> NullPointerException
     *     at TTWebProviderWrapper.createWebView
     *     at android.webkit.WebView.ensureProviderCreated
     *
     * So this is NOT a missing WebViewFactoryProvider: getProvider() succeeds and
     * returns the app's own TTWebView.  What is null is the real engine *inside*
     * TTWeb -- its chromium core never initialised here -- and the NPE escapes
     * through View.<init>, which is why inflating any layout containing
     * PullToRefreshSSWebView takes the whole process down.
     *
     * Nothing here can start that engine.  What it can do is stop one dead
     * subview from killing the app: wrap the provider so a failing createWebView
     * hands back an inert WebViewProvider instead of throwing.  The WebView then
     * constructs, measures as an empty view, and the rest of the layout -- the
     * native headline, summary and images around it -- still inflates and draws.
     * ------------------------------------------------------------------ */

    private static volatile boolean sWebViewGuardTried;

    private static void installWebViewGuard() {
        if (sWebViewGuardTried) return;
        sWebViewGuardTried = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                // WebViewFactory resolves its provider lazily and caches it, and
                // resolving needs the app context, so wait for the Application.
                for (int i = 0; i < 400; i++) {
                    if (appClassLoader() != null) break;
                    try { Thread.sleep(30); } catch (InterruptedException e) { return; }
                }
                // getProvider() has to run on the main thread: called from this
                // worker it throws, while the same call from the main looper (the
                // `webview` probe) returns TTWebProviderWrapper fine.
                try {
                    runOnMain(new Runnable() {
                        @Override public void run() { swapInGuardedProvider(); }
                    });
                } catch (Throwable th) {
                    System.err.println("[WL-WEBVIEW] could not post guard install: " + th);
                }
            }
        }, "wl-webview-guard");
        t.setDaemon(true);
        t.start();
    }

    private static void swapInGuardedProvider() {
        {
                try {
                    Class<?> factory = Class.forName("android.webkit.WebViewFactory");
                    Method get = factory.getDeclaredMethod("getProvider");
                    get.setAccessible(true);
                    final Object real = get.invoke(null);
                    if (real == null) {
                        System.err.println("[WL-WEBVIEW] getProvider() returned null; no guard installed");
                        return;
                    }
                    Class<?> providerCls = Class.forName("android.webkit.WebViewFactoryProvider");
                    Object guarded = Proxy.newProxyInstance(providerCls.getClassLoader(),
                            new Class<?>[] { providerCls }, new InvocationHandler() {
                        @Override public Object invoke(Object p, Method m, Object[] args)
                                throws Throwable {
                            try {
                                return m.invoke(real, args);
                            } catch (InvocationTargetException e) {
                                Throwable cause = e.getCause() != null ? e.getCause() : e;
                                Class<?> ret = m.getReturnType();
                                if (ret.isInterface()) {
                                    System.err.println("[WL-WEBVIEW] " + m.getName()
                                            + " failed (" + cause + ") -> inert "
                                            + ret.getSimpleName());
                                    return inertProxy(ret, 0);
                                }
                                throw cause;
                            }
                        }
                    });
                    Field inst = factory.getDeclaredField("sProviderInstance");
                    inst.setAccessible(true);
                    inst.set(null, guarded);
                    System.err.println("[WL-WEBVIEW] guard installed over "
                            + real.getClass().getName());
                } catch (Throwable th) {
                    Throwable c = th;
                    while (c instanceof InvocationTargetException
                            && ((InvocationTargetException) c).getTargetException() != null) {
                        c = ((InvocationTargetException) c).getTargetException();
                    }
                    System.err.println("[WL-WEBVIEW] guard not installed: " + c);
                }
        }
    }

    /**
     * An object of the given interface that answers everything with a default.
     *
     * WebView does not just hold the provider: it immediately asks it for
     * getViewDelegate() and getScrollDelegate() and calls into those from
     * onMeasure/onDraw.  Returning null there would only move the NPE, so
     * interface-typed results become inert proxies too, to a bounded depth.
     */
    private static Object inertProxy(final Class<?> iface, final int depth) {
        if (depth > 3) return null;
        return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[] { iface },
                new InvocationHandler() {
            @Override public Object invoke(Object p, Method m, Object[] args) {
                Class<?> ret = m.getReturnType();
                if (ret.isInterface()) return inertProxy(ret, depth + 1);
                // Known boundary: java.lang.reflect.Proxy only implements
                // interfaces, so an abstract *class* return type (the one that
                // matters here is android.webkit.WebSettings, handed out by
                // WebViewProvider.getSettings()) can only be answered with null.
                // Callers that dereference it -- e.g.
                // MediaAppUtil.getWebViewDefaultUserAgent() ->
                // getSettings().getUserAgentString() -- still NPE, so those call
                // sites are neutralised in the app dex instead (see
                // patches/patch_base_apk.py, classes21.dex).  Say so once rather
                // than fail silently.
                if (!ret.isPrimitive() && ret != Void.TYPE
                        && java.lang.reflect.Modifier.isAbstract(ret.getModifiers())
                        && !sInertAbstractReported) {
                    sInertAbstractReported = true;
                    System.err.println("[WL-WEBVIEW] " + m.getName() + " returns abstract "
                            + ret.getName() + "; cannot synthesise one, returning null");
                }
                return defaultValue(ret);
            }
        });
    }

    private static volatile boolean sInertAbstractReported;

    /**
     * Report the device-identity values the app's device_register uses.
     *
     * 热榜 / 关注 come back empty while 推荐 works, and the suspicion is that
     * device registration cannot produce a fingerprint.  Rather than inject
     * plausible-looking values blind, ask what each source actually returns:
     *
     *     echo fingerprint > /data/local/tmp/wl_input.cmd
     */
    private static void probeFingerprint() throws Exception {
        runOnMain(new Runnable() {
            @Override public void run() {
                final Object app;
                try {
                    app = Class.forName("android.app.ActivityThread")
                            .getMethod("currentApplication").invoke(null);
                } catch (Throwable t) {
                    System.err.println("[WL-FP] no Application: " + t);
                    return;
                }
                report("Settings.Secure android_id", new Probe() {
                    @Override public Object call() throws Exception {
                        Object cr = app.getClass().getMethod("getContentResolver").invoke(app);
                        Class<?> sec = Class.forName("android.provider.Settings$Secure");
                        Method m = sec.getMethod("getString",
                                Class.forName("android.content.ContentResolver"), String.class);
                        return m.invoke(null, cr, "android_id");
                    }
                });
                String[][] buildFields = {
                    {"SERIAL", "android.os.Build"}, {"MODEL", "android.os.Build"},
                    {"BRAND", "android.os.Build"}, {"DEVICE", "android.os.Build"},
                    {"FINGERPRINT", "android.os.Build"}, {"MANUFACTURER", "android.os.Build"},
                };
                for (int i = 0; i < buildFields.length; i++) {
                    final String fn = buildFields[i][0];
                    final String cn = buildFields[i][1];
                    report("Build." + fn, new Probe() {
                        @Override public Object call() throws Exception {
                            Field f = Class.forName(cn).getField(fn);
                            return f.get(null);
                        }
                    });
                }
                final String[] tmCalls = { "getDeviceId", "getSubscriberId", "getSimSerialNumber",
                                           "getNetworkOperator", "getSimOperator" };
                for (int i = 0; i < tmCalls.length; i++) {
                    final String call = tmCalls[i];
                    report("TelephonyManager." + call, new Probe() {
                        @Override public Object call() throws Exception {
                            Object tm = app.getClass().getMethod("getSystemService", String.class)
                                    .invoke(app, "phone");
                            if (tm == null) return "<no phone service>";
                            Method m = tm.getClass().getMethod(call);
                            m.setAccessible(true);
                            return m.invoke(tm);
                        }
                    });
                }
            }
        });
    }

    /**
     * Ask the framework, on the main thread, exactly why WebView is unavailable.
     *
     * The article detail pages and the 视频 / 畅听 channels are WebView-backed and
     * fail with a bare "Error inflating class …PullToRefreshSSWebView" whose real
     * cause sits several frames down, and only surfaces when the feed happens to
     * have content.  This asks the question directly and deterministically:
     *
     *     echo webview > /data/local/tmp/wl_input.cmd
     */
    private static void probeWebView() throws Exception {
        runOnMain(new Runnable() {
            @Override public void run() {
                report("WebViewFactory.getProvider()", new Probe() {
                    @Override public Object call() throws Exception {
                        Class<?> f = Class.forName("android.webkit.WebViewFactory");
                        Method m = f.getDeclaredMethod("getProvider");
                        m.setAccessible(true);
                        return m.invoke(null);
                    }
                });
                report("new WebView(context)", new Probe() {
                    @Override public Object call() throws Exception {
                        Class<?> at = Class.forName("android.app.ActivityThread");
                        Object app = at.getMethod("currentApplication").invoke(null);
                        Class<?> wv = Class.forName("android.webkit.WebView");
                        return wv.getConstructor(Class.forName("android.content.Context"))
                                 .newInstance(app);
                    }
                });
            }
        });
    }

    private interface Probe { Object call() throws Exception; }

    /** Run one probe and print its whole cause chain -- the buried frame is the point. */
    private static void report(String what, Probe c) {
        try {
            Object r = c.call();
            System.err.println("[WL-PROBE] " + what + " -> OK: " + r);
        } catch (Throwable t) {
            int depth = 0;
            for (Throwable e = t; e != null && depth < 6; e = causeOf(e), depth++) {
                System.err.println("[WL-WEBVIEW] " + what
                        + (depth == 0 ? " -> " : "   caused by: ") + e);
                StackTraceElement[] st = e.getStackTrace();
                int n = st.length > 6 ? 6 : st.length;
                for (int i = 0; i < n; i++) {
                    System.err.println("[WL-WEBVIEW]     at " + st[i]);
                }
            }
        }
    }

    private static Throwable causeOf(Throwable t) {
        if (t instanceof InvocationTargetException) {
            Throwable x = ((InvocationTargetException) t).getTargetException();
            if (x != null) return x;
        }
        Throwable x = t.getCause();
        return (x == t) ? null : x;
    }

    /**
     * The window a touch should go to.
     *
     * Not simply the last entry in mRoots.  This app's PopupWindows are
     * neutralised sub-windows (type >= FIRST_SUB_WINDOW, surface released,
     * zero-sized -- see FlagMaskHandler.neutralizeSubWindow), and they sit at
     * the top of mRoots.  Dispatching there is a silent no-op: the events are
     * accepted and land on a window with nothing in it.  Prefer a real,
     * laid-out application window and only fall back to a sub-window that
     * actually has a size.
     */
    private static Object topInputTarget() throws Exception {
        Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
        Object inst = wmg.getMethod("getInstance").invoke(null);
        List<?> roots = (List<?>) readField(wmg, inst, "mRoots");
        if (roots == null) return null;
        Object best = null;
        int bestRank = -1;
        StringBuilder seen = new StringBuilder();
        // mRoots is in add order, so a later entry wins at equal rank.
        for (int i = 0; i < roots.size(); i++) {
            Object vri = roots.get(i);
            if (vri == null) continue;
            Object view = readFieldValue(vri, "mView");
            if (view == null) continue;
            if (readFieldValue(vri, "mInputEventReceiver") == null) continue;
            int type = windowType(vri);
            int w = viewDimension(view, "getWidth");
            int h = viewDimension(view, "getHeight");
            int rank;
            if (w > 0 && h > 0 && type >= 1 && type < FIRST_SUB_WINDOW) {
                rank = 2;                       // laid-out application window
            } else if (w > 0 && h > 0) {
                rank = 1;                       // laid-out sub-window / dialog
            } else {
                rank = 0;                       // present but has no area
            }
            seen.append(" [").append(i).append("] type=").append(type)
                .append(' ').append(w).append('x').append(h)
                .append(" rank=").append(rank);
            if (rank >= bestRank) { bestRank = rank; best = vri; }
        }
        if (best == null || bestRank == 0) {
            System.err.println("[WL-INPUT] no laid-out window to dispatch to;"
                    + " candidates:" + (seen.length() == 0 ? " none" : seen.toString()));
        }
        return best;
    }

    private static int windowType(Object vri) {
        Object lp = readFieldValue(vri, "mWindowAttributes");
        if (lp == null) return -1;
        try {
            Field f = findField(lp.getClass(), "type");
            if (f == null) return -1;
            f.setAccessible(true);
            return f.getInt(lp);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int viewDimension(Object view, String getter) {
        try {
            Method m = findMethod(view.getClass(), getter);
            if (m == null) return -1;
            m.setAccessible(true);
            return ((Integer) m.invoke(view)).intValue();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String describeTarget(Object vri) {
        Object lp = readFieldValue(vri, "mWindowAttributes");
        return "ViewRootImpl(" + (lp == null ? "?" : describeLp(lp)) + ")";
    }

    private static long uptimeMillis() {
        try {
            return (Long) Class.forName("android.os.SystemClock")
                    .getMethod("uptimeMillis").invoke(null);
        } catch (Throwable t) {
            return System.nanoTime() / 1000000L;
        }
    }

    private static void dumpMainThreadStack(int pass) {
        try {
            Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
            for (Map.Entry<Thread, StackTraceElement[]> e : all.entrySet()) {
                Thread th = e.getKey();
                if (!"main".equals(th.getName())) continue;
                System.err.println("[WL-MAINSTACK] ==== pass " + pass + " main state="
                        + th.getState() + " ====");
                StackTraceElement[] st = e.getValue();
                int limit = st.length > 45 ? 45 : st.length;
                for (int i = 0; i < limit; i++) {
                    System.err.println("[WL-MAINSTACK]   at " + st[i]);
                }
                if (st.length > limit) {
                    System.err.println("[WL-MAINSTACK]   ... " + (st.length - limit) + " more");
                }
            }
        } catch (Throwable t) {
            System.err.println("[WL-MAINSTACK] pass " + pass + " failed: " + t);
        }
    }

    private static void dumpAllWindows(int pass) {
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object inst = wmg.getMethod("getInstance").invoke(null);
            List<?> views = (List<?>) readField(wmg, inst, "mViews");
            List<?> params = (List<?>) readField(wmg, inst, "mParams");
            System.err.println("[WL-VIEWTREE] ==== pass " + pass + ": "
                    + (views == null ? -1 : views.size()) + " window(s) ====");
            if (views == null) return;
            for (int i = 0; i < views.size(); i++) {
                Object lp = (params != null && i < params.size()) ? params.get(i) : null;
                System.err.println("[WL-VIEWTREE] --- window[" + i + "] params=" + describeLp(lp));
                dumpView(views.get(i), 0, 0);
            }
            driveConsentDialog(views);
        } catch (Throwable t) {
            System.err.println("[WL-VIEWTREE] pass " + pass + " failed: " + t);
        }
    }

    private static String describeLp(Object lp) {
        if (lp == null) return "null";
        try {
            Class<?> c = lp.getClass();
            return "w=" + readIntField(c, lp, "width") + " h=" + readIntField(c, lp, "height")
                    + " type=" + readIntField(c, lp, "type")
                    + " flags=0x" + Integer.toHexString(readIntField(c, lp, "flags"))
                    + " title=" + lp;
        } catch (Throwable t) {
            return "<" + t + ">";
        }
    }

    /** Depth-first, but capped: a Toutiao feed is thousands of views. */
    private static void dumpView(Object v, int depth, int index) {
        if (v == null || depth > 22) return;   // feed item roots sit ~15 levels deep
        try {
            Class<?> viewCls = Class.forName("android.view.View");
            int vis = (Integer) viewCls.getMethod("getVisibility").invoke(v);
            int w = (Integer) viewCls.getMethod("getWidth").invoke(v);
            int h = (Integer) viewCls.getMethod("getHeight").invoke(v);
            int left = (Integer) viewCls.getMethod("getLeft").invoke(v);
            int top = (Integer) viewCls.getMethod("getTop").invoke(v);
            boolean shown = (Boolean) viewCls.getMethod("isShown").invoke(v);
            // Relative left/top is not enough to aim a tap: Gravity and margins
            // inside SSTabHost leave most children reporting (0,0) even though
            // they render at the bottom of the screen.  getLocationOnScreen is
            // unambiguous, so print the absolute rect and its centre too.
            int[] loc = new int[] { -1, -1 };
            try {
                viewCls.getMethod("getLocationOnScreen", int[].class).invoke(v, (Object) loc);
            } catch (Throwable ignore) { }
            boolean clickable = false;
            try {
                clickable = (Boolean) viewCls.getMethod("isClickable").invoke(v);
            } catch (Throwable ignore) { }
            String text = "";
            try {
                Class<?> tv = Class.forName("android.widget.TextView");
                if (tv.isInstance(v)) {
                    Object cs = tv.getMethod("getText").invoke(v);
                    if (cs != null) {
                        text = " text=\"" + cs.toString() + "\"";
                        if (text.length() > 60) text = text.substring(0, 60) + "...\"";
                    }
                }
            } catch (Throwable ignore) { }

            StringBuilder sb = new StringBuilder("[WL-VIEWTREE] ");
            for (int i = 0; i < depth; i++) sb.append("  ");
            sb.append('#').append(index).append(' ')
              .append(v.getClass().getName())
              .append(" vis=").append(vis == 0 ? "VISIBLE" : (vis == 4 ? "INVISIBLE" : "GONE"))
              .append(" shown=").append(shown)
              .append(" @").append(left).append(',').append(top)
              .append(' ').append(w).append('x').append(h)
              .append(" abs=[").append(loc[0]).append(',').append(loc[1])
              .append("][").append(loc[0] + w).append(',').append(loc[1] + h).append(']');
            if (w > 0 && h > 0) {
                sb.append(" c=(").append(loc[0] + w / 2).append(',')
                  .append(loc[1] + h / 2).append(')');
            }
            if (clickable) sb.append(" CLICKABLE");
            sb.append(text);
            System.err.println(sb.toString());

            Class<?> vg = Class.forName("android.view.ViewGroup");
            if (vg.isInstance(v)) {
                int n = (Integer) vg.getMethod("getChildCount").invoke(v);
                Method getChildAt = vg.getMethod("getChildAt", int.class);
                int limit = n > 40 ? 40 : n;
                for (int i = 0; i < limit; i++) {
                    dumpView(getChildAt.invoke(v, i), depth + 1, i);
                }
                if (n > limit) {
                    StringBuilder pad = new StringBuilder("[WL-VIEWTREE] ");
                    for (int i = 0; i <= depth; i++) pad.append("  ");
                    System.err.println(pad + "... " + (n - limit) + " more child(ren)");
                }
            }
        } catch (Throwable t) {
            System.err.println("[WL-VIEWTREE] dumpView failed at depth " + depth + ": " + t);
        }
    }

    /*
     * Consent-dialog assist.
     *
     * Toutiao gates startup behind a privacy-consent PopupWindow.  The adapter
     * creates that window's scene session with a null ability token
     * (`createSession ... tokenAddr=0x0` -> the degenerate `session=1`), which has
     * neither a surface nor an input channel: the dialog is invisible on screen
     * AND cannot be tapped, so the app waits on consent forever and the whole
     * process composites white.
     *
     * NOTE: this dispatches the "同意" button in-process.  It is an automation
     * step performed because the adapter cannot present the dialog to a human --
     * it is NOT a human consent event, and it should be removed once
     * PopupWindows get real sessions.
     */
    private static volatile boolean sConsentDriven;

    private static void driveConsentDialog(List<?> views) {
        if (sConsentDriven) return;
        for (int i = 0; i < views.size(); i++) {
            Object hit = findViewWithText(views.get(i), "同意", 0);
            if (hit != null) {
                try {
                    Class<?> viewCls = Class.forName("android.view.View");
                    Object target = hit;
                    // The label itself is often not the click target; walk up to
                    // the first ancestor that is clickable.
                    for (int up = 0; up < 4; up++) {
                        boolean clickable = (Boolean) viewCls.getMethod("isClickable").invoke(target);
                        if (clickable) break;
                        Object parent = viewCls.getMethod("getParent").invoke(target);
                        if (parent == null || !viewCls.isInstance(parent)) break;
                        target = parent;
                    }
                    System.err.println("[WL-CONSENT] adapter cannot render or route input to the"
                            + " consent PopupWindow (session=1, no surface/input);"
                            + " dispatching performClick() on " + target.getClass().getName()
                            + " -- automation, not a human consent event");
                    Object ok = viewCls.getMethod("performClick").invoke(target);
                    System.err.println("[WL-CONSENT] performClick returned " + ok);
                    sConsentDriven = true;
                } catch (Throwable t) {
                    System.err.println("[WL-CONSENT] failed: " + t);
                }
                return;
            }
        }
    }

    private static Object findViewWithText(Object v, String want, int depth) {
        if (v == null || depth > 14) return null;
        try {
            Class<?> tv = Class.forName("android.widget.TextView");
            if (tv.isInstance(v)) {
                Object cs = tv.getMethod("getText").invoke(v);
                if (cs != null && want.contentEquals(cs.toString())) return v;
            }
            Class<?> vg = Class.forName("android.view.ViewGroup");
            if (vg.isInstance(v)) {
                int n = (Integer) vg.getMethod("getChildCount").invoke(v);
                Method getChildAt = vg.getMethod("getChildAt", int.class);
                for (int i = 0; i < n; i++) {
                    Object r = findViewWithText(getChildAt.invoke(v, i), want, depth + 1);
                    if (r != null) return r;
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static Object readField(Class<?> c, Object o, String name) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static int readIntField(Class<?> c, Object o, String name) throws Exception {
        Field f = c.getField(name);
        f.setAccessible(true);
        return f.getInt(o);
    }

    @Override
    public ContentProviderHolder getContentProvider(IApplicationThread caller,
            String callingPackage, String name, int userId, boolean stable)
            throws RemoteException {
        ensurePackageManagerWrapped();
        ProviderInfo own = findDeclaredProvider(name);
        if (own != null) {
            ContentProviderHolder holder = new ContentProviderHolder(own);
            holder.noReleaseNeeded = true;
            // Must be flagged local.  ActivityThread.acquireProvider() treats
            // (provider == null && !mLocal) as "another process is publishing it" and
            // blocks for CONTENT_PROVIDER_READY_TIMEOUT_MILLIS -- 20s per acquisition,
            // while the caller (Mira) holds PluginPackageManager.class.  With mLocal set
            // it goes straight to installProvider() and instantiates in-process.
            holder.mLocal = true;
            System.err.println("[WL-AMR] own authority " + name + " -> local install");
            return holder;
        }
        ContentProviderHolder holder = super.getContentProvider(
                caller, callingPackage, name, userId, stable);
        if ("settings".equals(name)) {
            holder = wrapSettingsProvider(holder);
        }
        return holder;
    }

    /* ------------------------------------------------------------------
     * ANDROID_ID
     *
     * Probed on device: Settings.Secure.getString(cr, "android_id") returns
     * null here, while Build.* and TelephonyManager.* all answer normally.  The
     * app's device_register needs a stable device id, and a null one is a
     * plausible reason 热榜 / 关注 come back empty while 推荐 works.
     *
     * Settings.Secure reads through the "settings" provider, and that provider
     * is acquired via IActivityManager.getContentProvider -- which is this
     * class.  So wrap the provider that comes back and answer the one query it
     * is failing, rather than reaching into Settings' private cache.
     *
     * The value is a fixed, well-formed 16-hex-digit id: stable across runs
     * (device_register would reject a value that changes every launch) and
     * obviously synthetic.
     * ------------------------------------------------------------------ */

    private static final String FAKE_ANDROID_ID = "a1b2c3d4e5f60718";
    private static volatile boolean sAndroidIdReported;

    private static ContentProviderHolder wrapSettingsProvider(ContentProviderHolder holder) {
        if (holder == null) {
            System.err.println("[WL-FP] settings provider holder is null; ANDROID_ID stays unset");
            return null;
        }
        try {
            Field pf = findField(holder.getClass(), "provider");
            if (pf == null) return holder;
            pf.setAccessible(true);
            final Object real = pf.get(holder);
            if (real == null) {
                System.err.println("[WL-FP] settings holder has no provider; ANDROID_ID stays unset");
                return holder;
            }
            Class<?> icp = Class.forName("android.content.IContentProvider");
            Object proxy = Proxy.newProxyInstance(icp.getClassLoader(),
                    new Class<?>[] { icp }, new InvocationHandler() {
                @Override public Object invoke(Object p, Method m, Object[] args) throws Throwable {
                    if ("call".equals(m.getName()) && args != null && wantsAndroidId(args)) {
                        Class<?> bundleCls = Class.forName("android.os.Bundle");
                        Object b = bundleCls.getConstructor().newInstance();
                        bundleCls.getMethod("putString", String.class, String.class)
                                .invoke(b, "value", FAKE_ANDROID_ID);
                        if (!sAndroidIdReported) {
                            sAndroidIdReported = true;
                            System.err.println("[WL-FP] answering settings android_id = "
                                    + FAKE_ANDROID_ID);
                        }
                        return b;
                    }
                    try {
                        return m.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause() != null ? e.getCause() : e;
                    }
                }
            });
            pf.set(holder, proxy);
            System.err.println("[WL-FP] settings provider wrapped for android_id");
        } catch (Throwable t) {
            System.err.println("[WL-FP] could not wrap settings provider: " + t);
        }
        return holder;
    }

    /**
     * IContentProvider.call has had several signatures across API levels, so match
     * on the payload rather than the position: it is a settings GET whose argument
     * names android_id.
     */
    private static boolean wantsAndroidId(Object[] args) {
        for (int i = 0; i < args.length; i++) {
            if ("android_id".equals(args[i])) return true;
        }
        return false;
    }

    // ---- 1. app's own providers -------------------------------------------------

    /** Look the authority up in the ProviderInfo list this process was bound with. */
    private static ProviderInfo findDeclaredProvider(String authority) {
        if (authority == null || authority.length() == 0) {
            return null;
        }
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method current = atClass.getMethod("currentActivityThread");
            Object activityThread = current.invoke((Object) null);
            if (activityThread == null) {
                return null;
            }
            Field boundField = atClass.getDeclaredField("mBoundApplication");
            boundField.setAccessible(true);
            Object bound = boundField.get(activityThread);
            if (bound == null) {
                return null;
            }
            Field providersField = bound.getClass().getDeclaredField("providers");
            providersField.setAccessible(true);
            Object raw = providersField.get(bound);
            if (!(raw instanceof List)) {
                return null;
            }
            for (Object item : (List<?>) raw) {
                if (!(item instanceof ProviderInfo)) {
                    continue;
                }
                ProviderInfo info = (ProviderInfo) item;
                if (info.authority == null) {
                    continue;
                }
                String[] auths = info.authority.split(";");
                for (int i = 0; i < auths.length; i++) {
                    if (authority.equals(auths[i].trim())) {
                        return info;
                    }
                }
            }
        } catch (Throwable ignored) {
            // fall through to the default stub path
        }
        return null;
    }

    // ---- 2b. AppBindData.processName --------------------------------------------
    //
    // Toutiao gates a large amount of startup (ALog, most lego init tasks) on
    // ToolUtils.isMainProcessByActivityThread(), which reflects
    // ActivityThread.currentProcessName() -> mBoundApplication.processName and compares it
    // with the package name.  If that field is empty the app decides it is a secondary
    // process and skips the init, and SplashActivity.onResume() then NPEs on the
    // uninitialised ALog instance.  Make sure the field carries the real process name.

    private static volatile boolean sProcessNameChecked;

    private static void ensureProcessNameVisible() {
        if (sProcessNameChecked) {
            return;
        }
        sProcessNameChecked = true;
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object at = atClass.getMethod("currentActivityThread").invoke((Object) null);
            if (at == null) {
                return;
            }
            Field boundField = atClass.getDeclaredField("mBoundApplication");
            boundField.setAccessible(true);
            Object bound = boundField.get(at);
            if (bound == null) {
                sProcessNameChecked = false;   // bind not started yet; re-check later
                return;
            }
            Field pnField = bound.getClass().getDeclaredField("processName");
            pnField.setAccessible(true);
            Object cur = pnField.get(bound);
            String pkg = null;
            Object appInfo = readFieldValue(bound, "appInfo");
            if (appInfo != null) {
                Object v = readFieldValue(appInfo, "processName");
                if (v instanceof String && ((String) v).length() > 0) {
                    pkg = (String) v;
                } else {
                    v = readFieldValue(appInfo, "packageName");
                    if (v instanceof String && ((String) v).length() > 0) {
                        pkg = (String) v;
                    }
                }
            }
            if (!(cur instanceof String) || ((String) cur).length() == 0) {
                if (pkg != null) {
                    pnField.set(bound, pkg);
                    System.err.println("[WL-AMR] AppBindData.processName was empty -> " + pkg);
                }
            }
            System.err.println("[WL-AMR] currentProcessName=" + pnField.get(bound)
                    + " appInfo.processName=" + pkg);
        } catch (Throwable t) {
            System.err.println("[WL-AMR] processName check failed: " + t);
        }
    }

    // ---- 2c. LayoutParams flag masking on the window session --------------------
    //
    // WindowSessionAdapter fail-closes: any LayoutParams flag outside its
    // SUPPORTED_LAYOUT_FLAGS whitelist makes addToDisplay() return ADD_INVALID_TYPE, and
    // ViewRootImpl.setView() then throws InvalidDisplayException -- so the Activity never
    // gets a window and no frame is ever drawn.  Toutiao's splash asks for
    // FLAG_TRANSLUCENT_STATUS / FLAG_TRANSLUCENT_NAVIGATION / FLAG_DIM_BEHIND, which are
    // purely decorative.  Clear exactly the bits the adapter does not implement so the
    // window is created; nothing else about the request changes.

    private static volatile boolean sWindowSessionWrapped;
    private static int sSupportedFlags;

    private static int supportedLayoutFlags() {
        if (sSupportedFlags != 0) {
            return sSupportedFlags;
        }
        try {
            Class<?> wsa = Class.forName("adapter.window.WindowSessionAdapter");
            Field f = wsa.getDeclaredField("SUPPORTED_LAYOUT_FLAGS");
            f.setAccessible(true);
            sSupportedFlags = f.getInt(null);
        } catch (Throwable ignored) {
        }
        if (sSupportedFlags == 0) {
            // Mirror of the adapter whitelist, in case the field moves.
            sSupportedFlags = 0x01000000 | 0x00000100 | 0x00010000 | 0x00800000
                    | 0x80000000 | 0x00000400 | 0x00000080 | 0x00080000
                    | 0x00200000 | 0x00400000;
        }
        return sSupportedFlags;
    }

    private static void ensureWindowSessionWrapped() {
        if (sWindowSessionWrapped) {
            return;
        }
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Field f = wmg.getDeclaredField("sWindowSession");
            f.setAccessible(true);
            Object cur = f.get(null);
            if (cur == null) {
                wmg.getMethod("getWindowSession").invoke((Object) null);
                cur = f.get(null);
            }
            if (cur == null) {
                return;   // window stack not ready yet; try again on a later call
            }
            if (Proxy.isProxyClass(cur.getClass())
                    && Proxy.getInvocationHandler(cur) instanceof FlagMaskHandler) {
                sWindowSessionWrapped = true;
                return;
            }
            Class<?> iws = Class.forName("android.view.IWindowSession");
            Object wrapper = Proxy.newProxyInstance(iws.getClassLoader(),
                    new Class<?>[] { iws }, new FlagMaskHandler(cur));
            f.set(null, wrapper);
            sWindowSessionWrapped = true;
            System.err.println("[WL-AMR] IWindowSession wrapped; supported flags=0x"
                    + Integer.toHexString(supportedLayoutFlags()));
        } catch (Throwable t) {
            System.err.println("[WL-AMR] window session wrap failed: " + t);
        }
    }

    private static final class FlagMaskHandler implements InvocationHandler {
        private final Object mTarget;

        FlagMaskHandler(Object target) {
            mTarget = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String mName = method.getName();
            Object windowArg = (args != null && args.length > 0) ? args[0] : null;
            Object attrsArg = (args != null && args.length > 1) ? args[1] : null;

            // Every window a Dialog, PopupWindow or new Activity puts on screen
            // has to come through here, so this one line answers "did the click
            // even try to open something?".  addToDisplay/remove are rare;
            // relayout is not, so it stays out of the log.
            if (mName.startsWith("addToDisplay") || "remove".equals(mName)) {
                System.err.println("[WL-WIN] " + mName + " attrs=" + describeLp(attrsArg));
            }
            noteDegenerate(windowArg, attrsArg);

            if (args != null) {
                for (int i = 0; i < args.length; i++) {
                    maskFlags(args[i], mName);
                    fixupNullToken(args[i], mName);
                    dropHwAccelForSubWindow(args[i], mName);
                }
            }

            if (shouldNeutralize(windowArg, attrsArg)) {
                neutralizeSubWindow(windowArg, attrsArg, mName);
            }

            Object result;
            try {
                result = method.invoke(mTarget, args);
            } catch (InvocationTargetException e) {
                throw e.getCause() != null ? e.getCause() : e;
            }

            if ("relayout".equals(mName) && result instanceof Integer) {
                int ty = subWindowType(windowArg, attrsArg);
                if (ty >= FIRST_SUB_WINDOW && shouldNeutralize(windowArg, attrsArg)) {
                    int original = (Integer) result;
                    result = Integer.valueOf(0);
                    System.err.println("[WL-AMR] relayout: sub-window type=" + ty
                            + " -> neutralized relayoutResult (" + original + " -> 0)");
                    if (args.length > 10 && args[10] != null) {
                        try {
                            Method releaseM = findMethod(args[10].getClass(), "release");
                            if (releaseM != null) {
                                releaseM.setAccessible(true);
                                releaseM.invoke(args[10]);
                                System.err.println("[WL-AMR] relayout: sub-window type=" + ty
                                        + " -> released outSurfaceControl!");
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }

            if (shouldNeutralize(windowArg, attrsArg)) {
                invalidateSubWindowSurface(windowArg, attrsArg, mName);
            }

            return result;
        }

        /*
         * Make a sub-window's Surface report invalid, so ViewRootImpl never draws it.
         *
         * destroyHardwareRenderer() + setWindowStopped(true) stop the *hardware*
         * path, which did kill the old
         *     ASSERT FAILED [skia] mEglSurface == EGL_NO_SURFACE -> abort() -> exit 134
         * but ViewRootImpl then falls back to drawSoftware(), and because the popup's
         * degenerate session=1 Surface has no buffer producer, lockCanvas() never
         * actually locks and the finally-block blows up on the main thread:
         *
         *   java.lang.IllegalStateException: Surface was not locked
         *     at android.view.Surface.unlockSwCanvasAndPost(Surface.java:507)
         *     at android.view.ViewRootImpl.drawSoftware(ViewRootImpl.java:5046)
         *     at android.view.ViewRootImpl.performTraversals(...)
         *
         * mStopped does not reliably suppress an already-scheduled traversal, but
         * ViewRootImpl.draw() opens with an unconditional
         *     if (!surface.isValid()) { return false; }
         * in every AOSP version.  Releasing mSurface hits that gate, so the popup is
         * skipped before any lock/unlock happens -- while the Activity's own window
         * (type < 1000) keeps drawing normally.
         *
         * Done *after* the real call so the adapter cannot hand a fresh Surface back
         * underneath us.
         */
        private static void invalidateSubWindowSurface(Object window, Object layoutParams,
                String where) {
            int ty = subWindowType(window, layoutParams);
            if (ty < FIRST_SUB_WINDOW || window == null) return;
            try {
                Object vri = viewRootOf(window);
                if (vri == null) return;

                Field sf = findField(vri.getClass(), "mSurface");
                if (sf != null) {
                    sf.setAccessible(true);
                    Object surface = sf.get(vri);
                    if (surface != null) {
                        Method isValid = findMethod(surface.getClass(), "isValid");
                        boolean valid = true;
                        if (isValid != null) {
                            isValid.setAccessible(true);
                            valid = (Boolean) isValid.invoke(surface);
                        }
                        if (valid) {
                            Method rel = findMethod(surface.getClass(), "release");
                            if (rel != null) {
                                rel.setAccessible(true);
                                rel.invoke(surface);
                                System.err.println("[WL-AMR] " + where + ": sub-window type=" + ty
                                        + " -> released ViewRootImpl.mSurface; draw() will now"
                                        + " short-circuit on !isValid() instead of throwing"
                                        + " \"Surface was not locked\" in drawSoftware()");
                            }
                        }
                    }
                }
                /* belt and braces: without this performDraw() can still be entered
                 * once via the (!mStopped || mReportNextDraw) path. */
                Field rnd = findField(vri.getClass(), "mReportNextDraw");
                if (rnd != null) {
                    rnd.setAccessible(true);
                    if (rnd.getBoolean(vri)) rnd.setBoolean(vri, false);
                }
            } catch (Throwable t) {
                System.err.println("[WL-AMR] " + where + ": invalidateSubWindowSurface failed: " + t);
            }
        }

        /*
         * Back-fill LayoutParams.token for windows that arrive without one.
         *
         * Toutiao shows its privacy-consent gate as a PopupWindow (type=2,
         * TYPE_APPLICATION).  AOSP fills such a window's token from
         * WindowManagerImpl.mDefaultToken, which this adapter never sets, so the
         * request reaches the adapter's window bridge with a null token:
         *
         *   [WESTLAKE-WMC] createSession ... ability=MainActivity tokenAddr=0x0
         *   [WESTLAKE-QID] session=1 producer ... nodeName=PopupWindow:e14a9e5
         *
         * A null token yields the degenerate session id 1, which has neither a
         * surface nor an input channel.  The popup is therefore invisible *and*
         * untouchable -- the app blocks on consent forever -- and its surface-less
         * render node also wedges the process-wide HWUI thread:
         *
         *   ASSERT FAILED [skia] cond=mEglSurface == EGL_NO_SURFACE
         *       msg=drawRenderNode called on a context with no surface!
         *
         * which is why every window in the process then composites as plain white.
         * Reuse the token of a window that already got a real session.
         */
        private static volatile Object sLastGoodToken;

        private static void fixupNullToken(Object arg, String where) {
            if (arg == null
                    || !"android.view.WindowManager$LayoutParams".equals(
                            arg.getClass().getName())) {
                return;
            }
            try {
                Field tokenField = findField(arg.getClass(), "token");
                if (tokenField == null) {
                    return;
                }
                tokenField.setAccessible(true);
                Field typeF = findField(arg.getClass(), "type");
                int ty = -1;
                if (typeF != null) { typeF.setAccessible(true); ty = typeF.getInt(arg); }
                Object token = tokenField.get(arg);
                System.err.println("[WL-WSDIAG] " + where + " type=" + ty
                        + " token=" + (token == null ? "null" : token.getClass().getName()));
                /*
                 * The adapter resolves a window to an OH scene session through a map
                 * keyed by *activity* tokens.  A PopupWindow's LayoutParams.token is
                 * not an activity token -- PopupWindow takes it from
                 * View.getApplicationWindowToken(), i.e. the owning ViewRootImpl's
                 * window token, a plain android.os.Binder.  The lookup misses, the
                 * adapter falls back to `createSession ... tokenAddr=0x0` and the
                 * window lands on the degenerate session=1 with no surface and no
                 * input channel.  Swap in a token the map actually knows.
                 */
                /* Sub-windows (type >= FIRST_SUB_WINDOW) are *supposed* to carry the
                 * parent's ViewRootImpl$W, not an activity token -- substituting there
                 * is wrong and does not help anyway (the popup still lands on
                 * session=1).  Only fix real application windows. */
                Object activityToken = (ty >= 1000) ? null : currentActivityToken(token);
                if (activityToken != null && activityToken != token) {
                    tokenField.set(arg, activityToken);
                    System.err.println("[WL-AMR] " + where + ": window type=" + ty
                            + " carried a non-activity token (" 
                            + (token == null ? "null" : token.getClass().getName())
                            + "); substituted the top activity token so the adapter's"
                            + " session map resolves it instead of falling back to session=1");
                }
                if (token != null) {
                    sLastGoodToken = token;
                    return;
                }
            } catch (Throwable ignored) {
            }
        }

        /** Topmost ActivityThread activity token, or null if `have` is already one. */
        private static Object currentActivityToken(Object have) {
            try {
                Class<?> at = Class.forName("android.app.ActivityThread");
                Object thread = at.getMethod("currentActivityThread").invoke(null);
                if (thread == null) return null;
                Field f = at.getDeclaredField("mActivities");
                f.setAccessible(true);
                Object map = f.get(thread);
                if (!(map instanceof Map)) return null;
                Map<?, ?> m = (Map<?, ?>) map;
                if (have != null && m.containsKey(have)) return null;   // already fine
                Object last = null;
                for (Object k : m.keySet()) last = k;
                return last;
            } catch (Throwable t) {
                return null;
            }
        }

        /*
         * Drop FLAG_HARDWARE_ACCELERATED on sub-windows.
         *
         * A PopupWindow (TYPE_APPLICATION_PANEL, 1000) never gets a real OH scene
         * session on this adapter -- it falls back to the degenerate `session=1`,
         * which has no surface.  ViewRootImpl still drives HWUI for it, so the
         * render thread hits
         *   ASSERT FAILED [skia] cond=mEglSurface == EGL_NO_SURFACE
         *       msg=drawRenderNode called on a context with no surface!
         *   abort() hwui hijack
         * and the whole process exits 134 -- which is what kills Toutiao ~70s into
         * MainActivity, right when the fallback first frame would appear.
         *
         * Without the flag ViewRootImpl draws that window through the software
         * pipeline, which needs no EGL surface.  A popup drawn in software is
         * perfectly fine; the Activity's own window keeps hardware acceleration.
         */
        private static int getLayoutParamsType(Object arg) {
            if (arg == null || !"android.view.WindowManager$LayoutParams".equals(arg.getClass().getName())) {
                return -1;
            }
            try {
                Field typeF = findField(arg.getClass(), "type");
                if (typeF != null) {
                    typeF.setAccessible(true);
                    return typeF.getInt(arg);
                }
            } catch (Throwable ignored) {}
            return -1;
        }

        /*
         * Sub-window registry.
         *
         * ViewRootImpl.relayoutWindow() only sends LayoutParams when they actually
         * changed -- every subsequent relayout passes attrs == null to save IPC.  So a
         * type check on the *current* call misses the follow-up relayouts: the first
         * one (with attrs, type=1000) was neutralised, the second (attrs == null) fell
         * through, the adapter reported SURFACE_CHANGED, ViewRootImpl scheduled a
         * redraw and drawSoftware() threw "Surface was not locked" on the main thread.
         *
         * Remember which IWindow objects are sub-windows the first time we see their
         * type, so every later call is recognised regardless of attrs.  Weak keys so a
         * dismissed popup does not pin its ViewRootImpl.
         */
        /**
     * Sub-windows whose LayoutParams were degenerate (width or height exactly 0).
     *
     * Only these get neutralised.  The EGL_NO_SURFACE / "Surface was not locked"
     * mine that cost us the first frame was laid by one specific PopupWindow that
     * came in as `w=0 h=-1` -- a window with no area at all.  Blanking *every*
     * sub-window was the sledgehammer version of that fix, and it also swallowed
     * the login dialog and anything else with a real size.
     *
     * MATCH_PARENT (-1) and WRAP_CONTENT (-2) are real sizes; only an exact 0 is
     * degenerate.  Set the file /data/local/tmp/wl_neutralize_all to fall back to
     * blanking every sub-window if this turns out to reopen the first-frame hole.
     */
    private static final Set<Object> sDegenerateSubWindows =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<Object, Boolean>()));

    private static final String NEUTRALIZE_ALL_FILE = "/data/local/tmp/wl_neutralize_all";

    private static boolean neutralizeEverySubWindow() {
        return new File(NEUTRALIZE_ALL_FILE).exists();
    }

    private static final Set<Object> sSubWindows =
                Collections.synchronizedSet(
                        Collections.newSetFromMap(new WeakHashMap<Object, Boolean>()));

        /** Sub-window type for this call, or -1.  Registers on first identification. */
        /**
         * Remember whether this sub-window has any area.  LayoutParams only
         * arrive when they change (relayoutWindow passes null otherwise), so the
         * verdict has to be remembered per window, exactly like sSubWindows.
         */
        private static void noteDegenerate(Object window, Object layoutParams) {
            if (window == null || layoutParams == null) return;
            if (getLayoutParamsType(layoutParams) < FIRST_SUB_WINDOW) return;
            try {
                Class<?> c = layoutParams.getClass();
                Field wf = findField(c, "width");
                Field hf = findField(c, "height");
                if (wf == null || hf == null) return;
                wf.setAccessible(true);
                hf.setAccessible(true);
                int w = wf.getInt(layoutParams);
                int h = hf.getInt(layoutParams);
                // -1 MATCH_PARENT and -2 WRAP_CONTENT are real sizes; only an
                // exact 0 means the window can never have a drawable surface.
                boolean degenerate = (w == 0 || h == 0);
                if (degenerate) {
                    if (sDegenerateSubWindows.add(window)) {
                        System.err.println("[WL-WIN] sub-window w=" + w + " h=" + h
                                + " is degenerate -> will be neutralised");
                    }
                } else if (sDegenerateSubWindows.remove(window)) {
                    System.err.println("[WL-WIN] sub-window resized to w=" + w + " h=" + h
                            + " -> neutralisation lifted");
                }
            } catch (Throwable ignored) {
            }
        }

        private static boolean shouldNeutralize(Object window, Object layoutParams) {
            if (subWindowType(window, layoutParams) < FIRST_SUB_WINDOW) return false;
            if (neutralizeEverySubWindow()) return true;
            return window != null && sDegenerateSubWindows.contains(window);
        }

        private static int subWindowType(Object window, Object layoutParams) {
            int ty = getLayoutParamsType(layoutParams);
            if (ty >= FIRST_SUB_WINDOW) {
                if (window != null) sSubWindows.add(window);
                return ty;
            }
            if (window == null) return -1;
            if (sSubWindows.contains(window)) return FIRST_SUB_WINDOW;
            // attrs == null and not yet registered: ask the ViewRootImpl itself.
            try {
                Object vri = viewRootOf(window);
                if (vri != null) {
                    Field waf = findField(vri.getClass(), "mWindowAttributes");
                    if (waf != null) {
                        waf.setAccessible(true);
                        int t = getLayoutParamsType(waf.get(vri));
                        if (t >= FIRST_SUB_WINDOW) {
                            sSubWindows.add(window);
                            return t;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            return -1;
        }

        private static Object viewRootOf(Object window) {
            try {
                Field vf = findField(window.getClass(), "mViewAncestor");
                if (vf == null) return null;
                vf.setAccessible(true);
                Object wr = vf.get(window);
                if (wr instanceof java.lang.ref.WeakReference) {
                    return ((java.lang.ref.WeakReference<?>) wr).get();
                }
            } catch (Throwable ignored) {
            }
            return null;
        }

        private static void neutralizeSubWindow(Object window, Object layoutParams, String where) {
            int ty = subWindowType(window, layoutParams);
            if (ty < FIRST_SUB_WINDOW) return;
            try {
                if (window != null) {
                    Field vf = findField(window.getClass(), "mViewAncestor");
                    if (vf != null) {
                        vf.setAccessible(true);
                        Object wr = vf.get(window);
                        if (wr instanceof java.lang.ref.WeakReference) {
                            Object vri = ((java.lang.ref.WeakReference<?>) wr).get();
                            if (vri != null) {
                                Method dm = findMethod(vri.getClass(), "destroyHardwareRenderer");
                                if (dm != null) {
                                    dm.setAccessible(true);
                                    dm.invoke(vri);
                                    System.err.println("[WL-AMR] " + where + ": sub-window type=" + ty
                                            + " -> destroyed HardwareRenderer on ViewRootImpl!");
                                }
                                Method stopM = findMethod(vri.getClass(), "setWindowStopped", boolean.class);
                                if (stopM != null) {
                                    stopM.setAccessible(true);
                                    stopM.invoke(vri, true);
                                    System.err.println("[WL-AMR] " + where + ": sub-window type=" + ty
                                            + " -> setWindowStopped(true) on ViewRootImpl!");
                                }
                                Field rndField = findField(vri.getClass(), "mReportNextDraw");
                                if (rndField != null) {
                                    rndField.setAccessible(true);
                                    rndField.setBoolean(vri, false);
                                }
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                System.err.println("[WL-AMR] neutralizeSubWindow failed: " + t);
            }
        }

        private static final int FLAG_HARDWARE_ACCELERATED = 0x01000000;
        private static final int FIRST_SUB_WINDOW = 1000;

        private static void dropHwAccelForSubWindow(Object arg, String where) {
            if (arg == null
                    || !"android.view.WindowManager$LayoutParams".equals(
                            arg.getClass().getName())) {
                return;
            }
            try {
                Field typeF = findField(arg.getClass(), "type");
                Field flagsF = findField(arg.getClass(), "flags");
                if (typeF == null || flagsF == null) return;
                typeF.setAccessible(true); flagsF.setAccessible(true);
                int ty = typeF.getInt(arg);
                if (ty < FIRST_SUB_WINDOW) return;
                int flags = flagsF.getInt(arg);
                if ((flags & FLAG_HARDWARE_ACCELERATED) == 0) return;
                flagsF.setInt(arg, flags & ~FLAG_HARDWARE_ACCELERATED);
                System.err.println("[WL-AMR] " + where + ": sub-window type=" + ty
                        + " -> cleared FLAG_HARDWARE_ACCELERATED (no OH session => no EGL"
                        + " surface => drawRenderNode would abort the process)");
            } catch (Throwable ignored) {
            }
        }

        private static void maskFlags(Object arg, String where) {
            if (arg == null
                    || !"android.view.WindowManager$LayoutParams".equals(
                            arg.getClass().getName())) {
                return;
            }
            try {
                Field flagsField = findField(arg.getClass(), "flags");
                if (flagsField == null) {
                    return;
                }
                flagsField.setAccessible(true);
                int flags = flagsField.getInt(arg);
                int unsupported = flags & ~supportedLayoutFlags();
                if (unsupported != 0) {
                    flagsField.setInt(arg, flags & ~unsupported);
                    System.err.println("[WL-AMR] " + where + ": cleared unsupported window flags 0x"
                            + Integer.toHexString(unsupported) + " (was 0x"
                            + Integer.toHexString(flags) + ")");
                }
            } catch (Throwable ignored) {
            }
        }
    }

    // ---- 3. missing system-service stubs ----------------------------------------
    //
    // ApplicationPackageManager caches context.getSystemService(PermissionManager.class)
    // in its constructor.  That fetcher does
    // IPermissionManager.Stub.asInterface(ServiceManager.getServiceOrThrow("permissionmgr")),
    // which the adapter has no route for, so mPermissionManager stays null and
    // Activity.shouldShowRequestPermissionRationale() NPEs on the main thread during
    // SplashActivity.onResume().  Park a defaults-only binder in ServiceManager.sCache --
    // the same channel the adapter already uses for 'user' and 'mount'.

    private static volatile boolean sStubsInstalled;

    private static void ensureStubServices() {
        if (sStubsInstalled) {
            return;
        }
        synchronized (ActivityManagerRouting.class) {
            if (sStubsInstalled) {
                return;
            }
            sStubsInstalled = true;
            registerBinderStub("permissionmgr", "android.permission.IPermissionManager");
            registerBinderStub("legacy_permission", "android.permission.ILegacyPermissionManager");
        }
    }

    private static void registerBinderStub(String serviceName, String interfaceName) {
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Field cacheField = smClass.getDeclaredField("sCache");
            cacheField.setAccessible(true);
            Object cacheObj = cacheField.get(null);
            if (!(cacheObj instanceof Map)) {
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> cache = (Map<String, Object>) cacheObj;
            if (cache.get(serviceName) != null) {
                return;
            }
            Class<?> ifaceClass = Class.forName(interfaceName);
            ClassLoader cl = ifaceClass.getClassLoader();
            Class<?> binderClass = Class.forName("android.os.IBinder");

            final Object[] holder = new Object[2];
            Object iface = Proxy.newProxyInstance(cl, new Class<?>[] { ifaceClass },
                    new DefaultsHandler(holder, 1));
            Object binder = Proxy.newProxyInstance(cl, new Class<?>[] { binderClass },
                    new DefaultsHandler(holder, 0));
            holder[0] = binder;
            holder[1] = iface;

            cache.put(serviceName, binder);
            System.err.println("[WL-AMR] stub service '" + serviceName + "' registered ("
                    + interfaceName + ")");
        } catch (Throwable t) {
            System.err.println("[WL-AMR] stub service '" + serviceName + "' failed: " + t);
        }
    }

    /** Returns benign defaults; wires asBinder()/queryLocalInterface() to each other. */
    private static final class DefaultsHandler implements InvocationHandler {
        private final Object[] mPair;   // [0] = binder, [1] = interface
        private final int mKind;        // 0 = binder, 1 = interface

        DefaultsHandler(Object[] pair, int kind) {
            mPair = pair;
            mKind = kind;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String n = method.getName();
            if ("queryLocalInterface".equals(n)) {
                return mPair[1];
            }
            if ("asBinder".equals(n)) {
                return mKind == 0 ? proxy : mPair[0];
            }
            if ("isBinderAlive".equals(n) || "pingBinder".equals(n)) {
                return Boolean.TRUE;
            }
            if ("toString".equals(n)) {
                return "WL-STUB";
            }
            if ("hashCode".equals(n)) {
                return Integer.valueOf(System.identityHashCode(proxy));
            }
            if ("equals".equals(n)) {
                return Boolean.valueOf(args != null && args.length == 1 && args[0] == proxy);
            }
            return defaultValue(method.getReturnType());
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == void.class || type == Void.class) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == int.class) {
            return Integer.valueOf(0);
        }
        if (type == long.class) {
            return Long.valueOf(0L);
        }
        if (type == short.class) {
            return Short.valueOf((short) 0);
        }
        if (type == byte.class) {
            return Byte.valueOf((byte) 0);
        }
        if (type == char.class) {
            return Character.valueOf('\0');
        }
        if (type == float.class) {
            return Float.valueOf(0f);
        }
        if (type == double.class) {
            return Double.valueOf(0d);
        }
        if (List.class.isAssignableFrom(type)) {
            return new java.util.ArrayList<Object>();
        }
        return null;
    }

    // ---- 2. processName back-fill on PackageManager results ----------------------

    private static void ensurePackageManagerWrapped() {
        if (sPmWrapped) {
            return;
        }
        synchronized (ActivityManagerRouting.class) {
            if (sPmWrapped) {
                return;
            }
            try {
                Class<?> atClass = Class.forName("android.app.ActivityThread");
                Field pmField = atClass.getDeclaredField("sPackageManager");
                pmField.setAccessible(true);
                Object real = pmField.get(null);
                if (real == null) {
                    return;
                }
                if (Proxy.isProxyClass(real.getClass())
                        && Proxy.getInvocationHandler(real) instanceof PmHandler) {
                    sPmWrapped = true;
                    return;
                }
                Class<?> ipm = Class.forName("android.content.pm.IPackageManager");
                Object wrapper = Proxy.newProxyInstance(ipm.getClassLoader(),
                        new Class<?>[] { ipm }, new PmHandler(real));
                pmField.set(null, wrapper);
                sPmWrapped = true;
                System.err.println("[WL-AMR] IPackageManager wrapped for processName back-fill");
            } catch (Throwable t) {
                System.err.println("[WL-AMR] PM wrap failed: " + t);
            }
        }
    }

    private static final class PmHandler implements InvocationHandler {
        private final Object mTarget;

        PmHandler(Object target) {
            mTarget = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            ensureProcessNameVisible();
            ensureWindowSessionWrapped();
            Object result;
            try {
                result = method.invoke(mTarget, args);
            } catch (InvocationTargetException e) {
                throw e.getCause() != null ? e.getCause() : e;
            }
            String name = method.getName();
            if (name.startsWith("query") || name.startsWith("resolve")
                    || name.startsWith("get")) {
                try {
                    int fixed = backFill(result, 0);
                    if (fixed > 0 && !sBackFillReported) {
                        sBackFillReported = true;
                        System.err.println("[WL-AMR] pm." + name
                                + " processName back-filled x" + fixed
                                + " (further back-fills silent)");
                    }
                } catch (Throwable t) {
                    System.err.println("[WL-AMR] backFill(" + name + ") threw " + t);
                }
            }
            return result;
        }
    }

    /** Walk any PackageManager result and back-fill ComponentInfo.processName. */
    private static int backFill(Object result, int depth) {
        if (result == null || depth > 3) {
            return 0;
        }
        int fixed = 0;

        if (result instanceof Object[]) {
            Object[] arr = (Object[]) result;
            for (int i = 0; i < arr.length; i++) {
                fixed += backFill(arr[i], depth + 1);
            }
            return fixed;
        }
        if (result instanceof List) {
            for (Object item : (List<?>) result) {
                fixed += backFill(item, depth + 1);
            }
            return fixed;
        }

        String cls = result.getClass().getName();

        // IPackageManager hands lists back as ParceledListSlice.
        if (cls.indexOf("ParceledListSlice") >= 0) {
            try {
                Object inner = result.getClass().getMethod("getList").invoke(result);
                return backFill(inner, depth + 1);
            } catch (Throwable ignored) {
                return 0;
            }
        }
        if ("android.content.pm.ResolveInfo".equals(cls)) {
            fixed += fixComponent(readField(result, "activityInfo"));
            fixed += fixComponent(readField(result, "serviceInfo"));
            fixed += fixComponent(readField(result, "providerInfo"));
            return fixed;
        }
        if ("android.content.pm.PackageInfo".equals(cls)) {
            fixed += backFill(readField(result, "activities"), depth + 1);
            fixed += backFill(readField(result, "services"), depth + 1);
            fixed += backFill(readField(result, "receivers"), depth + 1);
            fixed += backFill(readField(result, "providers"), depth + 1);
            return fixed;
        }
        return fixComponent(result);
    }

    /** ComponentInfo.processName defaults to the application's process on a device. */
    private static volatile boolean sThemeFixReported;
    private static volatile boolean sThemeMissReported;

    /**
     * ActivityInfo.theme.
     *
     * The adapter's manifest parser does not carry the manifest's theme across,
     * so ActivityInfo arrives with theme == 0.  ActivityThread then resolves a
     * bare framework theme and every AppCompatActivity dies on setContentView:
     *
     *   RuntimeException: Unable to start activity …SearchActivity:
     *   RuntimeException: themeId:0xnull themeResources:0x103013f
     *   Caused by: IllegalStateException: You need to use a Theme.AppCompat
     *              theme (or descendant) with this activity.
     *
     * This is what "aa start renders white" actually was: the process does not
     * render the new activity, it *dies* launching it.
     *
     * AOSP's own rule when an <activity> declares no theme is to fall back to
     * the <application> theme, so do exactly that.
     */
    private static int fixTheme(Object component) {
        Field themeF = findField(component.getClass(), "theme");
        if (themeF == null) {
            return 0;
        }
        try {
            themeF.setAccessible(true);
            if (themeF.getInt(component) != 0) {
                return 0;
            }
            Object appInfo = readField(component, "applicationInfo");
            Object v = (appInfo == null) ? null : readFieldValue(appInfo, "theme");
            int appTheme = (v instanceof Integer) ? ((Integer) v).intValue() : 0;
            if (appTheme == 0) {
                // Nothing to inherit -- ApplicationInfo.theme is 0 too.  Read the
                // real value straight out of the apk's manifest instead; failing
                // that, any Theme.AppCompat descendant at least satisfies
                // AppCompatDelegate.
                appTheme = manifestTheme(component, appInfo);
            }
            if (appTheme == 0) {
                appTheme = fallbackAppCompatTheme();
            }
            if (appTheme == 0) {
                if (!sThemeMissReported) {
                    sThemeMissReported = true;
                    System.err.println("[WL-AMR] theme back-fill unavailable:"
                            + " ActivityInfo.theme==0, ApplicationInfo.theme==0 and no"
                            + " Theme.AppCompat.* in the app's resources ("
                            + readFieldValue(component, "name") + ")");
                }
                return 0;
            }
            themeF.setInt(component, appTheme);
            if (!sThemeFixReported) {
                sThemeFixReported = true;
                System.err.println("[WL-AMR] ActivityInfo.theme back-filled from"
                        + " ApplicationInfo.theme=0x" + Integer.toHexString(appTheme));
            }
            return 1;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /* ------------------------------------------------------------------
     * Manifest themes
     *
     * The adapter's manifest parser does not carry android:theme across, so
     * every ActivityInfo arrives with theme == 0 -- and so does ApplicationInfo.
     * ActivityThread then resolves a bare framework theme, and every
     * AppCompatActivity dies in setContentView:
     *
     *   RuntimeException: Unable to start activity …SearchActivity:
     *   RuntimeException: themeId:0xnull themeResources:0x103013f
     *   Caused by: IllegalStateException: You need to use a Theme.AppCompat
     *              theme (or descendant) with this activity.
     *
     * That is what "aa start renders a white screen" actually was: the process
     * does not fail to draw the new activity, it *dies* launching it.
     *
     * The information is right there in the apk -- in this one, 781 of 968
     * activities declare a theme -- so read AndroidManifest.xml ourselves.  It
     * is binary AXML, but only three things are needed: the string pool, the
     * resource-id map (framework attributes carry no name, only an id), and the
     * android:name / android:theme attributes of each <activity>.
     * ------------------------------------------------------------------ */

    private static final int ATTR_THEME = 0x01010000;
    private static final int ATTR_NAME  = 0x01010003;

    private static volatile Map<String, Integer> sManifestThemes;
    private static volatile int sManifestAppTheme;
    private static volatile boolean sManifestTried;

    private static void loadManifestThemes(Object appInfo) {
        if (sManifestTried) return;
        sManifestTried = true;
        String apk = null;
        if (appInfo != null) {
            Object v = readFieldValue(appInfo, "sourceDir");
            if (v instanceof String) apk = (String) v;
            if (apk == null) {
                v = readFieldValue(appInfo, "publicSourceDir");
                if (v instanceof String) apk = (String) v;
            }
        }
        if (apk == null) {
            apk = "/data/app/el1/bundle/public/com.ss.android.article.news/android/base.apk";
        }
        java.util.zip.ZipFile zf = null;
        try {
            zf = new java.util.zip.ZipFile(apk);
            java.util.zip.ZipEntry e = zf.getEntry("AndroidManifest.xml");
            if (e == null) {
                System.err.println("[WL-THEME] no AndroidManifest.xml in " + apk);
                return;
            }
            java.io.InputStream in = zf.getInputStream(e);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n = in.read(buf); n > 0; n = in.read(buf)) bos.write(buf, 0, n);
            in.close();
            Map<String, Integer> map = parseAxmlThemes(bos.toByteArray());
            sManifestThemes = map;
            System.err.println("[WL-THEME] parsed " + apk + ": " + map.size()
                    + " activity themes, application theme=0x"
                    + Integer.toHexString(sManifestAppTheme));
        } catch (Throwable t) {
            System.err.println("[WL-THEME] manifest parse failed: " + t);
        } finally {
            if (zf != null) { try { zf.close(); } catch (Throwable ignored) {} }
        }
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8);
    }

    private static int u32(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8)
             | ((b[o + 2] & 0xff) << 16) | ((b[o + 3] & 0xff) << 24);
    }

    private static Map<String, Integer> parseAxmlThemes(byte[] b) throws Exception {
        Map<String, Integer> out = new java.util.HashMap<String, Integer>();
        List<String> pool = new ArrayList<String>();
        int[] resMap = new int[0];
        int p = 8;
        while (p + 8 <= b.length) {
            int ctype = u16(b, p);
            int hsize = u16(b, p + 2);
            int csize = u32(b, p + 4);
            if (csize <= 0 || p + csize > b.length) break;

            if (ctype == 0x0001) {                       // RES_STRING_POOL_TYPE
                int cnt = u32(b, p + 8);
                int flags = u32(b, p + 16);
                int strStart = p + u32(b, p + 20);
                boolean utf8 = (flags & 0x100) != 0;
                for (int i = 0; i < cnt; i++) {
                    int q = strStart + u32(b, p + hsize + 4 * i);
                    if (q < 0 || q >= b.length) { pool.add(""); continue; }
                    if (utf8) {
                        int n = b[q] & 0xff;               // char count
                        q += ((n & 0x80) != 0) ? 2 : 1;
                        int m = b[q] & 0xff;               // byte count
                        if ((m & 0x80) != 0) { m = ((m & 0x7f) << 8) | (b[q + 1] & 0xff); q += 2; }
                        else q += 1;
                        pool.add(new String(b, q, Math.min(m, b.length - q), "UTF-8"));
                    } else {
                        int n = u16(b, q);
                        q += 2;
                        pool.add(new String(b, q, Math.min(n * 2, b.length - q), "UTF-16LE"));
                    }
                }
            } else if (ctype == 0x0180) {                // RES_XML_RESOURCE_MAP_TYPE
                int n = (csize - hsize) / 4;
                resMap = new int[n];
                for (int i = 0; i < n; i++) resMap[i] = u32(b, p + hsize + 4 * i);
            } else if (ctype == 0x0102) {                // RES_XML_START_ELEMENT_TYPE
                int nameIdx = u32(b, p + 20);
                String tag = (nameIdx >= 0 && nameIdx < pool.size()) ? pool.get(nameIdx) : "";
                boolean isActivity = "activity".equals(tag) || "activity-alias".equals(tag);
                if (isActivity || "application".equals(tag)) {
                    // attributeStart is relative to the attrExt struct, which
                    // begins right after the 16-byte node header.
                    int aStart = p + 16 + u16(b, p + 24);
                    int aCount = u16(b, p + 28);
                    String name = null;
                    int theme = 0;
                    for (int i = 0; i < aCount; i++) {
                        int a = aStart + i * 20;
                        if (a + 20 > b.length) break;
                        int nIdx = u32(b, a + 4);
                        int res = (nIdx >= 0 && nIdx < resMap.length) ? resMap[nIdx] : 0;
                        int dtype = b[a + 15] & 0xff;
                        int data = u32(b, a + 16);
                        if (res == ATTR_THEME) {
                            theme = data;
                        } else if (res == ATTR_NAME && dtype == 0x03
                                && data >= 0 && data < pool.size()) {
                            name = pool.get(data);
                        }
                    }
                    if (isActivity) {
                        if (name != null && theme != 0) out.put(name, Integer.valueOf(theme));
                    } else if (theme != 0) {
                        sManifestAppTheme = theme;
                    }
                }
            }
            p += csize;
        }
        return out;
    }

    private static int manifestTheme(Object component, Object appInfo) {
        loadManifestThemes(appInfo);
        Map<String, Integer> map = sManifestThemes;
        if (map != null) {
            Object n = readFieldValue(component, "name");
            if (n instanceof String) {
                Integer t = map.get((String) n);
                if (t != null) return t.intValue();
            }
        }
        return sManifestAppTheme;
    }

    private static final String[] APPCOMPAT_THEMES = {
        "Theme.AppCompat.Light.NoActionBar",
        "Theme.AppCompat.Light",
        "Theme.AppCompat.NoActionBar",
        "Theme.AppCompat",
    };

    /** 0 until the Application exists; only a successful lookup is cached. */
    private static volatile int sFallbackTheme;

    private static int fallbackAppCompatTheme() {
        int cached = sFallbackTheme;
        if (cached != 0) {
            return cached;
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app == null) {
                return 0;
            }
            Object res = app.getClass().getMethod("getResources").invoke(app);
            String pkg = (String) app.getClass().getMethod("getPackageName").invoke(app);
            Method gi = res.getClass().getMethod("getIdentifier",
                    String.class, String.class, String.class);
            for (int i = 0; i < APPCOMPAT_THEMES.length; i++) {
                Object id = gi.invoke(res, APPCOMPAT_THEMES[i], "style", pkg);
                if (id instanceof Integer && ((Integer) id).intValue() != 0) {
                    int v = ((Integer) id).intValue();
                    sFallbackTheme = v;
                    System.err.println("[WL-AMR] theme fallback resolved "
                            + APPCOMPAT_THEMES[i] + " = 0x" + Integer.toHexString(v));
                    return v;
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static int fixComponent(Object component) {
        if (component == null) {
            return 0;
        }
        int fixed = fixTheme(component);
        Field processName = findField(component.getClass(), "processName");
        if (processName == null) {
            return fixed;
        }
        try {
            processName.setAccessible(true);
            Object cur = processName.get(component);
            if (cur instanceof String && ((String) cur).length() > 0) {
                return fixed;
            }
            String replacement = null;
            Object appInfo = readField(component, "applicationInfo");
            if (appInfo != null) {
                Object v = readFieldValue(appInfo, "processName");
                if (v instanceof String && ((String) v).length() > 0) {
                    replacement = (String) v;
                }
                if (replacement == null) {
                    v = readFieldValue(appInfo, "packageName");
                    if (v instanceof String && ((String) v).length() > 0) {
                        replacement = (String) v;
                    }
                }
            }
            if (replacement == null) {
                Object v = readFieldValue(component, "packageName");
                if (v instanceof String && ((String) v).length() > 0) {
                    replacement = (String) v;
                }
            }
            if (replacement == null) {
                // Adapter-built ComponentInfo can have neither applicationInfo nor
                // packageName set.  Every component of this app runs in this process, so
                // the bound process name is the correct default.
                replacement = currentProcessName();
            }
            if (replacement != null) {
                processName.set(component, replacement);
                return fixed + 1;
            }
        } catch (Throwable ignored) {
        }
        return fixed;
    }

    private static String sProcessName;

    private static String currentProcessName() {
        if (sProcessName != null) {
            return sProcessName;
        }
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object v = atClass.getMethod("currentProcessName").invoke((Object) null);
            if (v instanceof String && ((String) v).length() > 0) {
                sProcessName = (String) v;
            }
        } catch (Throwable ignored) {
        }
        if (sProcessName == null) {
            try {
                Class<?> atClass = Class.forName("android.app.ActivityThread");
                Object v = atClass.getMethod("currentPackageName").invoke((Object) null);
                if (v instanceof String && ((String) v).length() > 0) {
                    sProcessName = (String) v;
                }
            } catch (Throwable ignored) {
            }
        }
        return sProcessName;
    }

    private static Object readField(Object target, String name) {
        return readFieldValue(target, name);
    }

    private static Object readFieldValue(Object target, String name) {
        if (target == null) {
            return null;
        }
        Field f = findField(target.getClass(), name);
        if (f == null) {
            return null;
        }
        try {
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... paramTypes) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, paramTypes);
            } catch (NoSuchMethodException ignored) {}
        }
        return null;
    }

    private static Field findField(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            }
        }
        return null;
    }
}
