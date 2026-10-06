package com.waenhancer.xposed.features.media;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.Assert.*;

public class MediaPreviewPayloadTest {
    private Cipher cipher(int mode) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(mode, new SecretKeySpec(new byte[32], "AES"), new IvParameterSpec(new byte[16]));
        return cipher;
    }
    @Test public void streamsAcrossManyChunksAndExcludesTrailer() throws Exception {
        byte[] original = new byte[100003];
        new java.util.Random(1).nextBytes(original);
        byte[] encrypted = cipher(Cipher.ENCRYPT_MODE).doFinal(original);
        byte[] payload = Arrays.copyOf(encrypted, encrypted.length + 10);
        Arrays.fill(payload, encrypted.length, payload.length, (byte) 0x75);
        Path input = Files.createTempFile("media-test", ".enc");
        Path output = Files.createTempFile("media-test", ".decoded");
        try {
            Files.write(input, payload);
            MediaPreviewPayload.decrypt(input.toFile(), output.toFile(), cipher(Cipher.DECRYPT_MODE));
            assertArrayEquals(original, Files.readAllBytes(output));
        } finally { Files.deleteIfExists(input); Files.deleteIfExists(output); }
    }
    @Test public void rejectsTrailerOnly() throws Exception { rejectsLength(10); }
    @Test public void rejectsMisalignedPayload() throws Exception { rejectsLength(27); }
    private void rejectsLength(int length) throws Exception {
        Path input = Files.createTempFile("media-test", ".enc");
        Path output = Files.createTempFile("media-test", ".decoded");
        try {
            Files.write(input, new byte[length]);
            try {
                MediaPreviewPayload.decrypt(input.toFile(), output.toFile(), cipher(Cipher.DECRYPT_MODE));
                fail("Expected invalid payload rejection");
            } catch (java.io.IOException expected) {}
        } finally { Files.deleteIfExists(input); Files.deleteIfExists(output); }
    }
    @Test public void badPaddingRemovesPartialPlaintext() throws Exception {
        byte[] encrypted = cipher(Cipher.ENCRYPT_MODE).doFinal(new byte[10000]);
        // Modify the previous CBC block so the final padding byte deterministically becomes zero.
        encrypted[encrypted.length - 17] ^= 16;
        Path input = Files.createTempFile("media-test", ".enc");
        Path output = Files.createTempFile("media-test", ".decoded");
        try {
            Files.write(input, Arrays.copyOf(encrypted, encrypted.length + 10));
            try {
                MediaPreviewPayload.decrypt(input.toFile(), output.toFile(), cipher(Cipher.DECRYPT_MODE));
                fail("Expected corrupt padding rejection");
            } catch (javax.crypto.BadPaddingException expected) {}
            assertFalse(Files.exists(output));
        } finally { Files.deleteIfExists(input); Files.deleteIfExists(output); }
    }
}
