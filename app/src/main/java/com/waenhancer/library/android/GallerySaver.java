package com.waenhancer.library.android;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Explicit "save to gallery": inserts one photo/video into MediaStore under a relative album
 * path ({@code Pictures/View Once} or {@code DCIM/WhatsApp}, see
 * {@link com.waenhancer.library.core.GalleryAlbums}). The entry stays pending (invisible to other
 * apps) until every byte is written, and is deleted if the copy fails. Needs no storage
 * permission on Android 10+, where it is used; callers fall back on older versions.
 */
public final class GallerySaver {
    private GallerySaver() { }

    public static boolean supported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    public static Uri save(ContentResolver resolver, InputStream input, String mimeType, String displayName,
                           String relativePath) throws IOException {
        if (!supported()) throw new IOException("MediaStore albums need Android 10+");
        boolean video = mimeType != null && mimeType.startsWith("video/");
        Uri collection = video
                ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(collection, values);
        if (uri == null) throw new IOException("MediaStore refused the entry");
        boolean done = false;
        try (OutputStream out = resolver.openOutputStream(uri, "w")) {
            if (out == null) throw new IOException("No output stream");
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = input.read(buffer)) != -1; ) out.write(buffer, 0, read);
            out.flush();
            ContentValues publish = new ContentValues();
            publish.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, publish, null, null);
            done = true;
            return uri;
        } finally {
            if (!done) {
                try {
                    resolver.delete(uri, null, null);
                } catch (RuntimeException ignored) {
                }
            }
        }
    }
}
