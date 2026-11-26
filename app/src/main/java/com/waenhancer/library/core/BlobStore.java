package com.waenhancer.library.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Content-addressed, private file storage for library media.
 *
 * <p>Every stored file is named by its SHA-256, so identical bytes attached to different
 * messages share one file and deduplication cannot depend on caller-supplied names. Writes go to
 * a staging file first; the digest and size are computed while streaming, the file is synced,
 * and only then is it moved into place with an atomic rename. A crash therefore leaves either a
 * complete, correctly named blob or a staging file that {@link #cleanStaging} removes; it never
 * leaves a partially written file under a valid name.</p>
 *
 * <p>An existing blob is never trusted merely because its name exists: {@link #commit} re-hashes
 * it, and replaces it with the freshly staged bytes when it is missing, truncated or corrupt.</p>
 */
public final class BlobStore {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final int BUFFER = 64 * 1024;

    /** Free space that is always left on the volume, whatever the quota says. */
    public static final long DEFAULT_FREE_SPACE_RESERVE = 512L * 1024L * 1024L;

    private final File root;
    private final File blobs;
    private final File staging;
    private final File quarantine;
    private final SpaceProbe space;
    private final long freeSpaceReserve;

    /** Reports usable bytes on the volume; replaceable in tests to simulate a full device. */
    @FunctionalInterface
    public interface SpaceProbe {
        long usableBytes(File directory);
    }

    public BlobStore(File root) throws IOException {
        this(root, File::getUsableSpace, DEFAULT_FREE_SPACE_RESERVE);
    }

    public BlobStore(File root, SpaceProbe space, long freeSpaceReserve) throws IOException {
        this.root = root.getCanonicalFile();
        this.blobs = new File(this.root, "blobs");
        this.staging = new File(this.root, "staging");
        this.quarantine = new File(this.root, "quarantine");
        this.space = space;
        this.freeSpaceReserve = Math.max(0, freeSpaceReserve);
        mkdirs(this.blobs);
        mkdirs(this.staging);
    }

    public File root() {
        return root;
    }

    /** A fully written, synced staging file whose digest and size are known. */
    public static final class Staged {
        public final File file;
        public final String sha256;
        public final long size;
        public final String sniffedMime;

        Staged(File file, String sha256, long size, String sniffedMime) {
            this.file = file;
            this.sha256 = sha256;
            this.size = size;
            this.sniffedMime = sniffedMime;
        }

        /** Removes the staging file; safe to call more than once. */
        public void discard() {
            if (file.exists()) //noinspection ResultOfMethodCallIgnored
                file.delete();
        }
    }

    /**
     * Streams {@code input} into a new staging file.
     *
     * @param maxBytes per-item limit; the copy stops as soon as it is exceeded
     */
    public Staged stage(InputStream input, long maxBytes) throws IOException {
        if (input == null) throw new LibraryException(LibraryException.Code.INVALID_REQUEST, "No media stream");
        File target = new File(staging, UUID.randomUUID() + ".part");
        ensureChild(staging, target);
        MessageDigest digest = newDigest();
        byte[] prefix = new byte[MediaSniffer.PREFIX_BYTES];
        int prefixLength = 0;
        long written = 0;
        boolean success = false;
        try (FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[BUFFER];
            long nextSpaceCheck = 0;
            for (int read; (read = input.read(buffer)) != -1; ) {
                if (read == 0) continue;
                if (written + read > maxBytes) {
                    throw new LibraryException(LibraryException.Code.TOO_LARGE, "Media exceeds the per-item limit");
                }
                if (written >= nextSpaceCheck) {
                    // Checked every 8 MiB rather than per buffer: the probe is a statfs call.
                    requireFreeSpace(8L * 1024L * 1024L);
                    nextSpaceCheck = written + 8L * 1024L * 1024L;
                }
                if (prefixLength < prefix.length) {
                    int copy = Math.min(prefix.length - prefixLength, read);
                    System.arraycopy(buffer, 0, prefix, prefixLength, copy);
                    prefixLength += copy;
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
                written += read;
            }
            output.flush();
            output.getFD().sync();
            success = true;
        } finally {
            if (!success) //noinspection ResultOfMethodCallIgnored
                target.delete();
        }
        if (written == 0) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            throw new LibraryException(LibraryException.Code.UNSUPPORTED_MEDIA, "Media is empty");
        }
        return new Staged(target, hex(digest.digest()), written, MediaSniffer.sniff(prefix, prefixLength));
    }

    /** Outcome of {@link #commit}. */
    public enum CommitOutcome {
        /** The blob did not exist and was created from the staging file. */
        CREATED,
        /** A verified blob with the same digest already existed; the staging file was dropped. */
        REUSED_VERIFIED,
        /** A blob existed but failed verification and was replaced by the staging file. */
        REPAIRED
    }

    /**
     * Moves a staged file into its content address, or drops it when an identical, verified blob
     * already exists. The staging file never survives this call.
     */
    public CommitOutcome commit(Staged staged) throws IOException {
        File target = path(staged.sha256);
        try {
            if (target.isFile()) {
                if (verify(staged.sha256, staged.size)) {
                    staged.discard();
                    return CommitOutcome.REUSED_VERIFIED;
                }
                move(staged.file, target);
                return CommitOutcome.REPAIRED;
            }
            mkdirs(target.getParentFile());
            move(staged.file, target);
            File stale = new File(quarantine, staged.sha256);
            if (stale.isFile()) //noinspection ResultOfMethodCallIgnored
                stale.delete();
            return CommitOutcome.CREATED;
        } finally {
            staged.discard();
        }
    }

    /** True when the blob exists, has the expected size and its bytes hash to its name. */
    public boolean verify(String sha256, long expectedSize) throws IOException {
        File file = path(sha256);
        if (!file.isFile() || file.length() != expectedSize) return false;
        return sha256.equals(hash(file));
    }

    /** Cheap check used by reconciliation passes that do not re-hash every file. */
    public boolean existsWithSize(String sha256, long expectedSize) throws IOException {
        File file = path(sha256);
        return file.isFile() && file.length() == expectedSize;
    }

    /** Resolves a blob path; refuses anything that is not a lowercase SHA-256. */
    public File path(String sha256) throws IOException {
        if (sha256 == null || !SHA256.matcher(sha256).matches()) {
            throw new LibraryException(LibraryException.Code.INVALID_REQUEST, "Invalid blob id");
        }
        File target = new File(new File(blobs, sha256.substring(0, 2)), sha256);
        ensureChild(blobs, target);
        return target;
    }

    public InputStream open(String sha256) throws IOException {
        File file = path(sha256);
        if (!file.isFile()) throw new LibraryException(LibraryException.Code.NOT_FOUND, "Blob is missing");
        return new FileInputStream(file);
    }

    /** Deletes a blob; absent is the desired end state, not a failure. */
    public void delete(String sha256) throws IOException {
        File file = path(sha256);
        if (file.exists() && !file.delete()) throw new IOException("Could not delete library media");
    }

    /** Every correctly named blob currently on disk. */
    public List<String> listBlobs() {
        List<String> result = new ArrayList<>();
        File[] shards = blobs.listFiles();
        if (shards == null) return result;
        for (File shard : shards) {
            File[] files = shard.isDirectory() ? shard.listFiles() : null;
            if (files == null) continue;
            for (File file : files) {
                String name = file.getName();
                if (file.isFile() && SHA256.matcher(name).matches() && name.startsWith(shard.getName())) {
                    result.add(name);
                }
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- quarantine

    /**
     * Moves a blob whose catalog record is missing out of the content store instead of deleting
     * it: complete files are never destroyed by maintenance. Quarantined files are reported, can
     * be re-adopted automatically when the same bytes are kept again, and are deleted only on an
     * explicit user action.
     */
    public void quarantine(String sha256) throws IOException {
        File source = path(sha256);
        if (!source.isFile()) return;
        mkdirs(quarantine);
        File target = new File(quarantine, sha256);
        ensureChild(quarantine, target);
        if (target.exists()) {
            if (!source.delete()) throw new IOException("Could not quarantine library media");
            return;
        }
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
    }

    public List<String> listQuarantine() {
        List<String> result = new ArrayList<>();
        File[] files = quarantine.listFiles();
        if (files == null) return result;
        for (File file : files) if (file.isFile() && SHA256.matcher(file.getName()).matches()) result.add(file.getName());
        return result;
    }

    public File quarantined(String sha256) throws IOException {
        path(sha256); // validates the id
        File file = new File(quarantine, sha256);
        ensureChild(quarantine, file);
        return file;
    }

    /** Moves a verified quarantined file back into the content store; false if absent or damaged. */
    public boolean adoptFromQuarantine(String sha256, long expectedSize) throws IOException {
        File file = quarantined(sha256);
        if (!file.isFile() || file.length() != expectedSize || !sha256.equals(hash(file))) return false;
        File target = path(sha256);
        if (target.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            return true;
        }
        mkdirs(target.getParentFile());
        Files.move(file.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        return true;
    }

    public boolean deleteQuarantined(String sha256) throws IOException {
        File file = quarantined(sha256);
        return file.isFile() && file.delete();
    }

    /** Removes staging files left behind by an interrupted import or restore. */
    public int cleanStaging() {
        int removed = 0;
        File[] files = staging.listFiles();
        if (files == null) return 0;
        for (File file : files) {
            if (file.isFile() && file.delete()) removed++;
        }
        return removed;
    }

    /** Throws {@link LibraryException.Code#INSUFFICIENT_STORAGE} when {@code bytes} would cut into the reserve. */
    public void requireFreeSpace(long bytes) throws LibraryException {
        long usable = space.usableBytes(root);
        if (usable - Math.max(0, bytes) < freeSpaceReserve) {
            throw new LibraryException(LibraryException.Code.INSUFFICIENT_STORAGE,
                    "Not enough free storage on the device");
        }
    }

    public static String hash(File file) throws IOException {
        MessageDigest digest = newDigest();
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[BUFFER];
            for (int read; (read = input.read(buffer)) != -1; ) digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    static MessageDigest newDigest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value));
        return result.toString();
    }

    private static void move(File from, File to) throws IOException {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            // Staging and blobs share one directory tree, so this only happens on exotic
            // filesystems; a plain replace still never exposes a partial file under the name
            // because the staging file was completely written and synced first.
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        syncDirectory(to.getParentFile());
    }

    /** Persists the rename itself. Best effort: not every platform can open a directory. */
    private static void syncDirectory(File directory) {
        try (FileChannel channel = FileChannel.open(directory.toPath(), StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | RuntimeException ignored) {
            // The file contents were synced before the rename; only the rename's durability
            // depends on this, and reconciliation repairs a rename lost to a power cut.
        }
    }

    private static void mkdirs(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Could not create library storage");
        }
    }

    private static void ensureChild(File parent, File child) throws IOException {
        String prefix = parent.getCanonicalPath() + File.separator;
        if (!child.getCanonicalPath().startsWith(prefix)) {
            throw new LibraryException(LibraryException.Code.INVALID_REQUEST, "Invalid library path");
        }
    }
}
