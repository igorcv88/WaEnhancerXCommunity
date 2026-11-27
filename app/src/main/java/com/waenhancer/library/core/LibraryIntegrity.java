package com.waenhancer.library.core;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Detection and correction of inconsistencies without losing stored files.
 *
 * <ul>
 *   <li>{@link #inspect}: a read-only report of everything that does not line up — files without
 *   a record (and quarantined files), records without a file, damaged files, unused records,
 *   ambiguous associations, unknown origins, leftover staging files. Nothing is changed.</li>
 *   <li>{@link #findAmbiguities} / {@link #resolveIdentical}: one message matched by several
 *   items (typically the same message once under a phone JID and once under a LID before the
 *   alias was known). Only groups whose items share the exact same bytes are resolved
 *   automatically (the extra rows go to the trash, the shared file stays); groups with different
 *   content are reported for the user and never resolved by a guess.</li>
 *   <li>{@link #verifyBatch}: periodic SHA-256 re-verification in bounded batches, oldest
 *   verification first, so every file is re-hashed in rotation without a long stall.</li>
 * </ul>
 */
public final class LibraryIntegrity {
    private static final long LOCK_TIMEOUT_MS = 60_000L;

    private LibraryIntegrity() { }

    // ---------------------------------------------------------------- report

    public static final class Report {
        /** Files in the content store with no catalog record. */
        public final List<String> filesWithoutRecord = new ArrayList<>();
        /** Files kept in quarantine by maintenance (never deleted automatically). */
        public final List<String> quarantined = new ArrayList<>();
        /** Item uuids whose file is missing. */
        public final List<String> recordsWithoutFile = new ArrayList<>();
        /** Item uuids whose file is damaged (size, or SHA-256 when deep). */
        public final List<String> damaged = new ArrayList<>();
        /** Blob records no item uses. */
        public final List<String> unusedRecords = new ArrayList<>();
        public final List<AmbiguousGroup> ambiguous = new ArrayList<>();
        public int unknownOrigin;
        public int stagingFiles;
        public int items;
        public int blobs;

        public boolean clean() {
            return filesWithoutRecord.isEmpty() && recordsWithoutFile.isEmpty() && damaged.isEmpty()
                    && unusedRecords.isEmpty() && ambiguous.isEmpty() && stagingFiles == 0;
        }
    }

    public static Report inspect(MediaLibrary library, boolean deep) throws IOException {
        Report report = new Report();
        Sql sql = library.sql();
        BlobStore blobs = library.blobs();
        try (LibraryLock.Held ignored = library.lock().acquire(LOCK_TIMEOUT_MS)) {
            Map<String, Long> sizes = new HashMap<>();
            for (Map<String, Object> row : sql.query("SELECT sha256, size_bytes FROM blobs")) {
                sizes.put(Sql.Rows.getString(row, "sha256"), Sql.Rows.getLong(row, "size_bytes"));
            }
            report.blobs = sizes.size();
            Set<String> referenced = new LinkedHashSet<>();
            for (boolean trashed : new boolean[] { false, true }) {
                for (LibraryItem item : library.listAll(trashed)) {
                    report.items++;
                    referenced.add(item.sha256);
                    File file = blobs.path(item.sha256);
                    if (!file.isFile()) report.recordsWithoutFile.add(item.uuid);
                    else if (file.length() != item.sizeBytes
                            || (deep && !blobs.verify(item.sha256, item.sizeBytes))) report.damaged.add(item.uuid);
                    if (!item.viewOnceKnown) report.unknownOrigin++;
                }
            }
            for (String sha : sizes.keySet()) if (!referenced.contains(sha)) report.unusedRecords.add(sha);
            for (String sha : blobs.listBlobs()) if (!sizes.containsKey(sha)) report.filesWithoutRecord.add(sha);
            report.quarantined.addAll(blobs.listQuarantine());
            File[] staging = new File(blobs.root(), "staging").listFiles();
            report.stagingFiles = staging == null ? 0 : staging.length;
            report.ambiguous.addAll(findAmbiguities(library));
        }
        return report;
    }

    // ---------------------------------------------------------------- ambiguity

    public static final class AmbiguousGroup {
        public final long accountId;
        public final String messageKey;
        public final boolean fromMe;
        public final List<LibraryItem> items;

        AmbiguousGroup(long accountId, String messageKey, boolean fromMe, List<LibraryItem> items) {
            this.accountId = accountId;
            this.messageKey = messageKey;
            this.fromMe = fromMe;
            this.items = items;
        }

        /** Every item holds the same bytes: resolving loses nothing. */
        public boolean identicalContent() {
            for (LibraryItem item : items) if (!item.sha256.equals(items.get(0).sha256)) return false;
            return true;
        }
    }

    /** Groups of visible or trashed items that the same message (chat incl. aliases) resolves to. */
    public static List<AmbiguousGroup> findAmbiguities(MediaLibrary library) {
        Sql sql = library.sql();
        List<AmbiguousGroup> groups = new ArrayList<>();
        for (Map<String, Object> row : sql.query("SELECT account_id, message_key, from_me FROM items "
                + "GROUP BY account_id, message_key, from_me HAVING COUNT(*) > 1")) {
            long account = Sql.Rows.getLong(row, "account_id");
            String key = Sql.Rows.getString(row, "message_key");
            boolean fromMe = Sql.Rows.getLong(row, "from_me") != 0;
            List<LibraryItem> candidates = new ArrayList<>();
            for (Map<String, Object> item : sql.query("SELECT uuid FROM items WHERE account_id = ? AND message_key = ? "
                    + "AND from_me = ? ORDER BY id", account, key, fromMe ? 1 : 0)) {
                LibraryItem candidate = library.get(Sql.Rows.getString(item, "uuid"));
                if (!candidate.isTrashed()) candidates.add(candidate); // trash never makes a message ambiguous
            }
            // Connected components by chat identity (chat, recorded alt, learned aliases).
            List<Set<String>> closures = new ArrayList<>();
            for (LibraryItem item : candidates) closures.add(library.chatClosure(account, item.chatJid, item.chatAltJid));
            boolean[] seen = new boolean[candidates.size()];
            for (int i = 0; i < candidates.size(); i++) {
                if (seen[i]) continue;
                List<LibraryItem> component = new ArrayList<>();
                ArrayList<Integer> stack = new ArrayList<>();
                stack.add(i);
                seen[i] = true;
                while (!stack.isEmpty()) {
                    int at = stack.remove(stack.size() - 1);
                    component.add(candidates.get(at));
                    for (int j = 0; j < candidates.size(); j++) {
                        if (!seen[j] && intersects(closures.get(at), closures.get(j))) {
                            seen[j] = true;
                            stack.add(j);
                        }
                    }
                }
                if (component.size() > 1) groups.add(new AmbiguousGroup(account, key, fromMe, component));
            }
        }
        return groups;
    }

    private static boolean intersects(Set<String> a, Set<String> b) {
        for (String value : a) if (b.contains(value)) return true;
        return false;
    }

    /**
     * Resolves every ambiguous group whose items hold identical bytes: the oldest item stays,
     * the others move to the trash (restorable for 30 days; the shared file is untouched). Groups
     * with different content are left for the user. Returns the number of items moved.
     */
    public static int resolveIdentical(MediaLibrary library) throws IOException {
        int moved = 0;
        for (AmbiguousGroup group : findAmbiguities(library)) {
            if (!group.identicalContent()) continue;
            for (int i = 1; i < group.items.size(); i++) {
                if (library.trash(group.items.get(i).uuid)) moved++;
            }
        }
        return moved;
    }

    /**
     * The user chose which item is right in a group with different content: the others go to
     * the trash (still restorable, and their files kept until the trash expires).
     */
    public static int resolveKeeping(MediaLibrary library, String keepUuid) throws IOException {
        int moved = 0;
        for (AmbiguousGroup group : findAmbiguities(library)) {
            boolean contains = false;
            for (LibraryItem item : group.items) contains |= item.uuid.equals(keepUuid);
            if (!contains) continue;
            for (LibraryItem item : group.items) {
                if (!item.uuid.equals(keepUuid) && library.trash(item.uuid)) moved++;
            }
        }
        return moved;
    }

    // ---------------------------------------------------------------- periodic SHA-256

    public static final class VerifyReport {
        public int checked;
        public long bytes;
        public int ok;
        public int damaged;
        public int missing;
        public int recovered;
    }

    /**
     * Re-hashes stored files, least recently verified first, until {@code maxBytes} were read
     * (at least one file). Updates each record's state and verification time.
     */
    public static VerifyReport verifyBatch(MediaLibrary library, long maxBytes) throws IOException {
        VerifyReport report = new VerifyReport();
        Sql sql = library.sql();
        BlobStore blobs = library.blobs();
        try (LibraryLock.Held ignored = library.lock().acquire(LOCK_TIMEOUT_MS)) {
            for (Map<String, Object> row : sql.query("SELECT sha256, size_bytes, state FROM blobs "
                    + "ORDER BY verified_at ASC, sha256 ASC")) {
                if (report.checked > 0 && report.bytes >= maxBytes) break;
                String sha = Sql.Rows.getString(row, "sha256");
                long size = Sql.Rows.getLong(row, "size_bytes");
                String before = Sql.Rows.getString(row, "state");
                String state;
                if (!blobs.path(sha).isFile()) {
                    state = "missing";
                    report.missing++;
                } else if (blobs.verify(sha, size)) {
                    state = "ok";
                    report.ok++;
                    report.bytes += size;
                } else {
                    state = "corrupt";
                    report.damaged++;
                    report.bytes += size;
                }
                report.checked++;
                if (!"ok".equals(before) && "ok".equals(state)) report.recovered++;
                sql.update("UPDATE blobs SET state = ?, verified_at = ? WHERE sha256 = ?", state, library.now(), sha);
                if (!state.equals(before)) library.bumpGeneration();
            }
        }
        return report;
    }

    // ---------------------------------------------------------------- canonical dump (backup tests)

    /** Order-independent text dump of the catalog's meaningful content (no row ids or counters). */
    public static String canonicalDump(MediaLibrary library) {
        Sql sql = library.sql();
        StringBuilder out = new StringBuilder();
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> row : sql.query("SELECT a.package_name, a.own_jid, i.uuid, i.chat_jid, i.chat_alt_jid, "
                + "i.message_key, i.from_me, i.sender_jid, i.media_kind, i.mime_type, i.sha256, i.message_timestamp, "
                + "i.imported_at, i.basis, i.caption, i.trashed_at, i.view_once, i.view_once_known "
                + "FROM items i JOIN accounts a ON a.id = i.account_id")) {
            lines.add("item " + new LinkedHashMap<>(row));
        }
        for (Map<String, Object> row : sql.query("SELECT sha256, size_bytes, mime_type FROM blobs")) {
            lines.add("blob " + new LinkedHashMap<>(row));
        }
        for (Map<String, Object> row : sql.query("SELECT a.package_name, a.own_jid, c.jid, c.alias "
                + "FROM chat_aliases c JOIN accounts a ON a.id = c.account_id")) {
            lines.add("alias " + new LinkedHashMap<>(row));
        }
        lines.add("quota " + library.quotaBytes());
        java.util.Collections.sort(lines);
        for (String line : lines) out.append(line).append('\n');
        return out.toString();
    }
}
