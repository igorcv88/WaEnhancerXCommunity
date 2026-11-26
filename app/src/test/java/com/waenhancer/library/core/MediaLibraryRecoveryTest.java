package com.waenhancer.library.core;

import static com.waenhancer.library.core.LibraryFixture.jpeg;
import static com.waenhancer.library.core.LibraryFixture.mp4;
import static com.waenhancer.library.core.LibraryFixture.request;
import static com.waenhancer.library.core.LibraryFixture.source;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/** What an interruption at each step of an import leaves behind, and how it is repaired. */
public class MediaLibraryRecoveryTest {
    private static final String CHAT = "5511888880000@s.whatsapp.net";
    private LibraryFixture fixture;

    @Before
    public void setUp() throws Exception {
        fixture = new LibraryFixture();
    }

    @After
    public void tearDown() throws Exception {
        fixture.close();
    }

    @Test
    public void streamFailureMidwayLeavesNoBlobNoRowAndNoStagingFile() throws Exception {
        MediaLibrary library = fixture.open();
        byte[] video = mp4(1, 300_000);
        MediaLibrary.StreamSource breaking = () -> new InputStream() {
            private final InputStream delegate = new ByteArrayInputStream(video);
            private int served;

            @Override
            public int read() throws IOException {
                if (++served > 150_000) throw new IOException("connection to WhatsApp lost");
                return delegate.read();
            }
        };
        try {
            library.importMedia(request(CHAT, "BROKEN").build(), breaking);
            fail("a broken stream must fail the import");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("connection"));
        }
        assertEquals(0, library.listAll(false).size());
        assertEquals(0, library.blobs().listBlobs().size());
        assertEquals(0, new File(fixture.libraryDir(), "staging").list().length);
        // And the same message can then be imported normally.
        assertEquals(MediaLibrary.ImportStatus.CREATED,
                library.importMedia(request(CHAT, "BROKEN").build(), source(video)).status);
    }

    @Test
    public void catalogFailureAfterTheBlobWasMovedRemovesTheUnreferencedBlob() throws Exception {
        JdbcSql real = fixture.connection();
        FailingSql failing = new FailingSql(real);
        MediaLibrary library = fixture.open(failing);
        failing.failOnInsertInto = "INSERT INTO items";
        try {
            library.importMedia(request(CHAT, "TX").build(), source(jpeg(3)));
            fail("catalog failure must surface");
        } catch (LibraryException expected) {
            assertEquals(LibraryException.Code.IO, expected.code);
        }
        assertEquals(0, LibraryFixture.count(real, "items"));
        assertEquals("the blob row was rolled back with the item", 0, LibraryFixture.count(real, "blobs"));
        assertEquals("bytes without a row must not leak", 0, library.blobs().listBlobs().size());
    }

    @Test
    public void catalogFailureNeverDeletesBytesOwnedByAnotherItem() throws Exception {
        JdbcSql real = fixture.connection();
        FailingSql failing = new FailingSql(real);
        MediaLibrary library = fixture.open(failing);
        byte[] photo = jpeg(4);
        LibraryItem owner = library.importMedia(request(CHAT, "OWNER").build(), source(photo)).item;
        failing.failOnInsertInto = "INSERT INTO items";
        try {
            library.importMedia(request(CHAT, "SECOND").build(), source(photo));
            fail();
        } catch (LibraryException expected) {
            assertEquals(LibraryException.Code.IO, expected.code);
        }
        assertTrue(library.blobs().verify(owner.sha256, owner.sizeBytes));
        assertArrayEquals(photo, Files.readAllBytes(library.fileOf(owner).toPath()));
    }

    @Test
    public void reconcileRemovesStagingFilesAndOrphanBlobsFromACrash() throws Exception {
        MediaLibrary library = fixture.open();
        LibraryItem kept = library.importMedia(request(CHAT, "KEEP").build(), source(jpeg(5))).item;

        // Simulate a process killed (a) while streaming and (b) after the rename but before the
        // catalog transaction committed.
        File stray = new File(new File(fixture.libraryDir(), "staging"), "dead.part");
        try (FileOutputStream out = new FileOutputStream(stray)) {
            out.write(new byte[1234]);
        }
        BlobStore.Staged orphan = library.blobs().stage(new ByteArrayInputStream(mp4(6, 10_000)), 1 << 20);
        library.blobs().commit(orphan);
        assertEquals(2, library.blobs().listBlobs().size());

        MediaLibrary afterRestart = fixture.open();
        MediaLibrary.ReconcileReport report = afterRestart.reconcile(false);
        assertEquals(1, report.stagingRemoved);
        assertEquals(1, report.orphanBlobsRemoved);
        assertEquals(1, report.verified);
        assertEquals(1, afterRestart.blobs().listBlobs().size());
        assertTrue(afterRestart.blobs().verify(kept.sha256, kept.sizeBytes));
        assertEquals(1, afterRestart.listAll(false).size());
    }

    @Test
    public void deepReconcileDetectsSameSizeCorruptionAndALaterRepairClearsIt() throws Exception {
        MediaLibrary library = fixture.open();
        byte[] photo = jpeg(9);
        LibraryItem item = library.importMedia(request(CHAT, "DEEP").build(), source(photo)).item;
        try (RandomAccessFile file = new RandomAccessFile(library.fileOf(item), "rw")) {
            file.seek(10);
            file.write(photo[10] ^ 0x01);
        }
        assertEquals("a shallow pass trusts an ok row with the right size", 0, library.reconcile(false).markedCorrupt);
        assertEquals(1, library.reconcile(true).markedCorrupt);
        assertFalse(library.get(item.uuid).isAvailable());

        MediaLibrary.ImportResult repaired = library.importMedia(request(CHAT, "DEEP").build(), source(photo));
        assertEquals(MediaLibrary.ImportStatus.REPAIRED, repaired.status);
        assertTrue(library.get(item.uuid).isAvailable());
        assertEquals(0, library.reconcile(true).markedCorrupt);
    }

    @Test
    public void truncatedBlobIsMarkedCorruptAndARestoredFileIsRecognisedAgain() throws Exception {
        MediaLibrary library = fixture.open();
        byte[] video = mp4(8, 40_000);
        LibraryItem item = library.importMedia(request(CHAT, "TRUNC").build(), source(video)).item;
        File file = library.fileOf(item);
        byte[] original = Files.readAllBytes(file.toPath());
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.setLength(100);
        }
        assertEquals(1, library.reconcile(false).markedCorrupt);
        Files.write(file.toPath(), original);
        assertEquals(1, library.reconcile(false).recovered);
        assertTrue(library.get(item.uuid).isAvailable());
    }

    @Test
    public void unreferencedBlobRowsAreCollected() throws Exception {
        JdbcSql sql = fixture.connection();
        MediaLibrary library = fixture.open(sql);
        LibraryItem item = library.importMedia(request(CHAT, "GC").build(), source(jpeg(12))).item;
        // A row left referencing nothing (e.g. by a future bug or manual edit) must not leak bytes.
        sql.update("DELETE FROM items WHERE id = ?", item.id);
        MediaLibrary.ReconcileReport report = library.reconcile(false);
        assertEquals(1, report.unreferencedBlobsRemoved);
        assertEquals(0, library.blobs().listBlobs().size());
        assertEquals(0, LibraryFixture.count(sql, "blobs"));
    }

    /** Delegating {@link Sql} that fails a chosen statement, to simulate a crash mid-transaction. */
    static final class FailingSql implements Sql {
        private final Sql delegate;
        volatile String failOnInsertInto;

        FailingSql(Sql delegate) {
            this.delegate = delegate;
        }

        @Override
        public void exec(String sql, Object... args) {
            delegate.exec(sql, args);
        }

        @Override
        public long insert(String sql, Object... args) {
            String marker = failOnInsertInto;
            if (marker != null && sql.startsWith(marker)) throw new IllegalStateException("simulated disk I/O error");
            return delegate.insert(sql, args);
        }

        @Override
        public int update(String sql, Object... args) {
            return delegate.update(sql, args);
        }

        @Override
        public List<Map<String, Object>> query(String sql, Object... args) {
            return delegate.query(sql, args);
        }

        @Override
        public <T> T inTransaction(Work<T> work) {
            return delegate.inTransaction(work);
        }

        @Override
        public int userVersion() {
            return delegate.userVersion();
        }

        @Override
        public void setUserVersion(int version) {
            delegate.setUserVersion(version);
        }
    }
}
