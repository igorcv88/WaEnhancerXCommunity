package com.waenhancer.library.core;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Password-based streaming authenticated encryption for library backups.
 *
 * <p>The existing full backup seals one JSON document with a single AES-GCM call, which needs
 * the whole plaintext (and on Android the whole ciphertext during decryption) in memory. A media
 * library can be many gigabytes, so this container uses the STREAM construction instead
 * (Hoang, Reyhanitabar, Rogaway, Vizár, "Online Authenticated-Encryption and its
 * Nonce-Reuse Misuse-Resistance", 2015): the plaintext is split into fixed-size chunks, each
 * sealed with AES-256-GCM under nonce {@code prefix(7) ‖ counter(4) ‖ lastFlag(1)} and the
 * header as associated data. Reordering, dropping, duplicating or truncating chunks, or changing
 * the header, fails authentication. The final chunk is always shorter than a full chunk (an empty
 * one is emitted when needed), so truncation at a chunk boundary is detected as a missing final
 * chunk.</p>
 *
 * <p>Each chunk is released only after its own tag verified, so a consumer may observe
 * authenticated prefixes of a damaged file. The library restore therefore stages everything and
 * commits only after {@link DecryptingInputStream} reached a verified end.</p>
 */
public final class StreamingAead {
    private static final byte[] MAGIC = "WAEXLIB1".getBytes(StandardCharsets.US_ASCII);
    private static final int VERSION = 1;
    private static final int SALT_BYTES = 16;
    private static final int PREFIX_BYTES = 7;
    private static final int TAG_BYTES = 16;
    public static final int DEFAULT_CHUNK = 1024 * 1024;
    public static final int DEFAULT_ITERATIONS = 600_000;
    static final int HEADER_BYTES = MAGIC.length + 1 + 4 + 4 + SALT_BYTES + PREFIX_BYTES;

    private StreamingAead() { }

    public static boolean looksLikeBackup(byte[] prefix) {
        if (prefix == null || prefix.length < MAGIC.length) return false;
        for (int i = 0; i < MAGIC.length; i++) if (prefix[i] != MAGIC[i]) return false;
        return true;
    }

    public static OutputStream encrypt(OutputStream sink, char[] password) throws IOException {
        return encrypt(sink, password, DEFAULT_ITERATIONS, DEFAULT_CHUNK);
    }

    /** Package-visible parameters let tests use small chunks and a fast KDF. */
    static OutputStream encrypt(OutputStream sink, char[] password, int iterations, int chunkSize) throws IOException {
        requirePassword(password);
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[SALT_BYTES];
        byte[] prefix = new byte[PREFIX_BYTES];
        random.nextBytes(salt);
        random.nextBytes(prefix);
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
        header.put(MAGIC).put((byte) VERSION).putInt(iterations).putInt(chunkSize).put(salt).put(prefix);
        byte[] headerBytes = header.array();
        byte[] key = derive(password, salt, iterations);
        sink.write(headerBytes);
        return new EncryptingOutputStream(sink, key, prefix, headerBytes, chunkSize);
    }

    public static InputStream decrypt(InputStream source, char[] password) throws IOException {
        return decrypt(source, password, 2_000_000);
    }

    static InputStream decrypt(InputStream source, char[] password, int maxIterations) throws IOException {
        requirePassword(password);
        DataInputStream input = new DataInputStream(source);
        byte[] headerBytes = new byte[HEADER_BYTES];
        try {
            input.readFully(headerBytes);
        } catch (EOFException truncated) {
            throw invalid("Backup is truncated", truncated);
        }
        ByteBuffer header = ByteBuffer.wrap(headerBytes);
        byte[] magic = new byte[MAGIC.length];
        header.get(magic);
        if (!Arrays.equals(magic, MAGIC)) throw invalid("Not a media library backup", null);
        if (header.get() != VERSION) throw invalid("Unsupported media library backup version", null);
        int iterations = header.getInt();
        int chunkSize = header.getInt();
        if (iterations < 1 || iterations > maxIterations || (maxIterations >= 210_000 && iterations < 210_000)) {
            throw invalid("Backup KDF parameters are unsafe", null);
        }
        if (chunkSize < 1024 || chunkSize > 16 * 1024 * 1024) throw invalid("Backup chunk size is invalid", null);
        byte[] salt = new byte[SALT_BYTES];
        byte[] prefix = new byte[PREFIX_BYTES];
        header.get(salt);
        header.get(prefix);
        return new DecryptingInputStream(input, derive(password, salt, iterations), prefix, headerBytes, chunkSize);
    }

    private static void requirePassword(char[] password) throws LibraryException {
        if (password == null || password.length == 0) {
            throw new LibraryException(LibraryException.Code.BACKUP_INVALID, "A backup password is required");
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) throws IOException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException failure) {
            throw new LibraryException(LibraryException.Code.IO, "Key derivation is unavailable", failure);
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] nonce(byte[] prefix, int counter, boolean last) {
        return ByteBuffer.allocate(12).put(prefix).putInt(counter).put((byte) (last ? 1 : 0)).array();
    }

    private static LibraryException invalid(String message, Throwable cause) {
        return new LibraryException(LibraryException.Code.BACKUP_INVALID, message, cause);
    }

    private static final class EncryptingOutputStream extends FilterOutputStream {
        private final byte[] key;
        private final byte[] prefix;
        private final byte[] aad;
        private final byte[] buffer;
        private int filled;
        private int counter;
        private boolean closed;

        EncryptingOutputStream(OutputStream sink, byte[] key, byte[] prefix, byte[] aad, int chunkSize) {
            super(sink);
            this.key = key;
            this.prefix = prefix;
            this.aad = aad;
            this.buffer = new byte[chunkSize];
        }

        @Override
        public void write(int value) throws IOException {
            write(new byte[] { (byte) value }, 0, 1);
        }

        @Override
        public void write(byte[] data, int offset, int length) throws IOException {
            if (closed) throw new IOException("Stream closed");
            while (length > 0) {
                // A full buffer is only sealed once more data arrives, so the final chunk is
                // always the short (possibly empty) one sealed by close().
                if (filled == buffer.length) seal(false);
                int copy = Math.min(length, buffer.length - filled);
                System.arraycopy(data, offset, buffer, filled, copy);
                filled += copy;
                offset += copy;
                length -= copy;
            }
        }

        private void seal(boolean last) throws IOException {
            if (counter == Integer.MAX_VALUE) throw new IOException("Backup is too large");
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(TAG_BYTES * 8, nonce(prefix, counter, last)));
                cipher.updateAAD(aad);
                out.write(cipher.doFinal(buffer, 0, filled));
            } catch (GeneralSecurityException failure) {
                throw new LibraryException(LibraryException.Code.IO, "Could not encrypt backup", failure);
            }
            counter++;
            filled = 0;
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            try {
                if (filled == buffer.length) seal(false);
                seal(true);
                out.flush();
            } finally {
                Arrays.fill(key, (byte) 0);
                Arrays.fill(buffer, (byte) 0);
                out.close();
            }
        }
    }

    /** Releases plaintext chunk by chunk; reports a verified end with {@link #isComplete()}. */
    public static final class DecryptingInputStream extends InputStream {
        private final DataInputStream in;
        private final byte[] key;
        private final byte[] prefix;
        private final byte[] aad;
        private final byte[] sealed;
        private byte[] plain = new byte[0];
        private int position;
        private int counter;
        private boolean finished;

        DecryptingInputStream(DataInputStream in, byte[] key, byte[] prefix, byte[] aad, int chunkSize) {
            this.in = in;
            this.key = key;
            this.prefix = prefix;
            this.aad = aad;
            this.sealed = new byte[chunkSize + TAG_BYTES];
        }

        /** True once the final chunk authenticated and nothing followed it. */
        public boolean isComplete() {
            return finished;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int read = read(one, 0, 1);
            return read < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            if (length == 0) return 0;
            while (position == plain.length) {
                if (finished) return -1;
                next();
            }
            int copy = Math.min(length, plain.length - position);
            System.arraycopy(plain, position, target, offset, copy);
            position += copy;
            return copy;
        }

        private void next() throws IOException {
            int read = 0;
            while (read < sealed.length) {
                int count = in.read(sealed, read, sealed.length - read);
                if (count < 0) break;
                read += count;
            }
            boolean last = read < sealed.length;
            if (read < TAG_BYTES) throw invalid("Backup is truncated", null);
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(TAG_BYTES * 8, nonce(prefix, counter, last)));
                cipher.updateAAD(aad);
                plain = cipher.doFinal(sealed, 0, read);
            } catch (GeneralSecurityException failure) {
                throw invalid("Backup password is incorrect or the file was modified", failure);
            }
            position = 0;
            counter++;
            if (last) {
                if (in.read() != -1) throw invalid("Unexpected data after the end of the backup", null);
                finished = true;
                Arrays.fill(key, (byte) 0);
            }
        }

        @Override
        public void close() throws IOException {
            Arrays.fill(sealed, (byte) 0);
            in.close();
        }
    }
}
