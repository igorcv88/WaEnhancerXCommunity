package com.waenhancer.library.core;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * The persistent private media library: catalog + content-addressed blobs.
 *
 * <p>Consistency model. Bytes are always made durable before the catalog row that refers to
 * them (stage → sync → atomic rename → transaction). The only states an interruption can leave
 * are therefore (a) a staging file, (b) a blob without a row, or (c) a row whose blob was later
 * lost or damaged outside the module. {@link #reconcile} removes (a) and (b) and marks (c) as
 * {@code missing}/{@code corrupt} without deleting the row, so the user still sees the item and
 * a re-import or backup restore repairs it in place.</p>
 *
 * <p>Every mutation holds {@link LibraryLock}; reads use SQLite's own isolation.</p>
 */
public final class MediaLibrary {
    public static final long GIB = 1024L * 1024L * 1024L;
    /** Default quota: large enough for a real library, bounded by the device reserve anyway. */
    public static final long DEFAULT_QUOTA_BYTES = 16L * GIB;
    public static final long MIN_QUOTA_BYTES = 64L * 1024L * 1024L;
    public static final long MAX_QUOTA_BYTES = 1024L * GIB;
    /** Per-item limit (WhatsApp documents can be 2 GB; videos are far smaller). */
    public static final long MAX_ITEM_BYTES = 4L * GIB;
    /** How long a trashed item stays recoverable before {@link #purgeExpiredTrash} may erase it. */
    public static final long TRASH_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L;

    private static final long LOCK_TIMEOUT_MS = 30_000L;
    private static final String META_QUOTA = "quota_bytes";

    private static final String ITEM_COLUMNS = "i.id, i.uuid, i.account_id, a.package_name, a.own_jid, i.chat_jid, "
            + "i.chat_alt_jid, i.message_key, i.from_me, i.sender_jid, i.media_kind, i.mime_type, i.sha256, "
            + "b.size_bytes, i.message_timestamp, i.imported_at, i.basis, i.caption, i.trashed_at, b.state";
    private static final String ITEM_FROM = " FROM items i JOIN accounts a ON a.id = i.account_id "
            + "JOIN blobs b ON b.sha256 = i.sha256 ";

    private final Sql sql;
    private final BlobStore blobs;
    private final LibraryLock lock;
    private final LongSupplier clock;

    public MediaLibrary(Sql sql, BlobStore blobs, LibraryLock lock, LongSupplier clock) throws LibraryException {
        this.sql = sql;
        this.blobs = blobs;
        this.lock = lock;
        this.clock = clock;
        new LibrarySchema().migrate(sql);
    }

    public BlobStore blobs() {
        return blobs;
    }

    LibraryLock lock() {
        return lock;
    }

    Sql sql() {
        return sql;
    }

    // ---------------------------------------------------------------- import

    public enum ImportStatus {
        /** New item with new bytes. */
        CREATED,
        /** New item whose bytes were already stored for another message (no extra space). */
        DEDUPLICATED,
        /** The same message was already kept and verified; nothing was read or written. */
        ALREADY_PRESENT,
        /** The item existed but its bytes were missing or corrupt; they were restored. */
        REPAIRED,
        /** The item was in the trash and is visible again. */
        RESTORED_FROM_TRASH
    }

    public static final class ImportResult {
        public final ImportStatus status;
        public final LibraryItem item;

        ImportResult(ImportStatus status, LibraryItem item) {
            this.status = status;
            this.item = item;
        }
    }

    /** Opens the media stream only when bytes are actually needed (idempotent re-imports skip it). */
    @FunctionalInterface
    public interface StreamSource {
        InputStream open() throws IOException;
    }

    /**
     * Keeps one media item. The caller must have obtained an explicit user action; the request's
     * facts are checked against {@link ImportPolicy} before any bytes are read.
     */
    public ImportResult importMedia(ImportRequest request, StreamSource source) throws IOException {
        String basis = ImportPolicy.authorize(request.facts());
        request.validate();
        try (LibraryLock.Held ignored = lock.acquire(LOCK_TIMEOUT_MS)) {
            long accountId = ensureAccount(request.packageName, request.ownJid);
            LibraryItem existing = findByIdentity(accountId, request.chatJid, request.chatAltJid,
                    request.messageKey, request.fromMe);
            if (existing != null && blobs.verify(existing.sha256, existing.sizeBytes)) {
                markBlobOk(existing.sha256);
                if (existing.isTrashed()) {
                    sql.update("UPDATE items SET trashed_at = 0 WHERE id = ?", existing.id);
                    return new ImportResult(ImportStatus.RESTORED_FROM_TRASH, get(existing.uuid));
                }
                return new ImportResult(ImportStatus.ALREADY_PRESENT, get(existing.uuid));
            }
            BlobStore.Staged staged;
            try (InputStream input = source.open()) {
                staged = blobs.stage(input, MAX_ITEM_BYTES);
            }
            try {
                return storeStaged(accountId, request, basis, staged, existing);
            } finally {
                staged.discard();
            }
        }
    }

    private ImportResult storeStaged(long accountId, ImportRequest request, String basis, BlobStore.Staged staged,
                                     LibraryItem existing) throws IOException {
        String mime = staged.sniffedMime;
        String kind = MediaSniffer.kindOf(mime);
        if (kind == null) {
            throw new LibraryException(LibraryException.Code.UNSUPPORTED_MEDIA, "Only photos and videos can be kept");
        }
        // Quota is evaluated after deduplication: identical, verified bytes cost nothing.
        boolean alreadyStored = blobs.verify(staged.sha256, staged.size);
        if (!alreadyStored && usedBytes() + staged.size > quotaBytes()) {
            throw new LibraryException(LibraryException.Code.QUOTA_EXCEEDED, "The media library is full");
        }
        BlobStore.CommitOutcome outcome = blobs.commit(staged);
        long now = clock.getAsLong();
        String uuid;
        ImportStatus status;
        try {
            uuid = sql.inTransaction(() -> {
                upsertBlob(staged.sha256, staged.size, mime, now);
                if (existing != null) {
                    sql.update("UPDATE items SET sha256 = ?, mime_type = ?, media_kind = ?, trashed_at = 0 WHERE id = ?",
                            staged.sha256, mime, kind, existing.id);
                    return existing.uuid;
                }
                String id = UUID.randomUUID().toString();
                sql.insert("INSERT INTO items (uuid, account_id, chat_jid, chat_alt_jid, message_key, from_me, "
                                + "sender_jid, media_kind, mime_type, sha256, message_timestamp, imported_at, basis, caption) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        id, accountId, request.chatJid, request.chatAltJid, request.messageKey,
                        request.fromMe ? 1 : 0, request.senderJid, kind, mime, staged.sha256,
                        request.messageTimestamp, now, basis, request.caption);
                return id;
            });
        } catch (RuntimeException failure) {
            // Bytes without a row are invisible; drop them unless another item owns them.
            if (outcome == BlobStore.CommitOutcome.CREATED && !isBlobReferenced(staged.sha256)) {
                deleteBlobQuietly(staged.sha256);
            }
            throw new LibraryException(LibraryException.Code.IO, "Could not record the media item", failure);
        }
        if (existing != null) {
            status = ImportStatus.REPAIRED;
            if (!existing.sha256.equals(staged.sha256)) collectBlob(existing.sha256);
        } else {
            status = outcome == BlobStore.CommitOutcome.REUSED_VERIFIED ? ImportStatus.DEDUPLICATED : ImportStatus.CREATED;
        }
        return new ImportResult(status, get(uuid));
    }

    // ---------------------------------------------------------------- queries

    public LibraryItem get(String uuid) {
        if (uuid == null) return null;
        List<Map<String, Object>> rows = sql.query("SELECT " + ITEM_COLUMNS + ITEM_FROM + "WHERE i.uuid = ?", uuid);
        return rows.isEmpty() ? null : new LibraryItem(rows.get(0));
    }

    /** The visible (non-trashed) item kept for a message, or {@code null}. */
    public LibraryItem lookup(String packageName, String ownJid, String chatJid, String chatAltJid,
                              String messageKey, boolean fromMe) {
        Long accountId = findAccount(packageName, ownJid);
        if (accountId == null) return null;
        LibraryItem item = findByIdentity(accountId, chatJid, chatAltJid, messageKey, fromMe);
        return item == null || item.isTrashed() ? null : item;
    }

    /** Items of one conversation, newest message first. Both JID forms of the contact match. */
    public List<LibraryItem> listChat(String packageName, String ownJid, Collection<String> chatJids,
                                      boolean trashed) {
        Long accountId = findAccount(packageName, ownJid);
        List<LibraryItem> result = new ArrayList<>();
        if (accountId == null) return result;
        LinkedHashSet<String> jids = new LinkedHashSet<>();
        for (String jid : chatJids) if (jid != null && !jid.isEmpty()) jids.add(jid);
        if (jids.isEmpty()) return result;
        StringBuilder placeholders = new StringBuilder();
        List<Object> args = new ArrayList<>();
        args.add(accountId);
        for (String jid : jids) {
            if (placeholders.length() > 0) placeholders.append(',');
            placeholders.append('?');
        }
        args.addAll(jids);
        args.addAll(jids);
        String sqlText = "SELECT " + ITEM_COLUMNS + ITEM_FROM + "WHERE i.account_id = ? AND (i.chat_jid IN ("
                + placeholders + ") OR i.chat_alt_jid IN (" + placeholders + ")) AND "
                + (trashed ? "i.trashed_at > 0" : "i.trashed_at = 0")
                + " ORDER BY i.message_timestamp DESC, i.id DESC";
        for (Map<String, Object> row : sql.query(sqlText, args.toArray())) result.add(new LibraryItem(row));
        return result;
    }

    /** Every item (all accounts), for the module's own library screen. */
    public List<LibraryItem> listAll(boolean trashed) {
        List<LibraryItem> result = new ArrayList<>();
        for (Map<String, Object> row : sql.query("SELECT " + ITEM_COLUMNS + ITEM_FROM + "WHERE "
                + (trashed ? "i.trashed_at > 0" : "i.trashed_at = 0")
                + " ORDER BY i.message_timestamp DESC, i.id DESC")) {
            result.add(new LibraryItem(row));
        }
        return result;
    }

    public File fileOf(LibraryItem item) throws IOException {
        return blobs.path(item.sha256);
    }

    /** Bytes used by distinct stored blobs (deduplicated). */
    public long usedBytes() {
        return Sql.Rows.getLong(sql.query("SELECT COALESCE(SUM(size_bytes), 0) AS n FROM blobs WHERE state != 'missing'")
                .get(0), "n");
    }

    public long quotaBytes() {
        List<Map<String, Object>> rows = sql.query("SELECT value FROM meta WHERE key = ?", META_QUOTA);
        if (rows.isEmpty()) return DEFAULT_QUOTA_BYTES;
        try {
            return clampQuota(Long.parseLong(Sql.Rows.getString(rows.get(0), "value")));
        } catch (NumberFormatException invalid) {
            return DEFAULT_QUOTA_BYTES;
        }
    }

    public void setQuotaBytes(long bytes) throws IOException {
        long value = clampQuota(bytes);
        try (LibraryLock.Held ignored = lock.acquire(LOCK_TIMEOUT_MS)) {
            // Plain statements rather than UPSERT: Android 9 ships SQLite 3.22, which predates it.
            sql.inTransaction(() -> {
                if (sql.update("UPDATE meta SET value = ? WHERE key = ?", String.valueOf(value), META_QUOTA) == 0) {
                    sql.insert("INSERT INTO meta (key, value) VALUES (?, ?)", META_QUOTA, String.valueOf(value));
                }
                return null;
            });
        }
    }

    public static long clampQuota(long bytes) {
        return Math.max(MIN_QUOTA_BYTES, Math.min(MAX_QUOTA_BYTES, bytes));
    }

    // ---------------------------------------------------------------- controlled deletion

    /** Moves an item to the trash; its bytes stay until it is purged. */
    public boolean trash(String uuid) throws IOException {
        try (LibraryLock.Held ignored = lock.acquire(LOCK_TIMEOUT_MS)) {
            return sql.update("UPDATE items SET trashed_at = ? WHERE uuid = ? AND trashed_at = 0",
                    Math.max(1, clock.getAsLong()), uuid) > 0;
        }
    }

    public boolean restoreFromTrash(String uuid) throws IOException {
        try (LibraryLock.Held ignored = lock.acquire(LOCK_TIMEOUT_MS)) {
            return sql.update("UPDATE items SET trashed_at = 0 WHERE uuid = ? AND trashed_at > 0", uuid) > 0;
        }
    }

    /**
     * Permanently erases a trashed item. Refuses items that are not in the trash, so a single
     * accidental call can never erase visible media. Shared bytes survive while another item
     * still references them.
     */
    public boolean purge(String uuid) throws IOException {
        try (LibraryLock.Held ignored = lock.acquire(LOCK_TIMEOUT_MS)) {
            LibraryItem item = get(uuid);
            if (item == null || !item.isTrashed()) return false;
            sql.update("DELETE FROM items WHERE id = ?", item.id);
            collectBlob(item.sha256);
            return true;
        }
    }

    /** Purges items trashed before {@code now - TRASH_RETENTION_MS}. */
    public int purgeExpiredTrash() throws IOException {
        long cutoff = clock.getAsLong() - TRASH_RETENTION_MS;
        int purged = 0;
        for (Map<String, Object> row : sql.query("SELECT uuid FROM items WHERE trashed_at > 0 AND trashed_at < ?", cutoff)) {
            if (purge(Sql.Rows.getString(row, "uuid"))) purged++;
        }
        return purged;
    }

    // ---------------------------------------------------------------- reconciliation

    public static final class ReconcileReport {
        public int stagingRemoved;
        public int orphanBlobsRemoved;
        public int unreferencedBlobsRemoved;
        public int markedMissing;
        public int markedCorrupt;
        public int recovered;
        public int verified;
    }

    /**
     * Repairs what an interruption or external damage can leave behind.
     *
     * @param deep re-hash every blob (slow); otherwise only presence and size are checked
     */
    public ReconcileReport reconcile(boolean deep) throws IOException {
        ReconcileReport report = new ReconcileReport();
        try (LibraryLock.Held ignored = lock.acquire(LOCK_TIMEOUT_MS)) {
            report.stagingRemoved = blobs.cleanStaging();
            LinkedHashSet<String> known = new LinkedHashSet<>();
            for (Map<String, Object> row : sql.query("SELECT sha256, size_bytes, state FROM blobs")) {
                String sha = Sql.Rows.getString(row, "sha256");
                long size = Sql.Rows.getLong(row, "size_bytes");
                String state = Sql.Rows.getString(row, "state");
                known.add(sha);
                if (!isBlobReferenced(sha)) {
                    sql.update("DELETE FROM blobs WHERE sha256 = ?", sha);
                    deleteBlobQuietly(sha);
                    report.unreferencedBlobsRemoved++;
                    continue;
                }
                String actual;
                if (!blobs.path(sha).isFile()) actual = "missing";
                else if (!blobs.existsWithSize(sha, size)) actual = "corrupt";
                else if (deep) actual = blobs.verify(sha, size) ? "ok" : "corrupt";
                else actual = "ok".equals(state) ? "ok" : (blobs.verify(sha, size) ? "ok" : "corrupt");
                if ("ok".equals(actual)) report.verified++;
                if (!actual.equals(state)) {
                    sql.update("UPDATE blobs SET state = ?, verified_at = ? WHERE sha256 = ?", actual, clock.getAsLong(), sha);
                    if ("missing".equals(actual)) report.markedMissing++;
                    else if ("corrupt".equals(actual)) report.markedCorrupt++;
                    else report.recovered++;
                }
            }
            for (String sha : blobs.listBlobs()) {
                if (!known.contains(sha)) {
                    deleteBlobQuietly(sha);
                    report.orphanBlobsRemoved++;
                }
            }
        }
        return report;
    }

    // ---------------------------------------------------------------- internals (also used by backup)

    long ensureAccount(String packageName, String ownJid) {
        Long existing = findAccount(packageName, ownJid);
        if (existing != null) return existing;
        sql.insert("INSERT OR IGNORE INTO accounts (package_name, own_jid, created_at) VALUES (?,?,?)",
                packageName, ownJid, clock.getAsLong());
        Long created = findAccount(packageName, ownJid);
        if (created == null) throw new IllegalStateException("Could not create library account");
        return created;
    }

    Long findAccount(String packageName, String ownJid) {
        if (packageName == null || ownJid == null) return null;
        List<Map<String, Object>> rows = sql.query("SELECT id FROM accounts WHERE package_name = ? AND own_jid = ?",
                packageName, ownJid);
        return rows.isEmpty() ? null : Sql.Rows.getLong(rows.get(0), "id");
    }

    LibraryItem findByIdentity(long accountId, String chatJid, String chatAltJid, String messageKey, boolean fromMe) {
        // The same chat may be addressed by its phone JID or its LID depending on the WhatsApp
        // build and code path; either form identifies the same message for this account.
        List<Object> args = new ArrayList<>();
        args.add(accountId);
        args.add(messageKey);
        args.add(fromMe ? 1 : 0);
        String alt = chatAltJid == null ? chatJid : chatAltJid;
        args.add(chatJid);
        args.add(alt);
        args.add(chatJid);
        args.add(alt);
        List<Map<String, Object>> rows = sql.query("SELECT " + ITEM_COLUMNS + ITEM_FROM
                + "WHERE i.account_id = ? AND i.message_key = ? AND i.from_me = ? "
                + "AND (i.chat_jid IN (?, ?) OR i.chat_alt_jid IN (?, ?)) ORDER BY i.id LIMIT 1", args.toArray());
        return rows.isEmpty() ? null : new LibraryItem(rows.get(0));
    }

    void upsertBlob(String sha256, long size, String mime, long now) {
        if (sql.update("UPDATE blobs SET size_bytes = ?, verified_at = ?, state = 'ok' WHERE sha256 = ?",
                size, now, sha256) == 0) {
            sql.insert("INSERT INTO blobs (sha256, size_bytes, mime_type, created_at, verified_at, state) "
                    + "VALUES (?,?,?,?,?,'ok')", sha256, size, mime, now, now);
        }
    }

    private void markBlobOk(String sha256) {
        sql.update("UPDATE blobs SET state = 'ok', verified_at = ? WHERE sha256 = ? AND state != 'ok'",
                clock.getAsLong(), sha256);
    }

    boolean isBlobReferenced(String sha256) {
        return !sql.query("SELECT 1 FROM items WHERE sha256 = ? LIMIT 1", sha256).isEmpty();
    }

    /** Deletes a blob row and file once no item references it. */
    void collectBlob(String sha256) {
        if (isBlobReferenced(sha256)) return;
        sql.update("DELETE FROM blobs WHERE sha256 = ?", sha256);
        deleteBlobQuietly(sha256);
    }

    private void deleteBlobQuietly(String sha256) {
        try {
            blobs.delete(sha256);
        } catch (IOException ignored) {
            // A later reconcile removes the orphan.
        }
    }

    long now() {
        return clock.getAsLong();
    }
}
