package com.waenhancer.library.android;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import com.waenhancer.library.core.PendingPublish;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Explicit "save to gallery": inserts one photo/video into MediaStore under a relative album
 * path ({@code DCIM/WhatsApp View Once} or {@code DCIM/WhatsApp}, see
 * {@link com.waenhancer.library.core.GalleryAlbums}). Ordering and failure handling are in
 * {@link PendingPublish} (tested): pending entry, write, close, publish, verify, else delete.
 * Needs no storage permission on Android 10+, where it is used; callers fall back on older versions.
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
        return PendingPublish.save(new PendingPublish.Store<Uri>() {
            @Override
            public Uri createPending() {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                return resolver.insert(collection, values);
            }

            @Override
            public OutputStream open(Uri entry) throws IOException {
                return resolver.openOutputStream(entry, "w");
            }

            @Override
            public int publish(Uri entry) {
                ContentValues publish = new ContentValues();
                publish.put(MediaStore.MediaColumns.IS_PENDING, 0);
                return resolver.update(entry, publish, null, null);
            }

            @Override
            public void delete(Uri entry) {
                try {
                    resolver.delete(entry, null, null);
                } catch (RuntimeException ignored) {
                }
            }
        }, input);
    }
}
