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
    private static final String META_GENERATION = "generation";

    private static final String ITEM_COLUMNS = "i.id, i.uuid, i.account_id, a.package_name, a.own_jid, i.chat_jid, "
            + "i.chat_alt_jid, i.message_key, i.from_me, i.sender_jid, i.media_kind, i.mime_type, i.sha256, "
            + "b.size_bytes, i.message_timestamp, i.imported_at, i.basis, i.caption, i.trashed_at, b.state, i.view_once, i.view_once_known";
    private static final String ITEM_FROM = " FROM items i JOIN accounts a ON a.id = i.account_id "
            + "JOIN blobs b ON b.sha256 = i.sha256 ";

    private final Sql sql;
    private final BlobStore blobs;
    private final LibraryLock lock;
    private final LongSupplier clock;
    private volatile DecodeProbe decodeProbe;

    /**
     * Device-side decode check (images: bounds decode; videos: duration and a video track).
     * Returns {@code null} when the file decodes, otherwise a reason. Optional: the structural
     * {@link MediaValidator} always runs.
     */
    public interface DecodeProbe {
        String check(File file, String mimeType);
    }

    public void setDecodeProbe(DecodeProbe probe) {
        this.decodeProbe = probe;
    }

    /** Structural validation plus the optional decode probe; throws INCOMPLETE_MEDIA. */
    void requireComplete(File file, String mime) throws IOException {
        String invalid = MediaValidator.validate(file, mime);
        DecodeProbe probe = decodeProbe;
        if (invalid == null && probe != null) {
            try {
                invalid = probe.check(file, mime);
            } catch (RuntimeException failure) {
                invalid = "decode_failed";
            }
        }
        if (invalid != null) {
            throw new LibraryException(LibraryException.Code.INCOMPLETE_MEDIA,
                    "The media is incomplete or cannot be decoded (" + invalid + ")");
        }
    }

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
            learnAlias(accountId, request.chatJid, request.chatAltJid);
            List<LibraryItem> matches = findAllByIdentity(accountId, request.chatJid, request.chatAltJid,
                    request.messageKey, request.fromMe);
            // Visible items decide; a single trashed match is restored by the import.
            List<LibraryItem> candidates = visible(matches).isEmpty() ? matches : visible(matches);
            if (candidates.size() > 1) {
                throw new LibraryException(LibraryException.Code.AMBIGUOUS,
                        "This message matches " + candidates.size() + " stored items; resolve them first");
            }
            LibraryItem existing = candidates.isEmpty() ? null : candidates.get(0);
            if (existing != null && blobs.verify(existing.sha256, existing.sizeBytes)) {
                markBlobOk(existing.sha256);
                if (existing.isTrashed()) {
                    sql.update("UPDATE items SET trashed_at = 0 WHERE id = ?", existing.id);
                    bumpGeneration();
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
        // A truncated download or a half-written file must never become a "kept" copy.
        requireComplete(staged.file, mime);
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
                                + "sender_jid, media_kind, mime_type, sha256, message_timestamp, imported_at, basis, caption, "
                                + "view_once, view_once_known) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1)",
                        id, accountId, request.chatJid, request.chatAltJid, request.messageKey,
                        request.fromMe ? 1 : 0, request.senderJid, kind, mime, staged.sha256,
                        request.messageTimestamp, now, basis, request.caption, request.viewOnce ? 1 : 0);
                return id;
            });
        } catch (RuntimeException failure) {
            // Bytes without a row are invisible; drop them unless another item owns them.
            if (outcome == BlobStore.CommitOutcome.CREATED && !isBlobReferenced(staged.sha256)) {
                deleteBlobQuietly(staged.sha256);
            }
            throw new LibraryException(LibraryException.Code.IO, "Could not record the media item", failure);
        }
        bumpGeneration();
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
        List<LibraryItem> matches = visible(findAllByIdentity(accountId, chatJid, chatAltJid, messageKey, fromMe));
        // An ambiguous association is never presented: no guess about which copy belongs here.
        return matches.size() == 1 ? matches.get(0) : null;
    }

    /** Whether a message matches more than one stored item (see {@link #findAmbiguities}). */
    public boolean isAmbiguous(String packageName, String ownJid, String chatJid, String chatAltJid,
                               String messageKey, boolean fromMe) {
        Long accountId = findAccount(packageName, ownJid);
        return accountId != null
                && visible(findAllByIdentity(accountId, chatJid, chatAltJid, messageKey, fromMe)).size() > 1;
    }

    /** Items of one conversation, newest message first. Both JID forms of the contact match. */
    public List<LibraryItem> listChat(String packageName, String ownJid, Collection<String> chatJids,
                                      boolean trashed) {
        Long accountId = findAccount(packageName, ownJid);
        List<LibraryItem> result = new ArrayList<>();
        if (accountId == null) return result;
        LinkedHashSet<String> jids = new LinkedHashSet<>();
        for (String jid : chatJids) if (jid != null && !jid.isEmpty()) jids.addAll(chatClosure(accountId, jid, null));
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

    /**
     * Items of one conversation filtered by origin: only view-once copies, or only normal media.
     * Feeds the separate "View once" tab and keeps the two kinds from mixing.
     */
    public List<LibraryItem> listChatByOrigin(String packageName, String ownJid, Collection<String> chatJids,
                                              boolean viewOnce) {
        List<LibraryItem> result = new ArrayList<>();
        for (LibraryItem item : listChat(packageName, ownJid, chatJids, false)) {
            if (item.viewOnce == viewOnce) result.add(item);
        }
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

    public boolean hasExplicitQuota() {
        return !sql.query("SELECT 1 FROM meta WHERE key = ?", META_QUOTA).isEmpty();
    }

    public static long clampQuota(long bytes) {
        return Math.max(MIN_QUOTA_BYTES, Math.min(MAX_QUOTA_BYTES, bytes));
    }

    /**
     * Settles the origin of a row migrated with an unknown view-once flag, using what WhatsApp
     * reports for the same message. Rows whose flag is already known are never changed.
     * Returns whether a row changed.
     */
    public boolean confirmOrigin(String packageName, String ownJid, String chatJid, String chatAltJid,
                                 String messageKey, boolean fromMe, boolean viewOnce) throws IOException {
        try (LibraryLock.Held ignored = lock.acquire(LOCK_TIMEOUT_MS)) {
            Long accountId = findAccount(packageName, ownJid);
            if (accountId == null) return false;
            List<LibraryItem> matches = visible(findAllByIdentity(accountId, chatJid, chatAltJid, messageKey, fromMe));
            if (matches.size() != 1) return false;
            LibraryItem item = matches.get(0);
            if (item.viewOnceKnown) return false;
            boolean changed = sql.update("UPDATE items SET view_once = ?, view_once_known = 1 WHERE id = ? "
                    + "AND view_once_known = 0", viewOnce ? 1 : 0, item.id) > 0;
            if (changed) bumpGeneration();
            return changed;
        }
    }

    // ---------------------------------------------------------------- first-run import progress

    /** Per-account progress of the first-run and incremental view-once import (see InitialImport). */
    public static final class SweepState {
        public final long watermark;
        public final boolean initialDone;
        public final long imported;

        public SweepState(long watermark, boolean initialDone, long imported) {
            this.watermark = watermark;
            this.initialDone = initialDone;
            this.imported = imported;
        }
    }

    private static String sweepKey(String packageName, String ownJid) {
        return "sweep\n" + packageName + "\n" + ownJid;
    }

    public SweepState sweepState(String packageName, String ownJid) {
        List<Map<String, Object>> rows = sql.query("SELECT value FROM meta WHERE key = ?", sweepKey(packageName, ownJid));
        if (rows.isEmpty()) return new SweepState(0, false, 0);
        String[] parts = Sql.Rows.getString(rows.get(0), "value").split(",");
        try {
            return new SweepState(Long.parseLong(parts[0]), "1".equals(parts[1]), Long.parseLong(parts[2]));
        } catch (RuntimeException invalid) {
            return new SweepState(0, false, 0);
        }
    }

    /** Stores progress; the watermark may be reset (WhatsApp database recreated) or advanced. */
    public void saveSweepState(String packageName, String ownJid, SweepState state) throws IOException {
        String value = Math.max(0, state.watermark) + "," + (state.initialDone ? "1" : "0") + "," + Math.max(0, state.imported);
        try (LibraryLock.Held ignored = lock.acquire(LOCK_TIMEOUT_MS)) {
            String key = sweepKey(packageName, ownJid);
            sql.inTransaction(() -> {
                if (sql.update("UPDATE meta SET value = ? WHERE key = ?", value, key) == 0) {
                    sql.insert("INSERT INTO meta (key, value) VALUES (?, ?)", key, value);
                }
                return null;
            });
        }
    }

    // ---------------------------------------------------------------- change tracking

    /**
     * A counter that changes whenever anything a viewer could have cached changes: an item
     * added, repaired, trashed, restored or purged, or a file found missing/damaged/recovered.
     * Persisted, so it keeps increasing across process restarts. Clients cache lookups and
     * thumbnails per generation and drop them as soon as it differs.
     */
    public long generation() {
        List<Map<String, Object>> rows = sql.query("SELECT value FROM meta WHERE key = ?", META_GENERATION);
        if (rows.isEmpty()) return 0L;
        try {
            return Long.parseLong(Sql.Rows.getString(rows.get(0), "value"));
        } catch (NumberFormatException invalid) {
            return 0L;
        }
    }

    void bumpGeneration() {
        long next = generation() + 1;
        if (sql.update("UPDATE meta SET value = ? WHERE key = ?", String.valueOf(next), META_GENERATION) == 0) {
            sql.insert("INSERT INTO meta (key, value) VALUES (?, ?)", META_GENERATION, String.valueOf(next));
        }
    }

    // ---------------------------------------------------------------- controlled deletion

    /** Moves an item to the trash; its bytes stay until it is purged. */
    public boolean trash(String uuid) throws IOException {
        try (LibraryLock.Held ignored = lock.acquire(LOCK_TIMEOUT_MS)) {
            boolean changed = sql.update("UPDATE items SET trashed_at = ? WHERE uuid = ? AND trashed_at = 0",
                    Math.max(1, clock.getAsLong()), uuid) > 0;
            if (changed) bumpGeneration();
            return changed;
        }
    }

    public boolean restoreFromTrash(String uuid) throws IOException {
        try (LibraryLock.Held ignored = lock.acquire(LOCK_TIMEOUT_MS)) {
            boolean changed = sql.update("UPDATE items SET trashed_at = 0 WHERE uuid = ? AND trashed_at > 0", uuid) > 0;
            if (changed) bumpGeneration();
            return changed;
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
            bumpGeneration();
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
        /** Files without a catalog record, moved to quarantine (never deleted by maintenance). */
        public int orphanBlobsQuarantined;
        /** Catalog blob records no item uses; the record is dropped and the file quarantined. */
        public int unreferencedBlobsQuarantined;
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
                    blobs.quarantine(sha);
                    report.unreferencedBlobsQuarantined++;
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
                    bumpGeneration();
                    if ("missing".equals(actual)) report.markedMissing++;
                    else if ("corrupt".equals(actual)) report.markedCorrupt++;
                    else report.recovered++;
                }
            }
            for (String sha : blobs.listBlobs()) {
                if (!known.contains(sha)) {
                    blobs.quarantine(sha);
                    report.orphanBlobsQuarantined++;
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
        List<LibraryItem> all = findAllByIdentity(accountId, chatJid, chatAltJid, messageKey, fromMe);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * Every item of the account for this message key and direction whose chat is the given chat
     * or any alias of it (phone JID ↔ LID, learned). More than one result is an ambiguity.
     */
    List<LibraryItem> findAllByIdentity(long accountId, String chatJid, String chatAltJid, String messageKey,
                                        boolean fromMe) {
        java.util.Set<String> chats = chatClosure(accountId, chatJid, chatAltJid);
        List<LibraryItem> result = new ArrayList<>();
        if (chats.isEmpty() || messageKey == null) return result;
        for (Map<String, Object> row : sql.query("SELECT " + ITEM_COLUMNS + ITEM_FROM
                + "WHERE i.account_id = ? AND i.message_key = ? AND i.from_me = ? ORDER BY i.id",
                accountId, messageKey, fromMe ? 1 : 0)) {
            LibraryItem item = new LibraryItem(row);
            if (chats.contains(item.chatJid) || (item.chatAltJid != null && chats.contains(item.chatAltJid))) {
                result.add(item);
            }
        }
        return result;
    }

    static List<LibraryItem> visible(List<LibraryItem> items) {
        List<LibraryItem> result = new ArrayList<>();
        for (LibraryItem item : items) if (!item.isTrashed()) result.add(item);
        return result;
    }

    /** The chat and every alias reachable from it (bounded). */
    java.util.Set<String> chatClosure(long accountId, String chatJid, String chatAltJid) {
        java.util.LinkedHashSet<String> chats = new java.util.LinkedHashSet<>();
        if (chatJid != null && !chatJid.isEmpty()) chats.add(chatJid);
        if (chatAltJid != null && !chatAltJid.isEmpty()) chats.add(chatAltJid);
        java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>(chats);
        while (!pending.isEmpty() && chats.size() < 16) {
            String jid = pending.poll();
            for (Map<String, Object> row : sql.query("SELECT alias FROM chat_aliases WHERE account_id = ? AND jid = ?",
                    accountId, jid)) {
                String alias = Sql.Rows.getString(row, "alias");
                if (chats.add(alias)) pending.add(alias);
            }
        }
        return chats;
    }

    /** Records that two JIDs address the same chat for this account (both directions). */
    void learnAlias(long accountId, String jid, String alias) {
        if (jid == null || alias == null || jid.isEmpty() || alias.isEmpty() || jid.equals(alias)) return;
        long now = clock.getAsLong();
        long added = sql.insert("INSERT OR IGNORE INTO chat_aliases (account_id, jid, alias, learned_at) VALUES (?,?,?,?)",
                accountId, jid, alias, now);
        sql.insert("INSERT OR IGNORE INTO chat_aliases (account_id, jid, alias, learned_at) VALUES (?,?,?,?)",
                accountId, alias, jid, now);
        if (added > 0) bumpGeneration(); // lookups may now resolve differently
    }

    /** Public, account-scoped form used when WhatsApp reports a phone JID ↔ LID pair. */
    public void learnChatAlias(String packageName, String ownJid, String jid, String alias) throws IOException {
        try (LibraryLock.Held ignored = lock.acquire(LOCK_TIMEOUT_MS)) {
            Long accountId = findAccount(packageName, ownJid);
            if (accountId != null) learnAlias(accountId, jid, alias);
        }
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
