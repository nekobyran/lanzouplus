package cc.nkbr.lanzouplus;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Generic LZP1 codec for repository distribution source files.
 *
 * <p>Byte layout: ASCII {@code LZP1} (4 bytes), random IV (16 bytes), AES-128-CTR
 * ciphertext. The ciphertext decrypts to a zlib Deflate level 9 stream whose payload is
 * the UTF-8 v2 source JSON. AES runs in {@code NoPadding} CTR mode with a 128-bit
 * big-endian counter, so the transform is byte addressed and the ciphertext length always
 * equals the deflated length. The public format key is ASCII {@code LanzouPlusMarket}
 * (16 bytes, a valid AES-128 key); it is a published protocol constant and carries no
 * confidentiality role. No Android or platform specific API is used, so the class also
 * compiles and runs on a plain JVM.
 */
public final class MarketSourceCodec {

    /** ASCII magic that opens every LZP1 container. */
    public static final byte[] MAGIC = {'L', 'Z', 'P', '1'};

    /** Published protocol key, ASCII {@code LanzouPlusMarket}. */
    public static final String FORMAT_KEY = "LanzouPlusMarket";

    /** IV length in bytes. */
    public static final int IV_LENGTH = 16;

    /** Header size: 4-byte magic plus 16-byte IV. */
    public static final int HEADER_LENGTH = MAGIC.length + IV_LENGTH;

    /** Maximum UTF-8 plaintext size of a source document: 8 MiB. */
    public static final int MAX_PLAINTEXT_BYTES = 8 * 1024 * 1024;

    private static final int ZLIB_LEVEL = Deflater.BEST_COMPRESSION;

    private static final SecureRandom IV_SOURCE = new SecureRandom();

    private MarketSourceCodec() {
    }

    /**
     * Wraps a plaintext UTF-8 source document into an LZP1 container.
     *
     * @param plaintext raw UTF-8 v2 source JSON bytes, at most 8 MiB
     * @return {@code LZP1} magic, a fresh random IV and AES-128-CTR ciphertext
     * @throws IllegalArgumentException when the plaintext is null or exceeds the limit
     */
    public static byte[] encode(byte[] plaintext) {
        byte[] iv = new byte[IV_LENGTH];
        IV_SOURCE.nextBytes(iv);
        return encodeWithIv(plaintext, iv);
    }

    /**
     * Opens an LZP1 container back into the plaintext UTF-8 source document.
     *
     * @param container bytes previously produced by {@link #encode(byte[])}
     * @return raw UTF-8 v2 source JSON bytes
     * @throws IllegalArgumentException when the container is malformed or too large
     */
    public static byte[] decode(byte[] container) {
        if (container == null) {
            throw new IllegalArgumentException("container must not be null");
        }
        if (container.length < HEADER_LENGTH) {
            throw new IllegalArgumentException("container shorter than LZP1 header: " + container.length);
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (container[i] != MAGIC[i]) {
                throw new IllegalArgumentException("not an LZP1 container");
            }
        }
        byte[] iv = new byte[IV_LENGTH];
        System.arraycopy(container, MAGIC.length, iv, 0, IV_LENGTH);
        int cipherLength = container.length - HEADER_LENGTH;
        byte[] ciphertext = new byte[cipherLength];
        System.arraycopy(container, HEADER_LENGTH, ciphertext, 0, cipherLength);
        return inflate(ctr(ciphertext, iv));
    }

    /**
     * Deterministic container builder with a caller supplied IV. Used by the interoperability
     * fixtures so a fixed IV can be checked byte for byte against the Rust and Python sides.
     */
    static byte[] encodeWithIv(byte[] plaintext, byte[] iv) {
        if (plaintext == null) {
            throw new IllegalArgumentException("plaintext must not be null");
        }
        if (iv == null || iv.length != IV_LENGTH) {
            throw new IllegalArgumentException("iv must be " + IV_LENGTH + " bytes");
        }
        if (plaintext.length > MAX_PLAINTEXT_BYTES) {
            throw new IllegalArgumentException(
                    "plaintext exceeds " + MAX_PLAINTEXT_BYTES + " bytes: " + plaintext.length);
        }
        byte[] ciphertext = ctr(deflate(plaintext), iv);
        byte[] container = new byte[HEADER_LENGTH + ciphertext.length];
        System.arraycopy(MAGIC, 0, container, 0, MAGIC.length);
        System.arraycopy(iv, 0, container, MAGIC.length, IV_LENGTH);
        System.arraycopy(ciphertext, 0, container, HEADER_LENGTH, ciphertext.length);
        return container;
    }

    private static byte[] deflate(byte[] plaintext) {
        Deflater deflater = new Deflater(ZLIB_LEVEL);
        try {
            deflater.setInput(plaintext);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, plaintext.length / 2));
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte[] inflate(byte[] deflated) {
        Inflater inflater = new Inflater();
        try {
            InflaterInputStream stream = new InflaterInputStream(new ByteArrayInputStream(deflated), inflater);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, deflated.length * 4));
            byte[] buffer = new byte[8192];
            int total = 0;
            while (true) {
                int read = stream.read(buffer);
                if (read < 0) {
                    break;
                }
                total += read;
                if (total > MAX_PLAINTEXT_BYTES) {
                    throw new IllegalArgumentException(
                            "decoded source exceeds " + MAX_PLAINTEXT_BYTES + " bytes");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException error) {
            throw new IllegalArgumentException("LZP1 payload is not a valid zlib stream", error);
        } finally {
            inflater.end();
        }
    }

    /**
     * Applies AES-128-CTR with the 128-bit big-endian counter to the whole buffer.
     * Encryption and decryption are the same transform because CTR is symmetric.
     * Package private so the host gate can verify the counter layout directly.
     */
    static byte[] ctr(byte[] data, byte[] iv) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            SecretKeySpec key = new SecretKeySpec(FORMAT_KEY.getBytes(StandardCharsets.US_ASCII), "AES");
            cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(iv));
            return cipher.doFinal(data);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("AES-128-CTR unavailable", error);
        }
    }
}
