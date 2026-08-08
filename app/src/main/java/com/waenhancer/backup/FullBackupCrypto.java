package com.waenhancer.backup;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Password container for full backups. Header fields are authenticated as GCM AAD. */
public final class FullBackupCrypto {
    private static final byte[] MAGIC = "WAEXFULL1".getBytes(StandardCharsets.US_ASCII);
    private static final int SALT_BYTES = 16, IV_BYTES = 12, KEY_BITS = 256, TAG_BITS = 128;
    // PBKDF2 is the documented fallback where no reviewed Argon2id provider is bundled.
    private static final int ITERATIONS = 600_000;

    private FullBackupCrypto() { }

    public static byte[] encrypt(byte[] plaintext, char[] password) throws BackupCodec.BackupException {
        if (plaintext == null || password == null || password.length == 0) throw new BackupCodec.BackupException("A backup password is required.");
        byte[] salt = random(SALT_BYTES), iv = random(IV_BYTES), key = null;
        try {
            key = derive(password, salt);
            ByteBuffer header = ByteBuffer.allocate(MAGIC.length + 4 + SALT_BYTES + IV_BYTES);
            header.put(MAGIC).putInt(ITERATIONS).put(salt).put(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(header.array());
            byte[] sealed = cipher.doFinal(plaintext);
            ByteArrayOutputStream output = new ByteArrayOutputStream(header.capacity() + sealed.length);
            output.write(header.array()); output.write(sealed);
            return output.toByteArray();
        } catch (GeneralSecurityException | IOException exception) {
            throw new BackupCodec.BackupException("Could not encrypt full backup.", exception);
        } finally { wipe(key); wipe(salt); wipe(iv); }
    }

    public static byte[] decrypt(byte[] container, char[] password) throws BackupCodec.BackupException {
        if (container == null || password == null || password.length == 0) throw new BackupCodec.BackupException("A backup password is required.");
        int length = MAGIC.length + 4 + SALT_BYTES + IV_BYTES;
        if (container.length <= length + 16) throw new BackupCodec.BackupException("Backup is truncated.");
        ByteBuffer input = ByteBuffer.wrap(container); byte[] magic = new byte[MAGIC.length]; input.get(magic);
        for (int i = 0; i < MAGIC.length; i++) if (magic[i] != MAGIC[i]) throw new BackupCodec.BackupException("Not a WaEnhancer full backup.");
        int iterations = input.getInt();
        if (iterations < 210_000 || iterations > 2_000_000) throw new BackupCodec.BackupException("Backup KDF parameters are unsafe.");
        byte[] salt = new byte[SALT_BYTES], iv = new byte[IV_BYTES], key = null; input.get(salt); input.get(iv);
        try {
            key = derive(password, salt, iterations);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(java.util.Arrays.copyOf(container, length));
            return cipher.doFinal(java.util.Arrays.copyOfRange(container, length, container.length));
        } catch (GeneralSecurityException exception) {
            throw new BackupCodec.BackupException("Backup password is incorrect or the file was modified.", exception);
        } finally { wipe(salt); wipe(iv); wipe(key); }
    }

    private static byte[] derive(char[] password, byte[] salt) throws GeneralSecurityException { return derive(password, salt, ITERATIONS); }
    private static byte[] derive(char[] password, byte[] salt, int iterations) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }
    private static byte[] random(int count) { byte[] bytes = new byte[count]; new SecureRandom().nextBytes(bytes); return bytes; }
    private static void wipe(byte[] bytes) { if (bytes != null) java.util.Arrays.fill(bytes, (byte) 0); }
}
