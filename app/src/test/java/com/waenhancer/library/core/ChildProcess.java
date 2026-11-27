package com.waenhancer.library.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs library code in a separate JVM (a real second OS process with its own SQLite connection,
 * its own {@link LibraryLock} instance and its own file lock), using the test classpath.
 */
final class ChildProcess {
    private ChildProcess() { }

    static Process start(Class<?> main, List<String> jvmArgs, String... args) throws IOException {
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        List<String> command = new ArrayList<>();
        command.add(java);
        command.addAll(jvmArgs);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(main.getName());
        command.addAll(Arrays.asList(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        return builder.start();
    }

    /** Waits for the process and returns its combined output; fails on a non-zero exit. */
    static String finish(Process process, long timeoutSeconds) throws Exception {
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("child process timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) throw new AssertionError("child process failed: " + output);
        return output;
    }

    /** Child entry point: imports {@code count} items, half sharing bytes with the parent. */
    public static final class Importer {
        public static void main(String[] args) throws Exception {
            File root = new File(args[0]);
            String prefix = args[1];
            int count = Integer.parseInt(args[2]);
            try (JdbcSql sql = new JdbcSql(new File(root, "media_library.db"))) {
                File dir = new File(root, "media_library");
                MediaLibrary library = new MediaLibrary(sql, new BlobStore(dir, d -> Long.MAX_VALUE / 4, 0),
                        new LibraryLock(dir), System::currentTimeMillis);
                for (int i = 0; i < count; i++) {
                    byte[] bytes = i % 2 == 0 ? LibraryFixture.mp4(5_000 + i, 20_000) // shared with parent
                            : LibraryFixture.mp4(prefix.hashCode() * 31 + i, 20_000); // unique to this process
                    library.importMedia(LibraryFixture.request("chat@s.whatsapp.net", prefix + i).build(),
                            LibraryFixture.source(bytes));
                }
            }
            System.out.println("imported " + count);
        }
    }

    /** Child entry point: export then restore a large library under a small heap. */
    public static final class BoundedBackup {
        public static void main(String[] args) throws Exception {
            File root = new File(args[0]);
            long payload = Long.parseLong(args[1]);
            File backup = new File(root, "library.waexlib");
            char[] password = "correct horse".toCharArray();
            try (JdbcSql sql = new JdbcSql(new File(root, "source.db"))) {
                File dir = new File(root, "source");
                MediaLibrary library = new MediaLibrary(sql, new BlobStore(dir, d -> Long.MAX_VALUE / 4, 0),
                        new LibraryLock(dir), System::currentTimeMillis);
                library.importMedia(LibraryFixture.request("c@s.whatsapp.net", "BIG").fromMe(true).build(),
                        LibraryFixture.largeMp4(77, payload));
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(backup)) {
                    new LibraryBackup(library).export(out, password, 1000, StreamingAead.DEFAULT_CHUNK);
                }
            }
            try (JdbcSql sql = new JdbcSql(new File(root, "target.db"))) {
                File dir = new File(root, "target");
                MediaLibrary library = new MediaLibrary(sql, new BlobStore(dir, d -> Long.MAX_VALUE / 4, 0),
                        new LibraryLock(dir), System::currentTimeMillis);
                LibraryBackup.RestoreReport report;
                try (java.io.FileInputStream in = new java.io.FileInputStream(backup)) {
                    report = new LibraryBackup(library).restore(in, password, 1000);
                }
                LibraryItem item = library.lookup("com.whatsapp", LibraryFixture.ME, "c@s.whatsapp.net", null, "BIG", true);
                if (report.itemsRestored != 1 || item == null || !library.blobs().verify(item.sha256, item.sizeBytes)) {
                    throw new AssertionError("restore incomplete");
                }
                System.out.println("restored " + item.sizeBytes + " maxHeap " + Runtime.getRuntime().maxMemory());
            }
        }
    }

    /**
     * Child entry point: a fresh process with every network path disabled opens an existing
     * library, runs the startup maintenance and verifies the bytes of every listed item.
     * args: root, then key=sha256 pairs.
     */
    public static final class OfflineReader {
        public static void main(String[] args) throws Exception {
            java.net.ProxySelector.setDefault(new java.net.ProxySelector() {
                @Override
                public java.util.List<java.net.Proxy> select(java.net.URI uri) {
                    throw new IllegalStateException("network used: " + uri);
                }

                @Override
                public void connectFailed(java.net.URI uri, java.net.SocketAddress sa, java.io.IOException ioe) {
                }
            });
            //noinspection deprecation
            java.net.Socket.setSocketImplFactory(() -> {
                throw new IllegalStateException("network socket opened");
            });
            File root = new File(args[0]);
            try (JdbcSql sql = new JdbcSql(new File(root, "media_library.db"))) {
                File dir = new File(root, "media_library");
                MediaLibrary library = new MediaLibrary(sql, new BlobStore(dir, d -> Long.MAX_VALUE / 4, 0),
                        new LibraryLock(dir), System::currentTimeMillis);
                MediaLibrary.ReconcileReport report = library.reconcile(true);
                if (report.markedMissing + report.markedCorrupt > 0) throw new AssertionError("damage after restart");
                for (int i = 1; i < args.length; i++) {
                    String[] pair = args[i].split("=");
                    LibraryItem item = library.lookup("com.whatsapp", LibraryFixture.ME, "c@s.whatsapp.net", null, pair[0], false);
                    if (item == null || !item.isAvailable() || !pair[1].equals(BlobStore.hash(library.fileOf(item)))) {
                        throw new AssertionError("not openable offline: " + pair[0]);
                    }
                }
            }
            System.out.println("offline ok " + (args.length - 1));
        }
    }
}
