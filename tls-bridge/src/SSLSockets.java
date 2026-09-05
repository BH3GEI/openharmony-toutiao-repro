package android.net.ssl;

import javax.net.ssl.SSLSocket;

/**
 * Stand-in for the AOSP API of the same name, which this adapter does not ship.
 *
 * android.net.ssl is part of conscrypt's public surface and arrives with the
 * conscrypt mainline module; a scan of all five dex files in the board's
 * framework.jar finds zero references to "android/net/ssl", so the package simply
 * does not exist here.
 *
 * okhttp selects its Android10Platform purely on Build.VERSION.SDK_INT >= 29 --
 * it does not probe for this class -- and then calls
 * SSLSockets.setUseSessionTickets() while configuring TLS extensions.  With the
 * class missing that throws
 *     NoClassDefFoundError: Failed resolution of: Landroid/net/ssl/SSLSockets;
 * which is exactly where TTNet's DoConnect lands once the construct-only shim is
 * out of the way.
 *
 * Both methods are safe to neutralise:
 *
 *   setUseSessionTickets  is a performance hint.  BouncyCastle's JSSE manages
 *                         session resumption itself, so ignoring it costs a full
 *                         handshake on reconnect and nothing else.
 *
 *   isSupportedSocket     asks "is this a Conscrypt socket?".  Ours never is, and
 *                         answering false is also the conservative choice: it
 *                         keeps okhttp off the API-29 ALPN accessors
 *                         (SSLSocket.getApplicationProtocol), which BouncyCastle's
 *                         Java 8 build does not necessarily override.  The cost is
 *                         HTTP/1.1 instead of negotiated HTTP/2, which is fine for
 *                         fetching the feed.
 */
public class SSLSockets {

    private SSLSockets() { }

    public static boolean isSupportedSocket(SSLSocket socket) {
        return false;
    }

    public static void setUseSessionTickets(SSLSocket socket, boolean useSessionTickets) {
        // no-op; see class comment
    }
}
