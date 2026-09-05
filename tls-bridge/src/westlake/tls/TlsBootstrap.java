package westlake.tls;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.security.KeyStore;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Real TLS for the Westlake OpenHarmony adapter.
 *
 * WHY THIS EXISTS
 * ---------------
 * The adapter ships AOSP's libcore but none of its TLS.  Concretely, on the board:
 *
 *   core-oj.jar      has the javax.net.ssl *API* (SSLContext, SSLSocket, SSLEngine)
 *                    but ZERO sun.security.ssl -- AOSP deletes OpenJDK's JSSE because
 *                    conscrypt is supposed to replace it.
 *   bouncycastle.jar is AOSP's stripped bcprov: JCE crypto primitives only.  No
 *                    org.bouncycastle.jsse, no org.bouncycastle.tls.
 *   conscrypt        absent entirely -- no classes, and no libjavacrypto.so.
 *
 * yet java/security/security.properties still declares:
 *
 *   security.provider.1 = com.android.org.conscrypt.OpenSSLProvider     <- missing
 *   security.provider.4 = com.android.org.conscrypt.JSSEProvider        <- missing
 *   ssl.SocketFactory.provider = ...conscrypt.OpenSSLSocketFactoryImpl  <- missing
 *
 * So the only providers that actually load are BouncyCastle (crypto) and
 * sun.security.provider.CertPathProvider (path validation).  There is no
 * SSLContext implementation at all: SSLContext.getInstance("TLS") throws
 * NoSuchAlgorithmException, which is what stops every TTNet/okhttp DoConnect and
 * leaves the Toutiao feed empty.
 *
 * WHAT THIS DOES
 * --------------
 * Installs upstream BouncyCastle's pure-Java JSSE (bctls) as a real TLS 1.2/1.3
 * provider.  Pure Java is the right call here: there is no OHOS NDK in this
 * workspace to cross-compile conscrypt's libjavacrypto.so, and BigInteger on ART is
 * already native-backed, so the asymmetric handshake maths does not pay the -Xint
 * interpreter tax.
 *
 * Two details that this file exists to get right:
 *
 *  1. PROVIDER NAME COLLISION.  Upstream org.bouncycastle...BouncyCastleProvider
 *     also calls itself "BC", exactly like the AOSP one already on the boot
 *     classpath, and Security.addProvider() silently no-ops on a duplicate name.
 *     We rename our instance to "WLBC" (reflection on Provider.name) so both can
 *     coexist, and hand it to BouncyCastleJsseProvider explicitly rather than
 *     relying on a global lookup that could bind to AOSP's stripped provider.
 *
 *  2. DEFAULT TRUST STORE.  okhttp does
 *         TrustManagerFactory.getInstance("PKIX").init((KeyStore) null)
 *     i.e. it asks for the *platform* trust anchors.  BC resolves that through
 *     javax.net.ssl.trustStore* system properties and would otherwise look for
 *     $JAVA_HOME/lib/security/cacerts, which does not exist here.  So we build a
 *     PKCS12 truststore from the board's own 133 PEM roots and point those
 *     properties at it, which makes the null-KeyStore path work for app code we
 *     do not control.
 *
 * Runs unmodified on a desktop JDK (see main) and on ART, so the crypto/trust
 * design can be validated on the host before it ever reaches the board.
 */
public final class TlsBootstrap {

    public static final String TAG = "[WL-TLS]";

    /** Name we give our copy of BouncyCastle, to dodge AOSP's "BC". */
    public static final String CRYPTO_PROVIDER_NAME = "WLBC";

    private static final String BC_PROVIDER_CLASS =
            "org.bouncycastle.jce.provider.BouncyCastleProvider";
    private static final String BC_JSSE_PROVIDER_CLASS =
            "org.bouncycastle.jsse.provider.BouncyCastleJsseProvider";

    /** Trust anchor sources, most specific first.  AOSP layout, then OH's own. */
    private static final String[] CA_DIRS = {
        "/data/pr03-74e6-portable/android/etc/security/cacerts",
        "/system/android/etc/security/cacerts",
        "/etc/security/certificates",
        "/system/etc/security/cacerts",
    };

    /** Somewhere we can drop the generated truststore. */
    private static final String[] SCRATCH_DIRS = {
        "/data/local/tmp", "/data/local", System.getProperty("java.io.tmpdir"),
    };

    private static volatile boolean sInstalled;
    private static volatile String sStatus = "not-installed";

    private static Provider sCrypto;
    /** True only when Security holds *our* instance under sCrypto.getName(). */
    private static boolean sCryptoRegistered;
    private static Provider sJsse;
    /** "prebuilt" when the baked PKCS12 was used, "pem" when we parsed the store. */
    private static String sTrustSource = "none";
    private static X509TrustManager sTrustManager;
    private static SSLContext sContext;

    private TlsBootstrap() { }

    public static boolean isInstalled() { return sInstalled; }
    public static String status() { return sStatus; }
    public static SSLContext context() { return sContext; }
    /** The real JSSE provider, for callers that must bypass a stale default. */
    public static Provider jsseProvider() { return sJsse; }
    public static X509TrustManager trustManager() { return sTrustManager; }
    public static Provider cryptoProvider() { return sCrypto; }

    /**
     * Idempotent.  Safe to call from several entry points; only the first does work.
     *
     * @return a one-line status string, also stashed in {@link #status()}.
     */
    public static synchronized String install() {
        if (sInstalled) return sStatus;
        long t0 = System.currentTimeMillis();
        try {
            // Must be set before anything can touch BC's SecureRandom.DEFAULT:
            // DRBG$Default's static initializer otherwise probes for conscrypt /
            // sun.security.provider.Sun, finds neither on this board, and throws --
            // see UrandomEntropySourceProvider for the full story.
            System.setProperty("org.bouncycastle.drbg.entropysource",
                    UrandomEntropySourceProvider.class.getName());

            // Prefer X25519 for key agreement.  In a pure-Java TLS stack on an
            // interpreted runtime the ECDHE scalar multiply dominates the
            // handshake, and BouncyCastle's X25519 is materially cheaper than its
            // SecP256R1 field arithmetic.  secp256r1 stays as a fallback so a
            // server that does not offer X25519 still connects (at the cost of a
            // HelloRetryRequest round trip).  Overridable from the command line.
            if (System.getProperty("jdk.tls.namedGroups") == null) {
                System.setProperty("jdk.tls.namedGroups", "x25519,secp256r1");
            }

            sCrypto = makeCryptoProvider();
            sJsse = makeJsseProvider(sCrypto);

            // Parsing 133 PEM roots costs real time under -Xint, and the answer
            // never changes, so prefer a truststore baked on the host.  The PEM
            // path stays as the fallback that makes this self-contained.
            int rootCount;
            KeyStore trustStore = loadPrebuiltTrustStore();
            if (trustStore != null) {
                rootCount = trustStore.size();
                sTrustSource = "prebuilt";
            } else {
                List<X509Certificate> roots = loadTrustAnchors();
                rootCount = roots.size();
                trustStore = buildTrustStore(roots);
                sTrustSource = "pem";
                // Make the platform-default path (TrustManagerFactory.init(null))
                // work for app code we do not control.  Best effort: if we cannot
                // write the file, our own default SSLContext still has the anchors.
                exportTrustStore(trustStore);
            }
            sTrustManager = buildTrustManager(trustStore);

            // Order matters: ahead of everything, so a bare getInstance("TLS") and a
            // bare getInstance("PKIX") both land on us.
            Security.insertProviderAt(sJsse, 1);

            sContext = SSLContext.getInstance("TLS", sJsse);
            sContext.init(null, new TrustManager[] { sTrustManager }, new SecureRandom());
            SSLContext.setDefault(sContext);
            HttpsURLConnection.setDefaultSSLSocketFactory(sContext.getSocketFactory());

            // security.properties still names a conscrypt class that is not here.
            // Do NOT point this at our own factory: SSLSocketFactory.getDefault()
            // resolves it with Class.forName / the system loader, neither of which
            // can see a class we loaded from a DexClassLoader, and the resulting
            // NoClassDefFoundError is an Error -- so libcore's `catch (Exception)`
            // lets it escape instead of falling back.  Blanking the property turns
            // that into a plain ClassNotFoundException, which *is* caught, and
            // getDefault() then falls through to the SSLContext we just installed.
            try {
                Security.setProperty("ssl.SocketFactory.provider", "");
                Security.setProperty("ssl.ServerSocketFactory.provider", "");
            } catch (Throwable ignored) { }

            maybeEnableJsseLogging();
            warmUp();

            sInstalled = true;
            sStatus = "ok providers=" + sCrypto.getName() + "/" + sJsse.getName()
                    + " roots=" + rootCount + " trust=" + sTrustSource
                    + " ms=" + (System.currentTimeMillis() - t0);
        } catch (Throwable t) {
            sStatus = "FAILED " + t;
            log("install failed", t);
        }
        log(sStatus);
        return sStatus;
    }

    /* ------------------------------------------------------------------ *
     * Providers
     * ------------------------------------------------------------------ */

    private static Provider makeCryptoProvider() throws Exception {
        Provider p = (Provider) Class.forName(BC_PROVIDER_CLASS)
                .getDeclaredConstructor().newInstance();
        // Rename before registering, or AOSP's "BC" wins and addProvider no-ops.
        // Reflection on Provider.name is blocked by module encapsulation on a
        // desktop JDK 17+ but works on ART, which has no module system.  Either
        // way we keep the instance and use it explicitly, so a failed rename only
        // costs us the by-name lookup below.
        if (!CRYPTO_PROVIDER_NAME.equals(p.getName())
                && !renameProvider(p, CRYPTO_PROVIDER_NAME)) {
            log("could not rename crypto provider (still '" + p.getName()
                    + "'); using explicit instance only");
        }
        if (Security.getProvider(p.getName()) == null) {
            Security.addProvider(p);
        }
        sCryptoRegistered = Security.getProvider(p.getName()) == p;
        return p;
    }

    /**
     * Provider.name is private with no setter.  It is a plain instance field on both
     * OpenJDK and AOSP, so reflection is enough; we never touch a static final.
     */
    private static boolean renameProvider(Provider p, String name) {
        try {
            Field f = Provider.class.getDeclaredField("name");
            f.setAccessible(true);
            f.set(p, name);
            return name.equals(p.getName());
        } catch (Throwable t) {
            return false;
        }
    }

    private static Provider makeJsseProvider(Provider crypto) throws Exception {
        // Bind the JSSE layer to *our* crypto instance explicitly.  The no-arg ctor
        // would go looking through the global provider list and could bind to AOSP's
        // stripped BC, which lacks pieces the handshake needs.
        return (Provider) Class.forName(BC_JSSE_PROVIDER_CLASS)
                .getConstructor(Provider.class).newInstance(crypto);
    }

    /* ------------------------------------------------------------------ *
     * Trust anchors
     * ------------------------------------------------------------------ */

    /** Reads every PEM block out of the board's CA directories. */
    public static List<X509Certificate> loadTrustAnchors() throws Exception {
        CertificateFactory cf = certificateFactory();
        List<X509Certificate> out = new ArrayList<X509Certificate>();
        List<String> seen = new ArrayList<String>();
        // Host-side validation runs the identical code against a copy of the
        // board's store; -Dwestlake.tls.caDir points at it.
        List<String> dirs = new ArrayList<String>();
        String override = System.getProperty("westlake.tls.caDir");
        if (override != null && override.length() > 0) dirs.add(override);
        dirs.addAll(Arrays.asList(CA_DIRS));
        for (int i = 0; i < dirs.size(); i++) {
            File dir = new File(dirs.get(i));
            if (!dir.isDirectory()) continue;
            File[] files = dir.listFiles();
            if (files == null) continue;
            Arrays.sort(files);
            for (int j = 0; j < files.length; j++) {
                if (!files[j].isFile()) continue;
                try {
                    byte[] raw = readAll(files[j]);
                    List<byte[]> pems = extractPemBlocks(raw);
                    for (int k = 0; k < pems.size(); k++) {
                        X509Certificate c = (X509Certificate) cf.generateCertificate(
                                new ByteArrayInputStream(pems.get(k)));
                        String key = c.getSubjectX500Principal().getName()
                                + "|" + c.getSerialNumber();
                        if (seen.contains(key)) continue;
                        seen.add(key);
                        out.add(c);
                    }
                } catch (Throwable t) {
                    // A single unparseable anchor must not sink the whole store.
                }
            }
            if (!out.isEmpty()) break;   // first directory that yields anchors wins
        }
        if (out.isEmpty()) throw new IOException("no trust anchors found in " + dirs);
        return out;
    }

    private static CertificateFactory certificateFactory() throws Exception {
        if (sCrypto != null) {
            try { return CertificateFactory.getInstance("X.509", sCrypto); }
            catch (Throwable ignored) { }
        }
        return CertificateFactory.getInstance("X.509");
    }

    /**
     * AOSP cacert files are a PEM block followed by an openssl-style text dump, and
     * some stores concatenate several.  Pull the blocks out rather than trusting a
     * stream reader to stop in the right place.
     */
    private static List<byte[]> extractPemBlocks(byte[] raw) {
        final String begin = "-----BEGIN CERTIFICATE-----";
        final String end = "-----END CERTIFICATE-----";
        String s = new String(raw, java.nio.charset.Charset.forName("US-ASCII"));
        List<byte[]> out = new ArrayList<byte[]>();
        int from = 0;
        while (true) {
            int b = s.indexOf(begin, from);
            if (b < 0) break;
            int e = s.indexOf(end, b);
            if (e < 0) break;
            e += end.length();
            out.add(s.substring(b, e).getBytes(java.nio.charset.Charset.forName("US-ASCII")));
            from = e;
        }
        return out;
    }

    private static byte[] readAll(File f) throws IOException {
        InputStream in = new java.io.FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
    }

    /** Where a host-baked truststore may sit, most specific first. */
    private static final String[] PREBUILT_TRUSTSTORES = {
        "/data/local/tmp/wl-cacerts.p12",
        "/data/pr03-74e6-portable/android/framework/wl-cacerts.p12",
    };

    /**
     * @return the baked truststore, or null if none is readable -- in which case
     *         the caller falls back to parsing the board's PEM store.
     */
    private static KeyStore loadPrebuiltTrustStore() {
        List<String> paths = new ArrayList<String>();
        String override = System.getProperty("westlake.tls.trustStore");
        if (override != null && override.length() > 0) paths.add(override);
        paths.addAll(Arrays.asList(PREBUILT_TRUSTSTORES));
        for (int i = 0; i < paths.size(); i++) {
            File f = new File(paths.get(i));
            if (!f.isFile() || !f.canRead()) continue;
            InputStream in = null;
            try {
                in = new java.io.FileInputStream(f);
                KeyStore ks = KeyStore.getInstance("PKCS12", sCrypto);
                ks.load(in, TRUSTSTORE_PASSWORD);
                // Point the platform-default path at the same file, so app code
                // doing TrustManagerFactory.init(null) sees these anchors too.
                System.setProperty("javax.net.ssl.trustStore", f.getAbsolutePath());
                System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
                System.setProperty("javax.net.ssl.trustStorePassword",
                        new String(TRUSTSTORE_PASSWORD));
                if (sCryptoRegistered) {
                    System.setProperty("javax.net.ssl.trustStoreProvider", sCrypto.getName());
                }
                log("using prebuilt truststore " + f.getAbsolutePath());
                return ks;
            } catch (Throwable t) {
                log("prebuilt truststore " + f + " unusable: " + t);
            } finally {
                if (in != null) try { in.close(); } catch (IOException ignored) { }
            }
        }
        return null;
    }

    private static KeyStore buildTrustStore(List<X509Certificate> roots) throws Exception {
        // PKCS12 from our own provider: no dependence on KeyStore.getDefaultType()
        // ("BKS" here), whose provider may or may not be present.
        KeyStore ks;
        try {
            ks = KeyStore.getInstance("PKCS12", sCrypto);
        } catch (Throwable t) {
            ks = KeyStore.getInstance(KeyStore.getDefaultType());
        }
        ks.load(null, null);
        for (int i = 0; i < roots.size(); i++) {
            ks.setCertificateEntry("wlca-" + i, roots.get(i));
        }
        return ks;
    }

    private static X509TrustManager buildTrustManager(KeyStore ks) throws Exception {
        TrustManagerFactory tmf;
        try {
            tmf = TrustManagerFactory.getInstance("PKIX", sJsse);
        } catch (Throwable t) {
            tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        }
        tmf.init(ks);
        TrustManager[] tms = tmf.getTrustManagers();
        for (int i = 0; i < tms.length; i++) {
            if (tms[i] instanceof X509TrustManager) return (X509TrustManager) tms[i];
        }
        throw new IllegalStateException("no X509TrustManager from " + tmf.getProvider());
    }

    /** Char-for-char the password BC will read back via the system property. */
    private static final char[] TRUSTSTORE_PASSWORD = "changeit".toCharArray();

    private static void exportTrustStore(KeyStore ks) {
        for (int i = 0; i < SCRATCH_DIRS.length; i++) {
            if (SCRATCH_DIRS[i] == null) continue;
            File dir = new File(SCRATCH_DIRS[i]);
            if (!dir.isDirectory() || !dir.canWrite()) continue;
            File out = new File(dir, "wl-cacerts.p12");
            OutputStream os = null;
            try {
                os = new FileOutputStream(out);
                ks.store(os, TRUSTSTORE_PASSWORD);
                os.close();
                os = null;
                System.setProperty("javax.net.ssl.trustStore", out.getAbsolutePath());
                System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
                System.setProperty("javax.net.ssl.trustStorePassword",
                        new String(TRUSTSTORE_PASSWORD));
                // Only pin the provider when the name really resolves to our
                // instance; otherwise it would point BC at AOSP's stripped "BC".
                // PKCS12 is a standard format, so leaving it unset and letting
                // JCA pick a reader is the safer fallback.
                if (sCryptoRegistered) {
                    System.setProperty("javax.net.ssl.trustStoreProvider", sCrypto.getName());
                } else {
                    System.clearProperty("javax.net.ssl.trustStoreProvider");
                }
                log("truststore exported to " + out.getAbsolutePath());
                return;
            } catch (Throwable t) {
                log("truststore export to " + out + " failed: " + t);
            } finally {
                if (os != null) try { os.close(); } catch (IOException ignored) { }
            }
        }
        log("no writable scratch dir; default-trust path may be unavailable");
    }

    /** Host used to warm the TLS path; the one the app talks to most. */
    private static final String WARMUP_HOST = "api.toutiaoapi.com";

    /**
     * Run one throwaway handshake so the TLS code path is warm before any app
     * request reaches it.
     *
     * Measured on the board, three back-to-back handshakes from a fresh process:
     *
     *     hs1=5195ms   hs2=540ms   hs3=165ms
     *
     * i.e. the famous "5 seconds per handshake" is almost entirely the one-off
     * cost of loading and verifying BouncyCastle's TLS path under the interpreter,
     * not the crypto.  That distinction decides whether the app works: it opens
     * dozens of connections at once the moment the gate releases, so without this
     * they all pay the cold cost simultaneously, contend for four A55 cores, and
     * get killed together when the app's deadline fires (observed as
     * user_canceled(90) alerts on 27 of 30 handshakes).
     *
     * Paying ~5s here, inside install() and therefore before the gate opens, buys
     * every subsequent handshake the warm price.  Failure is harmless -- the point
     * is to touch the code path, not to reach the network.
     */
    private static void warmUp() {
        if ("false".equals(System.getProperty("westlake.tls.warmup"))) return;
        warmUpDirect();
        warmUpLayered();
    }

    /** Subject of the peer's leaf certificate, for confirming SNI took effect. */
    private static String peerSubject(SSLSocket s) {
        try {
            return ((X509Certificate) s.getSession().getPeerCertificates()[0])
                    .getSubjectX500Principal().getName();
        } catch (Throwable t) {
            return "<" + t + ">";
        }
    }

    /** Warms the plain createSocket() entry point. */
    private static void warmUpDirect() {
        long t0 = System.currentTimeMillis();
        SSLSocket s = null;
        try {
            // createSocket(host, port), not the no-arg form: only this carries the
            // peer name into SNI, without which a CDN edge answers with its default
            // certificate rather than the one for this host.
            s = (SSLSocket) sContext.getSocketFactory().createSocket(WARMUP_HOST, 443);
            s.setSoTimeout(10000);
            s.startHandshake();
            log("warmup(direct) ok in " + (System.currentTimeMillis() - t0) + "ms"
                    + " peer=" + peerSubject(s));
        } catch (Throwable t) {
            log("warmup(direct) failed after " + (System.currentTimeMillis() - t0)
                    + "ms (harmless, path is still warm): " + t);
        } finally {
            if (s != null) try { s.close(); } catch (IOException ignored) { }
        }
    }

    /**
     * Warms the entry point okhttp actually uses: TLS layered over an already
     * connected plain socket, createSocket(Socket, host, port, autoClose).  It
     * shares the crypto with the direct path but not the wrapper code, and after
     * run10 that wrapper was still the cold one on the app's first request.
     */
    private static void warmUpLayered() {
        long t0 = System.currentTimeMillis();
        java.net.Socket raw = null;
        SSLSocket s = null;
        try {
            raw = new java.net.Socket();
            raw.connect(new java.net.InetSocketAddress(WARMUP_HOST, 443), 10000);
            s = (SSLSocket) sContext.getSocketFactory()
                    .createSocket(raw, WARMUP_HOST, 443, true);
            s.setSoTimeout(10000);
            s.startHandshake();
            log("warmup(layered) ok in " + (System.currentTimeMillis() - t0) + "ms"
                    + " peer=" + peerSubject(s));
        } catch (Throwable t) {
            log("warmup(layered) failed after " + (System.currentTimeMillis() - t0)
                    + "ms (harmless): " + t);
        } finally {
            if (s != null) {
                try { s.close(); } catch (IOException ignored) { }
            } else if (raw != null) {
                try { raw.close(); } catch (IOException ignored) { }
            }
        }
    }

    /** The hosts the app actually talks to, taken from its own connection log. */
    private static final String[] PROBE_HOSTS = {
        "api.toutiaoapi.com", "is.snssdk.com", "ib.snssdk.com",
        "abtest-ch.snssdk.com", "gecko.zijieapi.com", "dm.toutiao.com",
    };

    /**
     * For each host the app uses: complete a handshake and report whether the
     * certificate is actually valid for that hostname.
     *
     * okhttp's connectTls() runs its hostname verifier and certificate pinner
     * *after* startHandshake() returns, and on any failure its finally-block does
     * closeQuietly(sslSocket) -- which is precisely the user_canceled(90) alert we
     * see on nearly every app connection.  So "the handshake succeeded" tells us
     * nothing; the question is whether the presented certificate matches the name
     * that was asked for.  Our own probes kept coming back as
     * CN=*.certfallback.com (what Alibaba Cloud CDN serves when the SNI name is
     * not provisioned on that edge), which would fail verification for every
     * toutiao/snssdk name -- and would be swallowed exactly the way we observe.
     */
    public static String probeHosts() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < PROBE_HOSTS.length; i++) {
            String host = PROBE_HOSTS[i];
            java.net.Socket raw = null;
            SSLSocket s = null;
            String step = "connect";
            try {
                // Reproduce okhttp's connectTls() exactly, one step at a time, so a
                // failure names the step instead of just closing the socket.
                //
                // Note the socket is created WITH the hostname: an SSLSocket built
                // by the no-arg createSocket() and then connect()ed to an address
                // has no peer name to put in SNI, so a CDN edge answers with its
                // default certificate.  That is what produced the bogus
                // "*.certfallback.com MISMATCH" readings -- an artifact of the
                // probe, not something the app ever hit (okhttp always passes the
                // host, and BC logged "Server accepted SNI?: true" for its
                // connections).
                raw = new java.net.Socket();
                raw.connect(new java.net.InetSocketAddress(host, 443), 10000);

                step = "createSocket(layered,+SNI)";
                s = (SSLSocket) sContext.getSocketFactory().createSocket(raw, host, 443, true);
                s.setSoTimeout(10000);

                step = "configureSecureSocket";
                // okhttp's ConnectionSpec.MODERN_TLS restricts the socket before
                // handshaking; if nothing intersects what BC enables it throws
                // UnknownServiceException before any bytes go out.
                String[] enabled = s.getEnabledCipherSuites();
                String[] protos = s.getEnabledProtocols();

                step = "configureTlsExtensions";
                javax.net.ssl.SSLParameters p = s.getSSLParameters();
                s.setSSLParameters(p);

                step = "startHandshake";
                s.startHandshake();

                step = "verify";
                X509Certificate c = (X509Certificate) s.getSession().getPeerCertificates()[0];
                String subject = c.getSubjectX500Principal().getName();
                List<String> sans = subjectAltNames(c);
                boolean match = hostMatches(host, subject, sans);
                log("probe " + host + " -> " + (match ? "MATCH" : "MISMATCH")
                        + " proto=" + s.getSession().getProtocol()
                        + " cipher=" + s.getSession().getCipherSuite()
                        + " enabledSuites=" + enabled.length
                        + " enabledProtos=" + java.util.Arrays.toString(protos)
                        + " subject=" + subject + " san=" + sans);
                out.append(host).append('=').append(match ? "ok" : "MISMATCH").append(' ');
            } catch (Throwable t) {
                log("probe " + host + " -> FAIL at " + step + ": " + t);
                out.append(host).append("=fail(").append(step).append(") ");
            } finally {
                if (s != null) {
                    try { s.close(); } catch (IOException ignored) { }
                } else if (raw != null) {
                    try { raw.close(); } catch (IOException ignored) { }
                }
            }
        }
        return out.toString();
    }

    private static List<String> subjectAltNames(X509Certificate c) {
        List<String> out = new ArrayList<String>();
        try {
            java.util.Collection<List<?>> alt = c.getSubjectAlternativeNames();
            if (alt != null) {
                for (List<?> entry : alt) {
                    if (entry.size() >= 2 && entry.get(1) instanceof String) {
                        out.add((String) entry.get(1));
                    }
                }
            }
        } catch (Throwable ignored) { }
        return out;
    }

    /** The wildcard rule okhttp's OkHostnameVerifier uses, in miniature. */
    private static boolean hostMatches(String host, String subject, List<String> sans) {
        List<String> candidates = new ArrayList<String>(sans);
        int cn = subject.indexOf("CN=");
        if (cn >= 0) {
            int end = subject.indexOf(',', cn);
            candidates.add(subject.substring(cn + 3, end < 0 ? subject.length() : end));
        }
        String h = host.toLowerCase(java.util.Locale.US);
        for (int i = 0; i < candidates.size(); i++) {
            String p = candidates.get(i).toLowerCase(java.util.Locale.US);
            if (p.equals(h)) return true;
            if (p.startsWith("*.")) {
                String suffix = p.substring(1);           // ".example.com"
                // A wildcard covers exactly one label, so what precedes the suffix
                // must not itself contain a dot.
                if (h.endsWith(suffix)
                        && h.lastIndexOf('.', h.length() - suffix.length() - 1) < 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Names whoever cancels a handshake.
     *
     * Most of the app's connections die as a user_canceled(90) alert, which in TLS
     * means the peer closed the socket mid-handshake -- i.e. the app did it, not
     * the network and not us.  BouncyCastle raises that alert synchronously on the
     * calling thread, so a stack captured here is still inside the caller's
     * close(), which is the only way to see which TTNet code path is giving up
     * (the app swallows the resulting IOException, so nothing else records it).
     */
    private static final class CancelTracer extends java.util.logging.Handler {
        private int mRemaining = 3;   // a few samples is plenty; 27/run would flood

        @Override public void publish(java.util.logging.LogRecord record) {
            if (record == null || mRemaining <= 0) return;
            String msg = record.getMessage();
            if (msg == null || msg.indexOf("user_canceled") < 0) return;
            mRemaining--;
            System.err.println(TAG + " cancel-trace: " + msg);
            new Throwable("stack at handshake cancellation").printStackTrace();
        }

        @Override public void flush() { }
        @Override public void close() { }
    }

    /** Presence of this file turns on BouncyCastle's own JSSE trace. */
    private static final String DEBUG_MARKER = "/data/local/tmp/wl-tls-debug";

    /**
     * BouncyCastle reports handshake alerts and failures through java.util.logging,
     * but only above FINE, so a failed handshake otherwise shows up as nothing more
     * than "notifyConnectionClosed" with no reason.  App code that swallows the
     * IOException then leaves no trace at all.  Opt in to the full trace when the
     * marker file is present.
     */
    private static void maybeEnableJsseLogging() {
        if (!new File(DEBUG_MARKER).isFile()) return;
        try {
            java.util.logging.Logger bc = java.util.logging.Logger.getLogger("org.bouncycastle");
            bc.setLevel(java.util.logging.Level.ALL);
            java.util.logging.ConsoleHandler h = new java.util.logging.ConsoleHandler();
            h.setLevel(java.util.logging.Level.ALL);
            bc.addHandler(h);
            bc.addHandler(new CancelTracer());
            // Without this every record is emitted twice -- once by our handler and
            // once by the root logger's -- which silently doubles every count taken
            // off this log.
            bc.setUseParentHandlers(false);
            log("BouncyCastle JSSE trace enabled (" + DEBUG_MARKER + " present)");
        } catch (Throwable t) {
            log("could not enable JSSE trace", t);
        }
    }

    /* ------------------------------------------------------------------ *
     * Self test
     * ------------------------------------------------------------------ */

    /**
     * Exercises the same two paths app code uses: a raw SSLSocket handshake (what
     * okhttp drives) and HttpsURLConnection.  Returns a single log-friendly line.
     */
    public static String selfTest(String host, int port, String httpsUrl) {
        StringBuilder sb = new StringBuilder();
        // Certificate-vs-hostname check for every host the app uses.  This is the
        // one that explains the user_canceled alerts, so run it first.
        probeHosts();
        // 0. Three back-to-back handshakes.  The first one drags in the whole BC
        //    TLS code path (class load + verify), which on an interpreted runtime
        //    can dwarf the crypto itself, so a single measurement cannot tell
        //    "this stack is slow" from "this stack is cold".  The app opens dozens
        //    of connections, so what matters is the steady-state number.
        for (int i = 0; i < 3; i++) {
            SSLSocket w = null;
            try {
                long c0 = System.currentTimeMillis();
                w = (SSLSocket) sContext.getSocketFactory().createSocket(host, port);
                w.setSoTimeout(20000);
                long c1 = System.currentTimeMillis();
                w.startHandshake();
                sb.append("hs").append(i + 1).append('=')
                  .append(System.currentTimeMillis() - c1).append("ms ");
            } catch (Throwable t) {
                sb.append("hs").append(i + 1).append("=FAIL(").append(t).append(") ");
            } finally {
                if (w != null) try { w.close(); } catch (IOException ignored) { }
            }
        }
        sb.append("| ");
        // 1. raw handshake
        SSLSocket s = null;
        try {
            long t0 = System.currentTimeMillis();
            s = (SSLSocket) sContext.getSocketFactory().createSocket(host, port);
            s.setSoTimeout(20000);
            // Time the handshake on its own: on an interpreted runtime this is the
            // number that decides whether the app's own deadlines can be met, and
            // TCP connect / DNS would otherwise hide it.
            long tConnected = System.currentTimeMillis();
            s.startHandshake();
            long tShook = System.currentTimeMillis();
            sb.append("handshake=OK ").append(s.getSession().getProtocol())
              .append("/").append(s.getSession().getCipherSuite())
              .append(" peer=").append(((X509Certificate) s.getSession()
                      .getPeerCertificates()[0]).getSubjectX500Principal().getName())
              .append(" connectMs=").append(tConnected - t0)
              .append(" handshakeMs=").append(tShook - tConnected)
              .append(" ms=").append(tShook - t0);
        } catch (Throwable t) {
            sb.append("handshake=FAIL ").append(t);
        } finally {
            if (s != null) try { s.close(); } catch (IOException ignored) { }
        }
        // 2. HttpsURLConnection, the okhttp-independent path
        try {
            long t0 = System.currentTimeMillis();
            HttpsURLConnection c = (HttpsURLConnection) new URL(httpsUrl).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            int code = c.getResponseCode();
            InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            int n = 0;
            if (in != null) {
                byte[] buf = new byte[4096];
                int r;
                while ((r = in.read(buf)) > 0) { n += r; if (n > 65536) break; }
                in.close();
            }
            sb.append(" | https=").append(code).append(" bytes=").append(n)
              .append(" ms=").append(System.currentTimeMillis() - t0);
        } catch (Throwable t) {
            sb.append(" | https=FAIL ").append(t);
        }
        // 3. the okhttp path: platform default trust anchors via a null KeyStore,
        //    then a default-provider SSLContext.  This is what TTNet actually
        //    drives, and it is the one that depends on the exported truststore
        //    rather than on the context we install ourselves.
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            TrustManager[] tms = tmf.getTrustManagers();
            int anchors = -1;
            for (int i = 0; i < tms.length; i++) {
                if (tms[i] instanceof X509TrustManager) {
                    anchors = ((X509TrustManager) tms[i]).getAcceptedIssuers().length;
                    break;
                }
            }
            SSLContext c2 = SSLContext.getInstance("TLS");
            c2.init(null, tms, null);
            SSLSocket s2 = (SSLSocket) c2.getSocketFactory().createSocket();
            try {
                s2.connect(new java.net.InetSocketAddress(host, port), 15000);
                s2.setSoTimeout(20000);
                s2.startHandshake();
                sb.append(" | okhttpPath=OK anchors=").append(anchors)
                  .append(" ").append(s2.getSession().getProtocol());
            } finally {
                try { s2.close(); } catch (IOException ignored) { }
            }
        } catch (Throwable t) {
            sb.append(" | okhttpPath=FAIL ").append(t);
        }
        String line = sb.toString();
        log("selftest " + line);
        return line;
    }

    /* ------------------------------------------------------------------ *
     * Plumbing
     * ------------------------------------------------------------------ */

    static void log(String msg) {
        System.err.println(TAG + " " + msg);
    }

    static void log(String msg, Throwable t) {
        System.err.println(TAG + " " + msg + ": " + t);
        t.printStackTrace();
    }

    /**
     * Host-side: bake the board's PEM roots into the PKCS12 the board loads, so
     * ART never pays for parsing them.  Written by the same code that reads it.
     */
    private static void bake(String caDir, String outPath) throws Exception {
        sCrypto = makeCryptoProvider();
        System.setProperty("westlake.tls.caDir", caDir);
        List<X509Certificate> roots = loadTrustAnchors();
        KeyStore ks = buildTrustStore(roots);
        OutputStream os = new FileOutputStream(outPath);
        try {
            ks.store(os, TRUSTSTORE_PASSWORD);
        } finally {
            os.close();
        }
        System.out.println("baked " + roots.size() + " roots -> " + outPath
                + " (" + new File(outPath).length() + " bytes)");
    }

    /** Host-side harness: same code path the board takes. */
    public static void main(String[] args) throws Exception {
        if (args.length >= 3 && "bake".equals(args[0])) {
            bake(args[1], args[2]);
            return;
        }
        String host = args.length > 0 ? args[0] : "dm.toutiao.com";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 443;
        String url = args.length > 2 ? args[2] : "https://" + host + "/";
        System.out.println("install: " + install());
        System.out.println("providers:");
        Provider[] ps = Security.getProviders();
        for (int i = 0; i < ps.length; i++) {
            System.out.println("  " + (i + 1) + ". " + ps[i].getName() + " " + ps[i].getVersion());
        }
        System.out.println("selftest: " + selfTest(host, port, url));
    }
}
