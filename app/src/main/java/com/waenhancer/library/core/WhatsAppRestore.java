package com.waenhancer.library.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

/**
 * Writes a kept normal photo/video back to the path WhatsApp still records for its message.
 *
 * <p>Separate switch ("Restore into WhatsApp", on by default by owner decision, can be turned
 * off) because it writes into WhatsApp's own storage. Rules, each covered by tests:</p>
 * <ul>
 *   <li>only when the switch is on; never for view-once; never when a deliberate user deletion is
 *   known; only inside WhatsApp's media roots;</li>
 *   <li><b>never replaces a file</b>, including one WhatsApp creates while the copy runs: the
 *   final step is either a hard link (atomic, fails if the name exists) or, where the file system
 *   has no hard links, an exclusive create ({@code O_CREAT|O_EXCL}) of the target followed by a
 *   local copy. {@code File.renameTo}, which silently replaces on Linux, is not used;</li>
 *   <li>every operation uses its own unique temporary name, so concurrent restores cannot collide;</li>
 *   <li>a journal entry is written before anything touches WhatsApp's folders and removed after,
 *   so {@link #recover} can clean a temporary file or a partial target left by a process killed
 *   mid-way; a complete target (right size and SHA-256) is kept.</li>
 * </ul>
 */
public final class WhatsAppRestore {
    static final String TEMP_PREFIX = ".waex-restore-";

    private WhatsAppRestore() { }

    public enum Outcome {
        RESTORED, DISABLED, VIEW_ONCE, USER_CLEARED, OUTSIDE_ROOTS, ALREADY_PRESENT, NO_PARENT, FAILED
    }

    public interface Source {
        InputStream open() throws IOException;
    }

    /** Test seam: whether the final step may use a hard link. */
    interface Linker {
        void link(File target, File existing) throws IOException;
    }

    static final Linker HARD_LINK = (target, existing) -> Files.createLink(target.toPath(), existing.toPath());

    public static Outcome restore(boolean enabled, boolean viewOnce, boolean userCleared, File target,
                                  List<String> canonicalRoots, File journalDir, Source source) {
        return restore(enabled, viewOnce, userCleared, target, canonicalRoots, journalDir, source, HARD_LINK);
    }

    static Outcome restore(boolean enabled, boolean viewOnce, boolean userCleared, File target,
                           List<String> canonicalRoots, File journalDir, Source source, Linker linker) {
        if (!enabled) return Outcome.DISABLED;
        if (viewOnce) return Outcome.VIEW_ONCE;
        if (userCleared) return Outcome.USER_CLEARED;
        if (target == null) return Outcome.OUTSIDE_ROOTS;
        File canonical;
        try {
            canonical = target.getCanonicalFile();
        } catch (IOException invalid) {
            return Outcome.OUTSIDE_ROOTS;
        }
        if (!WhatsAppMediaPaths.isSafeRestoreTarget(canonical.getPath(), canonicalRoots, false, false)) {
            return Outcome.OUTSIDE_ROOTS;
        }
        if (canonical.exists()) return Outcome.ALREADY_PRESENT;
        File parent = canonical.getParentFile();
        // mkdirs() returns false when another thread created the directory first: re-check.
        if (parent == null || (!parent.mkdirs() && !parent.isDirectory())) return Outcome.NO_PARENT;
        if (journalDir == null || (!journalDir.mkdirs() && !journalDir.isDirectory())) return Outcome.FAILED;

        String id = UUID.randomUUID().toString();
        File temp = new File(parent, TEMP_PREFIX + id + ".tmp");
        File journal = new File(journalDir, id + ".journal");
        boolean targetCreated = false;
        try {
            // 1. Journal first: a crash from here on is recoverable.
            writeJournal(journal, temp, canonical, -1, "", false);
            // 2. Complete, synced copy under a unique private name.
            String sha256;
            long size;
            try (InputStream in = source.open();
                 OutputStream out = Files.newOutputStream(temp.toPath(), StandardOpenOption.CREATE_NEW,
                         StandardOpenOption.WRITE)) {
                if (in == null) throw new IOException("no source");
                MessageDigest digest = BlobStore.newDigest();
                byte[] buffer = new byte[64 * 1024];
                long written = 0;
                for (int read; (read = in.read(buffer)) != -1; ) {
                    out.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    written += read;
                }
                out.flush();
                if (out instanceof FileOutputStream) ((FileOutputStream) out).getFD().sync();
                sha256 = BlobStore.hex(digest.digest());
                size = written;
            }
            sync(temp);
            writeJournal(journal, temp, canonical, size, sha256, false);
            // 3. Publish without ever replacing an existing name.
            try {
                linker.link(canonical, temp);
                targetCreated = true;
            } catch (FileAlreadyExistsException exists) {
                return Outcome.ALREADY_PRESENT;
            } catch (IOException | UnsupportedOperationException | SecurityException noLinks) {
                if (canonical.exists()) return Outcome.ALREADY_PRESENT;
                try (OutputStream out = Files.newOutputStream(canonical.toPath(), StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE)) {
                    targetCreated = true;
                    writeJournal(journal, temp, canonical, size, sha256, true);
                    try (InputStream in = new FileInputStream(temp)) {
                        byte[] buffer = new byte[64 * 1024];
                        for (int read; (read = in.read(buffer)) != -1; ) out.write(buffer, 0, read);
                    }
                    out.flush();
                    if (out instanceof FileOutputStream) ((FileOutputStream) out).getFD().sync();
                } catch (FileAlreadyExistsException exists) {
                    return Outcome.ALREADY_PRESENT; // WhatsApp won the race; its file is untouched
                }
            }
            if (canonical.length() != size) throw new IOException("restored size mismatch");
            targetCreated = false; // complete: keep it
            return Outcome.RESTORED;
        } catch (IOException | RuntimeException failure) {
            return Outcome.FAILED;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            if (targetCreated) {
                // Only a target this operation created exclusively is removed.
                //noinspection ResultOfMethodCallIgnored
                canonical.delete();
            }
            //noinspection ResultOfMethodCallIgnored
            journal.delete();
        }
    }

    /** Result of {@link #recover}. */
    public static final class Recovery {
        public int journals;
        public int tempsRemoved;
        public int partialTargetsRemoved;
        public int completeTargetsKept;
    }

    /**
     * Cleans what a process killed during {@link #restore} left behind. A temporary file is
     * always removed. A target is removed only if the journal says this module created it and it
     * does not have the recorded size and SHA-256 (i.e. it is partial); anything else at that
     * path is left alone.
     */
    public static Recovery recover(File journalDir) {
        Recovery recovery = new Recovery();
        File[] stale = journalDir == null ? null : journalDir.listFiles((dir, name) -> name.endsWith(".journal.new"));
        if (stale != null) for (File file : stale) //noinspection ResultOfMethodCallIgnored
            file.delete();
        File[] entries = journalDir == null ? null : journalDir.listFiles((dir, name) -> name.endsWith(".journal"));
        if (entries == null) return recovery;
        for (File entry : entries) {
            recovery.journals++;
            try {
                List<String> lines = Files.readAllLines(entry.toPath(), StandardCharsets.UTF_8);
                if (lines.size() >= 5) {
                    File temp = new File(lines.get(0));
                    File target = new File(lines.get(1));
                    long size = Long.parseLong(lines.get(2));
                    String sha = lines.get(3);
                    boolean created = "1".equals(lines.get(4));
                    if (temp.getName().startsWith(TEMP_PREFIX) && temp.delete()) recovery.tempsRemoved++;
                    if (created && target.isFile()) {
                        boolean complete = size >= 0 && target.length() == size && sha.equals(BlobStore.hash(target));
                        if (complete) recovery.completeTargetsKept++;
                        else if (target.delete()) recovery.partialTargetsRemoved++;
                    }
                }
            } catch (IOException | RuntimeException unreadable) {
                // A damaged journal entry never authorises deleting anything.
            }
            //noinspection ResultOfMethodCallIgnored
            entry.delete();
        }
        return recovery;
    }

    private static void writeJournal(File journal, File temp, File target, long size, String sha, boolean created)
            throws IOException {
        File next = new File(journal.getPath() + ".new");
        String text = temp.getPath() + "\n" + target.getPath() + "\n" + size + "\n" + sha + "\n" + (created ? "1" : "0") + "\n";
        try (FileOutputStream out = new FileOutputStream(next)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        Files.move(next.toPath(), journal.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private static void sync(File file) {
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(file.toPath(),
                StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException ignored) {
        }
    }
}
