package com.waenhancer.xposed.features.media;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import javax.crypto.Cipher;

/** Disk-streaming adaptation of upstream bb8d5fb2; the last 10 bytes are the media trailer. */
public final class MediaPreviewPayload {
    private MediaPreviewPayload() {}

    public static void decrypt(File encrypted, File output, Cipher cipher) throws Exception {
        long remaining = encrypted.length() - 10;
        if (remaining <= 0 || remaining % 16 != 0)
            throw new IOException("Invalid encrypted media length");
        try (FileInputStream input = new FileInputStream(encrypted);
             FileOutputStream decoded = new FileOutputStream(output)) {
            byte[] buffer = new byte[8192];
            while (remaining > 0) {
                int count = input.read(buffer, 0, (int) Math.min(remaining, buffer.length));
                if (count < 0) throw new IOException("Truncated encrypted media");
                if (Thread.currentThread().isInterrupted()) throw new IOException("Preview cancelled");
                byte[] chunk = cipher.update(buffer, 0, count);
                if (chunk != null) decoded.write(chunk);
                remaining -= count;
            }
            decoded.write(cipher.doFinal());
        } catch (Exception failure) {
            output.delete();
            throw failure;
        }
    }
}
