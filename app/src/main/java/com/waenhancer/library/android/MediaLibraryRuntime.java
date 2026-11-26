package com.waenhancer.library.android;

import android.content.Context;
import android.util.Log;

import com.waenhancer.library.core.BlobStore;
import com.waenhancer.library.core.LibraryLock;
import com.waenhancer.library.core.MediaLibrary;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process-wide access to the module's media library.
 *
 * <p>Storage: {@code files/media_library/} for blobs (never the cache directory, which Android
 * may clear) and {@code databases/media_library.db} for the catalog, both private to the module.
 * Both are excluded from Android cloud backup and device transfer by
 * {@code data_extraction_rules.xml}; the only way the bytes leave the device is the user's own
 * encrypted library export.</p>
 */
public final class MediaLibraryRuntime {
    private static final String TAG = "WaexMediaLibrary";
    private static volatile MediaLibrary instance;
    private static final AtomicBoolean maintenanceStarted = new AtomicBoolean();

    private MediaLibraryRuntime() { }

    public static MediaLibrary get(Context context) throws IOException {
        MediaLibrary current = instance;
        if (current != null) return current;
        synchronized (MediaLibraryRuntime.class) {
            if (instance == null) {
                Context app = context.getApplicationContext();
                File root = new File(app.getFilesDir(), "media_library");
                File database = app.getDatabasePath("media_library.db");
                File parent = database.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Could not create the library database directory");
                }
                instance = new MediaLibrary(AndroidSql.open(database), new BlobStore(root),
                        new LibraryLock(root), System::currentTimeMillis);
            }
            startMaintenance(instance);
            return instance;
        }
    }

    /** Once per process: repair interrupted imports and purge trash past its retention. */
    private static void startMaintenance(MediaLibrary library) {
        if (!maintenanceStarted.compareAndSet(false, true)) return;
        Thread thread = new Thread(() -> {
            try {
                MediaLibrary.ReconcileReport report = library.reconcile(false);
                int purged = library.purgeExpiredTrash();
                if (report.stagingRemoved + report.orphanBlobsRemoved + report.markedMissing
                        + report.markedCorrupt + purged > 0) {
                    Log.i(TAG, "maintenance staging=" + report.stagingRemoved + " orphans=" + report.orphanBlobsRemoved
                            + " missing=" + report.markedMissing + " corrupt=" + report.markedCorrupt + " purged=" + purged);
                }
            } catch (IOException | RuntimeException failure) {
                Log.w(TAG, "maintenance failed: " + failure.getClass().getSimpleName());
            }
        }, "waex-library-maintenance");
        thread.setDaemon(true);
        thread.start();
    }
}
