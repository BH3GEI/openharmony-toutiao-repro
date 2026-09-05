package com.android.org.conscrypt;

/**
 * Presence-only shim for okhttp's platform detection.
 *
 * Both okhttp entry points gate on this class existing:
 *
 *   Android10Platform.buildIfSupported():
 *       ClassLoaderHelper.findClass("com.android.org.conscrypt.SSLParametersImpl")
 *   AndroidPlatform.buildIfSupported():
 *       same, falling back to org.apache.harmony...SSLParametersImpl, else null
 *
 * and findAndroidPlatform() throws NullPointerException("No platform found on
 * Android") when both return null -- which is what kills every TTNet DoConnect
 * on this adapter, because its boot classpath ships no conscrypt at all.
 *
 * okhttp only ever uses the returned Class as a reflection token:
 *   readFieldOrNull(sslSocketFactory, sslParametersClass, "sslParameters")
 * which is null-tolerant.  So an empty class with the right name is enough to
 * get past detection; the actual TLS still comes from whatever JSSE provider
 * the platform registers.
 *
 * Loaded through Mira's ClassLoaderHelper.findClass, i.e. the *app* class
 * loader -- so this ships as an extra dex in base.apk rather than needing a
 * boot classpath change.
 */
public class SSLParametersImpl {
}
