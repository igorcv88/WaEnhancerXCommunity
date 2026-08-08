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
        long quota = quotaBytes > 0 ? quotaBytes : DEFAULT_QUOTA_BYTES;
        if (canonicalSource.length() > quota || usedBytes() > quota - canonicalSource.length()) {
            throw new IOException("Deleted-media quota exceeded");
        }
        String hash = sha256(canonicalSource);
        DeletedMediaRecord existing = store.findMediaByHash(hash);
        if (existing != null) return existing;

        String storageId = UUID.randomUUID().toString();
        File target = new File(root, storageId);
        ensureChild(root, target);
        copy(canonicalSource, target);
        if (!hash.equals(sha256(target))) {
            target.delete();
            throw new IOException("Media hash verification failed");
        }
        SQLiteDatabase db = store.getWritableDatabase();
        db.beginTransaction();
        try {
            long id = store.insertDeletedMedia(messageId, storageId, hash,
                    mimeType == null || mimeType.isEmpty() ? "application/octet-stream" : mimeType, target.length());
            db.setTransactionSuccessful();
            return new DeletedMediaRecord(id, messageId, storageId, hash, mimeType, target.length());
        } finally {
            db.endTransaction();
            if (!target.exists() || store.findMediaByHash(hash) == null) target.delete();
        }
    }

    public synchronized boolean permanentlyDelete(String storageId) throws IOException {
        if (storageId == null || !storageId.matches("[0-9a-fA-F-]{36}")) return false;
        File root = mediaDirectory();
        File target = new File(root, storageId);
        ensureChild(root, target);
        if (target.exists() && !target.delete()) throw new IOException("Could not delete preserved media");
        return store.deleteDeletedMedia(storageId) > 0;
    }

    public File resolve(String storageId) throws IOException {
        if (storageId == null || !storageId.matches("[0-9a-fA-F-]{36}")) throw new IOException("Invalid media id");
        File root = mediaDirectory();
        File target = new File(root, storageId);
        ensureChild(root, target);
        if (!target.isFile()) throw new IOException("Preserved media is unavailable");
        return target;
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
}
