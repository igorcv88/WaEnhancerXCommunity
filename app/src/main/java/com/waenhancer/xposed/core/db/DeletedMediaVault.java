package com.waenhancer.xposed.core.db;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/** Owns preserved media. Caller-provided names and paths are never used as destination names. */
public final class DeletedMediaVault {
    public static final long DEFAULT_QUOTA_BYTES = 512L * 1024L * 1024L;
    private static final String DIRECTORY = "deleted_media";

    private final Context context;
    private final DelMessageStore store;

    public DeletedMediaVault(Context context, DelMessageStore store) {
        this.context = context.getApplicationContext();
        this.store = store;
    }

    public synchronized DeletedMediaRecord preserve(long messageId, File source, String mimeType, long quotaBytes)
            throws IOException {
        if (messageId <= 0 || source == null || !source.isFile()) throw new IOException("Invalid media source");
        File root = mediaDirectory();
        File canonicalSource = source.getCanonicalFile();
        if (!canonicalSource.isFile()) throw new IOException("Media source is unavailable");
        rejectOwnData(canonicalSource);
        long quota = quotaBytes > 0 ? quotaBytes : DEFAULT_QUOTA_BYTES;
        if (canonicalSource.length() > quota || usedBytes() > quota - canonicalSource.length()) {
            throw new IOException("Deleted-media quota exceeded");
        }
        String hash = sha256(canonicalSource);
        DeletedMediaRecord existing = store.findMediaByHash(hash);
        if (existing != null) {
            store.attachMediaToMessage(existing.id, messageId);
            return existing;
        }

        String storageId = UUID.randomUUID().toString();
        File target = new File(root, storageId);
        ensureChild(root, target);
        copy(canonicalSource, target);
        if (!hash.equals(sha256(target))) {
            target.delete();
            throw new IOException("Media hash verification failed");
        }
        String storedMime = normalizeMime(mimeType);
        SQLiteDatabase db = store.getWritableDatabase();
        db.beginTransaction();
        try {
            long id = store.insertDeletedMedia(messageId, storageId, hash, storedMime, target.length());
            db.setTransactionSuccessful();
            // The record must mirror what was stored, not what the caller passed.
            return new DeletedMediaRecord(id, messageId, storageId, hash, storedMime, target.length());
        } finally {
            db.endTransaction();
            if (!target.exists() || store.findMediaByHash(hash) == null) target.delete();
        }
    }

    /**
     * Drops one message's claim on preserved media. The bytes survive while any other message
     * still references them; only the last reference erases the file.
     */
    public synchronized boolean releaseReference(String storageId, long messageId) throws IOException {
        if (storageId == null || !storageId.matches("[0-9a-fA-F-]{36}") || messageId <= 0) return false;
        DeletedMediaRecord record = store.findMediaByStorageId(storageId);
        if (record == null) return false;
        if (store.detachMediaFromMessage(record.id, messageId) == 0) return false;
        if (store.hasMediaReferences(record.id)) return true;
        return permanentlyDelete(storageId);
    }

    /**
     * Erases the bytes and the index row outright. Refuses while another message still
     * references the same deduplicated file, so removing one message never destroys another's
     * media.
     */
    public synchronized boolean permanentlyDelete(String storageId) throws IOException {
        if (storageId == null || !storageId.matches("[0-9a-fA-F-]{36}")) return false;
        DeletedMediaRecord record = store.findMediaByStorageId(storageId);
        if (record != null && store.countMediaReferences(record.id) > 1) {
            throw new IOException("Preserved media is still referenced by another message");
        }
        File root = mediaDirectory();
        File target = new File(root, storageId);
        ensureChild(root, target);
        // The index row goes first: a row without bytes is a visible, recoverable inconsistency,
        // whereas bytes without a row are invisible and would leak the user's media forever.
        int removed = store.deleteDeletedMedia(storageId);
        if (target.exists() && !target.delete()) throw new IOException("Could not delete preserved media");
        return removed > 0;
    }

    public File resolve(String storageId) throws IOException {
        if (storageId == null || !storageId.matches("[0-9a-fA-F-]{36}")) throw new IOException("Invalid media id");
        File root = mediaDirectory();
        File target = new File(root, storageId);
        ensureChild(root, target);
        if (!target.isFile()) throw new IOException("Preserved media is unavailable");
        return target;
    }

    /** Removes a file left by a rolled-back transaction only when it has no database row. */
    public void discardUnindexed(String storageId) throws IOException {
        if (storageId == null || !storageId.matches("[0-9a-fA-F-]{36}")) return;
        try (android.database.Cursor cursor = store.getReadableDatabase().query(DelMessageStore.TABLE_DELETED_MEDIA,
                new String[] { "_id" }, "storage_id=?", new String[] { storageId }, null, null, null, "1")) {
            if (cursor.moveToFirst()) return;
        }
        File root = mediaDirectory();
        File file = new File(root, storageId);
        ensureChild(root, file);
        // Absent is the desired end state, not a failure: the rollback may have run already.
        if (file.exists() && !file.delete()) throw new IOException("Could not clean rolled-back media");
    }

    /** Returns a verified private media file for inclusion in an encrypted full backup. */
    public byte[] readVerified(String storageId, String expectedSha256, long expectedSize) throws IOException {
        File file = resolve(storageId);
        if (file.length() != expectedSize || !sha256(file).equals(expectedSha256)) {
            throw new IOException("Preserved media verification failed");
        }
        if (expectedSize > Integer.MAX_VALUE) throw new IOException("Preserved media is too large");
        byte[] bytes = new byte[(int) expectedSize];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                int read = input.read(bytes, offset, bytes.length - offset);
                if (read < 0) throw new IOException("Preserved media is truncated");
                offset += read;
            }
        }
        return bytes;
    }

    /** Restores bytes that were authenticated and hash-validated before this call. */
    public synchronized DeletedMediaRecord restore(long messageId, String sha256, String mimeType, byte[] bytes,
                                                   long quotaBytes) throws IOException {
        if (bytes == null || !sha256(bytes).equals(sha256)) throw new IOException("Restored media hash mismatch");
        DeletedMediaRecord existing = store.findMediaByHash(sha256);
        if (existing != null) {
            store.attachMediaToMessage(existing.id, messageId);
            return existing;
        }
        long quota = quotaBytes > 0 ? quotaBytes : DEFAULT_QUOTA_BYTES;
        if (bytes.length > quota || usedBytes() > quota - bytes.length) throw new IOException("Deleted-media quota exceeded");
        File root = mediaDirectory();
        String storageId = UUID.randomUUID().toString();
        File target = new File(root, storageId);
        ensureChild(root, target);
        try (FileOutputStream output = new FileOutputStream(target)) {
            output.write(bytes);
            output.getFD().sync();
        }
        if (!sha256(target).equals(sha256)) {
            target.delete();
            throw new IOException("Restored media verification failed");
        }
        String storedMime = normalizeMime(mimeType);
        try {
            long id = store.insertDeletedMedia(messageId, storageId, sha256, storedMime, bytes.length);
            return new DeletedMediaRecord(id, messageId, storageId, sha256, storedMime, bytes.length);
        } finally {
            if (store.findMediaByHash(sha256) == null) target.delete();
        }
    }

    private File mediaDirectory() throws IOException {
        File root = new File(context.getFilesDir(), DIRECTORY);
        if (!root.exists() && !root.mkdirs()) throw new IOException("Could not create media storage");
        return root.getCanonicalFile();
    }

    private long usedBytes() {
        long total = 0;
        for (DeletedMediaRecord record : store.getDeletedMedia()) total += Math.max(0, record.sizeBytes);
        return total;
    }

    private static String normalizeMime(String mimeType) {
        return mimeType == null || mimeType.isEmpty() ? "application/octet-stream" : mimeType;
    }

    /**
     * Refuses to copy from the module's own data directory.
     *
     * <p>The source path arrives from the hooked WhatsApp process, which is a trusted caller and
     * may also read preserved media back. Without this check that process could name
     * {@code private_config.xml} or the message database as "media", have this module copy it
     * into the vault, and then read the user's secrets out through the provider.</p>
     */
    private void rejectOwnData(File canonicalSource) throws IOException {
        String path = canonicalSource.getPath();
        for (File own : new File[] { context.getFilesDir(), context.getCacheDir(), context.getDataDir() }) {
            if (own == null) continue;
            String prefix = own.getCanonicalPath() + File.separator;
            if (path.startsWith(prefix)) throw new IOException("Media source is not eligible");
        }
    }

    private static void ensureChild(File root, File target) throws IOException {
        String prefix = root.getCanonicalPath() + File.separator;
        if (!target.getCanonicalPath().startsWith(prefix)) throw new IOException("Invalid media path");
    }

    private static void copy(File from, File to) throws IOException {
        try (FileInputStream input = new FileInputStream(from); FileOutputStream output = new FileOutputStream(to)) {
            byte[] buffer = new byte[32 * 1024];
            for (int read; (read = input.read(buffer)) != -1;) output.write(buffer, 0, read);
            output.getFD().sync();
        }
    }

    private static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[32 * 1024];
                for (int read; (read = input.read(buffer)) != -1;) digest.update(buffer, 0, read);
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", value));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(bytes);
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", value));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }
}
