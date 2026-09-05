package westlake.tls;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;

/**
 * The class named by the {@code ssl.SocketFactory.provider} security property.
 *
 * security.properties on this board points that property at
 * com.android.org.conscrypt.OpenSSLSocketFactoryImpl, which is not present, so
 * SSLSocketFactory.getDefault() would otherwise depend on whatever fallback the
 * platform happens to implement.  Naming this class instead makes the default
 * factory resolve deterministically onto the BouncyCastle context that
 * {@link TlsBootstrap} installs.
 *
 * Must keep a public no-arg constructor: JSSE instantiates it by name.
 */
public final class WlSSLSocketFactory extends SSLSocketFactory {

    private final SSLSocketFactory mDelegate;

    public WlSSLSocketFactory() {
        SSLSocketFactory d = null;
        try {
            SSLContext ctx = TlsBootstrap.context();
            if (ctx == null) {
                TlsBootstrap.install();
                ctx = TlsBootstrap.context();
            }
            if (ctx != null) d = ctx.getSocketFactory();
        } catch (Throwable t) {
            TlsBootstrap.log("WlSSLSocketFactory init failed", t);
        }
        mDelegate = d;
    }

    private SSLSocketFactory delegate() {
        if (mDelegate == null) {
            throw new IllegalStateException(
                    "TLS not installed: " + TlsBootstrap.status());
        }
        return mDelegate;
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return delegate().getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return delegate().getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose)
            throws IOException {
        return delegate().createSocket(s, host, port, autoClose);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return delegate().createSocket(host, port);
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
            throws IOException {
        return delegate().createSocket(host, port, localHost, localPort);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return delegate().createSocket(host, port);
    }

    @Override
    public Socket createSocket(InetAddress address, int port,
                               InetAddress localAddress, int localPort) throws IOException {
        return delegate().createSocket(address, port, localAddress, localPort);
    }

    @Override
    public Socket createSocket() throws IOException {
        return delegate().createSocket();
    }
}
