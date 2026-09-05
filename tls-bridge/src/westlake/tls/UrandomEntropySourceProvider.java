package westlake.tls;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.bouncycastle.crypto.prng.EntropySource;
import org.bouncycastle.crypto.prng.EntropySourceProvider;

/**
 * Entropy for BouncyCastle's DRBG, read straight from /dev/urandom.
 *
 * WHY THIS EXISTS
 * ---------------
 * BC registers its own SecureRandom as "SecureRandom.DEFAULT" ->
 * org.bouncycastle.jcajce.provider.drbg.DRBG$Default, and BCJSSE asks its crypto
 * provider for exactly that when it needs randomness for a handshake.
 *
 * DRBG$Default holds
 *     private static final SecureRandom random = createBaseRandom(true);
 * and createBaseRandom(), with no override configured, bootstraps its entropy by
 * probing a hardcoded list of providers (DRBG.initialEntropySourceNames):
 *
 *     sun.security.provider.Sun                        - not in AOSP libcore
 *     org.apache.harmony...CryptoProvider              - not present
 *     com.android.org.conscrypt.OpenSSLProvider        - absent (the whole reason
 *     org.conscrypt.OpenSSLProvider                      this bridge exists)
 *
 * On this board all four are missing, so that static initializer throws and every
 * later touch of the class surfaces as
 *     NoClassDefFoundError: org.bouncycastle.jcajce.provider.drbg.DRBG$Default
 * which is what killed TTNet's DoConnect even after the provider was installed.
 *
 * Naming this class in the org.bouncycastle.drbg.entropysource system property
 * makes createBaseRandom() take its first branch and build an SP800-90A hash DRBG
 * over this source, so the probing never runs.  BC instantiates it by name through
 * ClassUtil.loadClass(DRBG.class, ...), i.e. with DRBG's own class loader, which is
 * the same dex this class ships in.
 *
 * Reading the device directly is also what keeps this safe: any fallback that went
 * back through JCA could re-enter the provider we are still constructing.
 *
 * Must keep a public no-arg constructor.
 */
public final class UrandomEntropySourceProvider implements EntropySourceProvider {

    private static final String DEVICE = "/dev/urandom";

    public UrandomEntropySourceProvider() { }

    @Override
    public EntropySource get(final int bitsRequired) {
        final int byteCount = (bitsRequired + 7) / 8;
        return new EntropySource() {
            @Override public boolean isPredictionResistant() {
                // /dev/urandom is continuously reseeded by the kernel pool.
                return true;
            }

            @Override public int entropySize() {
                return bitsRequired;
            }

            @Override public byte[] getEntropy() {
                byte[] out = new byte[byteCount];
                InputStream in = null;
                try {
                    in = new FileInputStream(DEVICE);
                    new DataInputStream(in).readFully(out);
                    return out;
                } catch (IOException e) {
                    throw new IllegalStateException(
                            "cannot read entropy from " + DEVICE, e);
                } finally {
                    if (in != null) try { in.close(); } catch (IOException ignored) { }
                }
            }
        };
    }
}
