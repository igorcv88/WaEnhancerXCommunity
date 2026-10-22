package com.waenhancer.config;

import android.content.Context;
import android.graphics.Typeface;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Module-owned, fixed-path storage for a user-supplied emoji font. Never stores document URIs. */
public final class EmojiFontStore {
    private static final String DIRECTORY = "user-emoji";
    private static final String FILENAME = "selected.ttf";
    private static final String DISPLAY_NAME_KEY = "user_emoji_font_name";

    private EmojiFontStore() {}

    /** The only file the WhatsApp Binder capability can ever open. */
    public static File fontFile(Context context) {
        return new File(new File(context.getFilesDir(), DIRECTORY), FILENAME);
    }

    public static boolean hasFont(Context context) {
        return fontFile(context).isFile();
    }

    public static String displayName(Context context) {
        return PreferenceStores.privateStore(context).getString(DISPLAY_NAME_KEY, "Selected emoji font");
    }

    public static void importFont(Context context, Uri uri, String displayName) throws IOException {
        if (uri == null || !"content".equals(uri.getScheme())) {
            throw new IOException("Choose a font with the Android document picker");
        }
        File destination = fontFile(context);
        File directory = destination.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create private font directory");
        }

        File staging = File.createTempFile(".import-", ".ttf", directory);
        try {
            try (InputStream input = context.getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(staging)) {
                if (input == null) throw new IOException("Cannot read selected document");
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                int size;
                while ((size = input.read(buffer)) != -1) {
                    total += size;
                    if (total > EmojiFontValidator.MAX_BYTES) throw new IOException("Font exceeds 128 MB");
                    output.write(buffer, 0, size);
                }
                output.getFD().sync();
            }
            EmojiFontValidator.validate(staging);
            try {
                if (Typeface.createFromFile(staging) == null) throw new IOException("Android refused the font");
            } catch (RuntimeException rejected) {
                throw new IOException("Android cannot load this font", rejected);
            }
            // Same-directory atomic replacement preserves the previously selected font on failure.
            Files.move(staging.toPath(), destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            String safeName = displayName == null ? "Emoji font" : displayName
                    .replaceAll("[\\p{Cntrl}\\\\/]", "").trim();
            if (safeName.length() > 100) safeName = safeName.substring(0, 100);
            if (safeName.isEmpty()) safeName = "Emoji font";
            PreferenceStores.privateStore(context).edit().putString(DISPLAY_NAME_KEY, safeName).apply();
        } finally {
            if (staging.exists()) staging.delete();
        }
    }

    public static boolean removeFont(Context context) {
        File selected = fontFile(context);
        if (selected.exists() && !selected.delete()) return false;
        PreferenceStores.privateStore(context).edit().remove(DISPLAY_NAME_KEY).apply();
        return true;
    }
}
