package com.waenhancer.library.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Mutual exclusion for every library mutation, within and across processes.
 *
 * <p>A {@link FileLock} is held by the whole JVM process, so on its own it cannot serialize two
 * threads of the same process (and a second {@code lock()} from the same process throws
 * {@code OverlappingFileLockException}). It is therefore paired with an in-process
 * {@link ReentrantLock} shared by every instance that uses the same lock file. The provider,
 * the gallery and backup jobs all run in the module process today; the file lock keeps the
 * invariant if a future component (or a second module process, such as {@code :remote}) opens the
 * same library.</p>
 */
public final class LibraryLock {
    private static final ConcurrentHashMap<String, ReentrantLock> LOCAL = new ConcurrentHashMap<>();

    private final File lockFile;
    private final ReentrantLock local;

    public LibraryLock(File root) throws IOException {
        this.lockFile = new File(root, ".lock").getCanonicalFile();
        this.local = LOCAL.computeIfAbsent(lockFile.getPath(), ignored -> new ReentrantLock());
    }

    /** A held lock. Closing it releases both the file lock and the in-process lock. */
    public final class Held implements AutoCloseable {
        private final RandomAccessFile file;
        private final FileLock fileLock;

        Held(RandomAccessFile file, FileLock fileLock) {
            this.file = file;
            this.fileLock = fileLock;
        }

        @Override
        public void close() {
            try {
                if (fileLock != null) fileLock.release();
            } catch (IOException ignored) {
            }
            try {
                if (file != null) file.close();
            } catch (IOException ignored) {
            }
            local.unlock();
        }
    }

    /**
     * Acquires the lock, waiting at most {@code timeoutMs} for other threads of this process.
     * Re-entrant for the owning thread: nested acquisitions skip the file lock.
     */
    public Held acquire(long timeoutMs) throws IOException {
        try {
            if (!local.tryLock(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new LibraryException(LibraryException.Code.IO, "The media library is busy");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new LibraryException(LibraryException.Code.IO, "Interrupted while waiting for the library");
        }
        if (local.getHoldCount() > 1) return new Held(null, null);
        RandomAccessFile file = null;
        try {
            file = new RandomAccessFile(lockFile, "rw");
            FileChannel channel = file.getChannel();
            FileLock fileLock = channel.lock();
            return new Held(file, fileLock);
        } catch (IOException | RuntimeException failure) {
            if (file != null) try { file.close(); } catch (IOException ignored) { }
            local.unlock();
            if (failure instanceof IOException) throw (IOException) failure;
            throw new LibraryException(LibraryException.Code.IO, "Could not lock the media library", failure);
        }
    }

    /** True when the current thread holds the lock (used by assertions and tests). */
    public boolean isHeldByCurrentThread() {
        return local.isHeldByCurrentThread();
    }
}
