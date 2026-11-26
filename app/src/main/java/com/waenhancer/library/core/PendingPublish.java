package com.waenhancer.library.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Order of an explicit export into a shared media store, independent of Android so it can be
 * tested: create a pending (hidden) entry, write every byte, <b>close</b> the stream, and only
 * then publish; a publish that does not report exactly one updated entry is a failure. On any
 * failure the pending entry is deleted, so a half-written or unpublished file never stays.
 */
public final class PendingPublish {
    private PendingPublish() { }

    /** The media store, e.g. MediaStore through a ContentResolver. */
    public interface Store<T> {
        /** Creates a pending entry; {@code null} when refused. */
        T createPending() throws IOException;

        OutputStream open(T entry) throws IOException;

        /** Clears the pending flag; returns the number of entries updated. */
        int publish(T entry) throws IOException;

        void delete(T entry);
    }

    public static <T> T save(Store<T> store, InputStream input) throws IOException {
        T entry = store.createPending();
        if (entry == null) throw new IOException("The media store refused the entry");
        boolean published = false;
        try {
            try (OutputStream out = store.open(entry)) {
                if (out == null) throw new IOException("No output stream");
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = input.read(buffer)) != -1; ) out.write(buffer, 0, read);
                out.flush();
            } // closed (and committed by the provider) before it becomes visible
            int updated = store.publish(entry);
            if (updated != 1) throw new IOException("The media store did not publish the entry (" + updated + ")");
            published = true;
            return entry;
        } finally {
            if (!published) store.delete(entry);
        }
    }
}
