package com.waenhancer.library.android;

import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Build;

import com.waenhancer.library.core.MediaLibrary;

import java.io.File;

/** Device decode check run before a file is accepted (after structural validation). */
public final class AndroidDecodeProbe implements MediaLibrary.DecodeProbe {
    @Override
    public String check(File file, String mimeType) {
        if (mimeType.startsWith("image/")) {
            if ("image/avif".equals(mimeType) && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            return options.outWidth > 0 && options.outHeight > 0 ? null : "image_undecodable";
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getPath());
            String hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO);
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (!"yes".equals(hasVideo)) return "no_video_track";
            return duration != null && Long.parseLong(duration) > 0 ? null : "no_duration";
        } catch (RuntimeException failure) {
            return "video_undecodable";
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }
}
