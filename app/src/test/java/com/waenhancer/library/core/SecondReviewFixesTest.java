package com.waenhancer.library.core;

import static com.waenhancer.library.core.LibraryFixture.ME;
import static com.waenhancer.library.core.LibraryFixture.WA;
import static com.waenhancer.library.core.LibraryFixture.jpeg;
import static com.waenhancer.library.core.LibraryFixture.mp4;
import static com.waenhancer.library.core.LibraryFixture.request;
import static com.waenhancer.library.core.LibraryFixture.source;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Regression tests for the second code review: restore never replaces a file WhatsApp created
 * meanwhile, concurrent restores do not collide, interrupted restores are recovered, the gallery
 * export publishes only a closed and confirmed entry, and the v3 migration does not guess origins.
 */
public class SecondReviewFixesTest {
    private LibraryFixture fixture;
    private File waRoot;
    private List<String> roots;
    private File journal;

    @Before
    public void setUp() throws Exception {
        fixture = new LibraryFixture();
        waRoot = new File(fixture.root, "Android/media/com.whatsapp/WhatsApp");
        assertTrue(new File(waRoot, "Media/WhatsApp Images").mkdirs());
        roots = Collections.singletonList(waRoot.getCanonicalPath());
        journal = new File(fixture.root, "wa-private/files/waex/restore-journal");
    }

    @After
    public void tearDown() throws Exception {
        fixture.close();
    }

    private File target(String name) {
        return new File(waRoot, "Media/WhatsApp Images/" + name);
    }

    private static WhatsAppRestore.Source bytes(byte[] data) {
        return () -> new ByteArrayInputStream(data);
    }

    private File images() {
        return new File(waRoot, "Media/WhatsApp Images");
    }

    // ---------------------------------------------------------------- high: no overwrite race

    @Test
    public void fileCreatedByWhatsAppJustBeforeTheFinalStepIsNeverReplaced_hardLinkPath() throws Exception {
        File target = target("IMG-RACE.jpg");
        byte[] whatsApp = "whatsapp re-downloaded this".getBytes(StandardCharsets.UTF_8);
        WhatsAppRestore.Linker racingLinker = (t, existing) -> {
            Files.write(t.toPath(), whatsApp); // WhatsApp wins the race between check and publish
            WhatsAppRestore.HARD_LINK.link(t, existing);
        };
        assertEquals(WhatsAppRestore.Outcome.ALREADY_PRESENT, WhatsAppRestore.restore(true, false, false, target,
                roots, journal, bytes(jpeg(1)), racingLinker));
        assertArrayEquals(whatsApp, Files.readAllBytes(target.toPath()));
        assertEquals("no temporary file left", 1, images().list().length);
    }

    @Test
    public void fileCreatedByWhatsAppJustBeforeTheFinalStepIsNeverReplaced_noHardLinksPath() throws Exception {
        File target = target("IMG-RACE2.jpg");
        byte[] whatsApp = "whatsapp file".getBytes(StandardCharsets.UTF_8);
        WhatsAppRestore.Linker noLinksButRace = (t, existing) -> {
            Files.write(t.toPath(), whatsApp);
            throw new UnsupportedOperationException("FUSE: no hard links");
        };
        assertEquals(WhatsAppRestore.Outcome.ALREADY_PRESENT, WhatsAppRestore.restore(true, false, false, target,
                roots, journal, bytes(jpeg(2)), noLinksButRace));
        assertArrayEquals(whatsApp, Files.readAllBytes(target.toPath()));
        assertEquals(1, images().list().length);
    }

    @Test
    public void withoutHardLinksTheExclusiveCreateCopyStillRestoresExactBytes() throws Exception {
        File target = target("VID-NOLINK.mp4");
        byte[] video = mp4(3, 300_000);
        WhatsAppRestore.Linker noLinks = (t, existing) -> {
            throw new IOException("Operation not permitted");
        };
        assertEquals(WhatsAppRestore.Outcome.RESTORED, WhatsAppRestore.restore(true, false, false, target, roots,
                journal, bytes(video), noLinks));
        assertArrayEquals(video, Files.readAllBytes(target.toPath()));
        assertEquals(1, images().list().length);
        assertEquals("journal entry removed after success", 0, journal.list().length);
    }

    // ---------------------------------------------------------------- high: unique temporary names

    @Test
    public void concurrentRestoresUseDistinctTemporaryFilesAndOneWinnerPerTarget() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<WhatsAppRestore.Outcome>> sameTarget = new ArrayList<>();
        List<Future<WhatsAppRestore.Outcome>> ownTargets = new ArrayList<>();
        File shared = target("SHARED.jpg");
        for (int i = 0; i < threads; i++) {
            int n = i;
            sameTarget.add(pool.submit(() -> {
                start.await();
                return WhatsAppRestore.restore(true, false, false, shared, roots, journal, bytes(jpeg(100 + n)));
            }));
            ownTargets.add(pool.submit(() -> {
                start.await();
                return WhatsAppRestore.restore(true, false, false, target("OWN-" + n + ".mp4"), roots, journal,
                        bytes(mp4(200 + n, 100_000)));
            }));
        }
        start.countDown();
        int restored = 0;
        for (Future<WhatsAppRestore.Outcome> f : sameTarget) {
            WhatsAppRestore.Outcome outcome = f.get(30, TimeUnit.SECONDS);
            if (outcome == WhatsAppRestore.Outcome.RESTORED) restored++;
            else assertEquals(WhatsAppRestore.Outcome.ALREADY_PRESENT, outcome);
        }
        for (Future<WhatsAppRestore.Outcome> f : ownTargets) {
            assertEquals(WhatsAppRestore.Outcome.RESTORED, f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertEquals("exactly one restore wins a shared name", 1, restored);
        boolean matchesOne = false;
        byte[] winner = Files.readAllBytes(shared.toPath());
        for (int i = 0; i < threads; i++) matchesOne |= Arrays.equals(winner, jpeg(100 + i));
        assertTrue("the shared file is one complete copy, never a mix", matchesOne);
        for (int i = 0; i < threads; i++) {
            assertArrayEquals(mp4(200 + i, 100_000), Files.readAllBytes(target("OWN-" + i + ".mp4").toPath()));
        }
        assertEquals("only final files remain", threads + 1, images().list().length);
        assertEquals(0, journal.list().length);
    }

    // ---------------------------------------------------------------- medium: abrupt interruption

    private void journalEntry(String id, File temp, File target, long size, String sha, boolean created)
            throws IOException {
        assertTrue(journal.isDirectory() || journal.mkdirs());
        Files.write(new File(journal, id + ".journal").toPath(), (temp.getPath() + "\n" + target.getPath() + "\n"
                + size + "\n" + sha + "\n" + (created ? "1" : "0") + "\n").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void recoveryAfterAKilledProcessRemovesTempsAndPartialTargetsOnly() throws Exception {
        byte[] full = jpeg(7);
        String sha;
        File complete = target("COMPLETE.jpg");
        Files.write(complete.toPath(), full);
        sha = BlobStore.hash(complete);

        // (a) killed while copying to the temp file
        File temp1 = new File(images(), WhatsAppRestore.TEMP_PREFIX + "a.tmp");
        Files.write(temp1.toPath(), Arrays.copyOf(full, 10));
        journalEntry("a", temp1, target("NEVER.jpg"), -1, "", false);
        // (b) killed during the no-hard-link copy into a target it created
        File temp2 = new File(images(), WhatsAppRestore.TEMP_PREFIX + "b.tmp");
        Files.write(temp2.toPath(), full);
        File partial = target("PARTIAL.jpg");
        Files.write(partial.toPath(), Arrays.copyOf(full, full.length / 2));
        journalEntry("b", temp2, partial, full.length, sha, true);
        // (c) killed after the target was complete, before cleanup
        journalEntry("c", new File(images(), WhatsAppRestore.TEMP_PREFIX + "c.tmp"), complete, full.length, sha, true);
        // (d) a file at the target this module did not create is never touched
        File foreign = target("FOREIGN.jpg");
        Files.write(foreign.toPath(), new byte[] { 9 });
        journalEntry("d", new File(images(), WhatsAppRestore.TEMP_PREFIX + "d.tmp"), foreign, 999, sha, false);
        // (e) a damaged entry authorises nothing
        File other = target("OTHER.jpg");
        Files.write(other.toPath(), new byte[] { 1 });
        Files.write(new File(journal, "e.journal").toPath(), "garbage".getBytes(StandardCharsets.UTF_8));

        WhatsAppRestore.Recovery recovery = WhatsAppRestore.recover(journal);
        assertEquals(5, recovery.journals);
        assertEquals(2, recovery.tempsRemoved);
        assertEquals(1, recovery.partialTargetsRemoved);
        assertEquals(1, recovery.completeTargetsKept);
        assertFalse(temp1.exists());
        assertFalse(temp2.exists());
        assertFalse(partial.exists());
        assertArrayEquals(full, Files.readAllBytes(complete.toPath()));
        assertTrue(foreign.exists());
        assertTrue(other.exists());
        assertEquals(0, journal.list().length);
        // A second recovery is a no-op.
        assertEquals(0, WhatsAppRestore.recover(journal).journals);
    }

    @Test
    public void journalPointsAtTheRealTempWhileARestoreIsInFlight() throws Exception {
        File target = target("INFLIGHT.jpg");
        CountDownLatch copying = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        WhatsAppRestore.Source slow = () -> new java.io.InputStream() {
            private final java.io.InputStream data = new ByteArrayInputStream(jpeg(8));

            @Override
            public int read() throws IOException {
                copying.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
                return data.read();
            }
        };
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<WhatsAppRestore.Outcome> result = worker.submit(() ->
                WhatsAppRestore.restore(true, false, false, target, roots, journal, slow));
        assertTrue(copying.await(5, TimeUnit.SECONDS));
        File[] entries = journal.listFiles((d, n) -> n.endsWith(".journal"));
        assertEquals(1, entries.length);
        String tempPath = Files.readAllLines(entries[0].toPath()).get(0);
        assertTrue("the journal is written before the temp file is filled", new File(tempPath).exists());
        assertTrue(new File(tempPath).getName().startsWith(WhatsAppRestore.TEMP_PREFIX));
        release.countDown();
        assertEquals(WhatsAppRestore.Outcome.RESTORED, result.get(10, TimeUnit.SECONDS));
        worker.shutdown();
        assertFalse(new File(tempPath).exists());
    }

    // ---------------------------------------------------------------- medium: gallery export ordering

    private static final class FakeStore implements PendingPublish.Store<String> {
        final List<String> events = new ArrayList<>();
        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        int publishResult = 1;
        boolean failWrite;
        boolean failClose;

        @Override
        public String createPending() {
            events.add("create");
            return "entry";
        }

        @Override
        public OutputStream open(String entry) {
            events.add("open");
            return new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    if (failWrite) throw new IOException("disk full");
                    written.write(b);
                }

                @Override
                public void close() throws IOException {
                    events.add("close");
                    if (failClose) throw new IOException("commit failed on close");
                }
            };
        }

        @Override
        public int publish(String entry) {
            events.add("publish");
            return publishResult;
        }

        @Override
        public void delete(String entry) {
            events.add("delete");
        }
    }

    @Test
    public void exportPublishesOnlyAfterTheStreamIsClosed() throws Exception {
        FakeStore store = new FakeStore();
        byte[] photo = jpeg(9);
        assertEquals("entry", PendingPublish.save(store, new ByteArrayInputStream(photo)));
        assertEquals(Arrays.asList("create", "open", "close", "publish"), store.events);
        assertArrayEquals(photo, store.written.toByteArray());
    }

    @Test
    public void exportFailsAndCleansUpWhenPublishWriteOrCloseFails() throws Exception {
        for (String mode : new String[] { "publish0", "write", "close" }) {
            FakeStore store = new FakeStore();
            if (mode.equals("publish0")) store.publishResult = 0;
            if (mode.equals("write")) store.failWrite = true;
            if (mode.equals("close")) store.failClose = true;
            try {
                PendingPublish.save(store, new ByteArrayInputStream(jpeg(10)));
                fail(mode + " must fail");
            } catch (IOException expected) {
                // reported as a failure, never as success
            }
            assertEquals(mode, "delete", store.events.get(store.events.size() - 1));
            if (!mode.equals("publish0")) assertFalse(mode + " never publishes", store.events.contains("publish"));
        }
    }

    // ---------------------------------------------------------------- low: v3 does not guess origins

    @Test
    public void migrationMarksUncertainOriginsUnknownAndWhatsAppSettlesThem() throws Exception {
        JdbcSql sql = fixture.connection();
        new LibrarySchema(LibrarySchema.defaultSteps().subList(0, 1), 1).migrate(sql);
        String sha = "b".repeat(64);
        sql.insert("INSERT INTO accounts (package_name, own_jid, created_at) VALUES ('com.whatsapp', ?, 0)", ME);
        sql.insert("INSERT INTO blobs (sha256, size_bytes, mime_type, created_at, verified_at) VALUES (?, 10, 'image/jpeg', 0, 0)", sha);
        String[][] rows = { { "OWN", "1", "own_media" }, { "RCV", "0", "user_explicit" }, { "AVO", "0", "auto_view_once" } };
        for (String[] row : rows) {
            sql.insert("INSERT INTO items (uuid, account_id, chat_jid, message_key, from_me, media_kind, mime_type, sha256, "
                    + "imported_at, basis) VALUES (?, 1, 'c@s.whatsapp.net', ?, ?, 'image', 'image/jpeg', ?, 0, ?)",
                    row[0], row[0], Integer.parseInt(row[1]), sha, row[2]);
        }
        MediaLibrary library = fixture.open(sql);
        assertEquals(LibrarySchema.CURRENT_VERSION, sql.userVersion());
        LibraryItem own = library.get("OWN");
        assertFalse("own media from v1 cannot be classified by basis", own.viewOnceKnown);
        assertTrue(library.get("RCV").viewOnceKnown);
        assertFalse(library.get("RCV").viewOnce);
        assertTrue(library.get("AVO").viewOnceKnown);
        assertTrue(library.get("AVO").viewOnce);

        long generation = library.generation();
        // WhatsApp reports the message was the user's own view-once.
        assertTrue(library.confirmOrigin(WA, ME, "c@s.whatsapp.net", null, "OWN", true, true));
        assertTrue(library.get("OWN").viewOnce);
        assertTrue(library.get("OWN").viewOnceKnown);
        assertTrue(library.generation() > generation);
        assertFalse("a known flag is never overwritten",
                library.confirmOrigin(WA, ME, "c@s.whatsapp.net", null, "RCV", false, true));
        assertFalse(library.get("RCV").viewOnce);
        assertFalse("another account cannot settle it",
                library.confirmOrigin(WA, "other@s.whatsapp.net", "c@s.whatsapp.net", null, "OWN", true, false));
    }

    @Test
    public void newImportsAlwaysRecordAKnownOriginThatSurvivesBackup() throws Exception {
        MediaLibrary library = fixture.open();
        library.importMedia(request("c@s.whatsapp.net", "NEWOWN").fromMe(true).build(), source(jpeg(11)));
        library.importMedia(request("c@s.whatsapp.net", "NEWVO").fromMe(true).viewOnce(true).build(), source(jpeg(12)));
        assertTrue(library.lookup(WA, ME, "c@s.whatsapp.net", null, "NEWOWN", true).viewOnceKnown);
        assertTrue(library.lookup(WA, ME, "c@s.whatsapp.net", null, "NEWVO", true).viewOnce);
        assertFalse(library.confirmOrigin(WA, ME, "c@s.whatsapp.net", null, "NEWOWN", true, true));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new LibraryBackup(library).export(out, "password1".toCharArray(), 1000, 4096);
        try (LibraryFixture other = new LibraryFixture()) {
            MediaLibrary restored = other.open();
            new LibraryBackup(restored).restore(new ByteArrayInputStream(out.toByteArray()), "password1".toCharArray(), 1000);
            assertTrue(restored.lookup(WA, ME, "c@s.whatsapp.net", null, "NEWVO", true).viewOnce);
            assertTrue(restored.lookup(WA, ME, "c@s.whatsapp.net", null, "NEWOWN", true).viewOnceKnown);
        }
        assertNull(library.lookup(WA, ME, "c@s.whatsapp.net", null, "MISSING", true));
    }
}
