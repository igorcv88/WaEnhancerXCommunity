package com.waenhancer.library.core;

import static com.waenhancer.library.core.LibraryFixture.ME;
import static com.waenhancer.library.core.LibraryFixture.WA;
import static com.waenhancer.library.core.LibraryFixture.jpeg;
import static com.waenhancer.library.core.LibraryFixture.mp4;
import static com.waenhancer.library.core.LibraryFixture.request;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Does a kept item stay openable over time and as the library grows?
 *
 * <p>The reported symptom is media that stops opening "after some period" (one month? two?) or
 * "after the ~512 MB limit". These tests drive the library through both suspected triggers:
 * simulated time far beyond any plausible expiry with restarts and maintenance in between, and a
 * library well past 512 MiB. WhatsApp's own source file is deleted right after each import, so
 * the copy must stand on its own. "Openable" means: found by the same lookup the chat uses, marked
 * available, and its file's bytes are exactly the bytes that were kept.</p>
 */
public class MediaLibraryPersistenceTest {
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
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

    /** Writes the bytes to a "WhatsApp" file, imports from it, then deletes it. */
    private LibraryItem keepAndLoseSource(MediaLibrary library, String key, byte[] bytes) throws Exception {
        File whatsAppFile = new File(fixture.root, "whatsapp-" + key);
        Files.write(whatsAppFile.toPath(), bytes);
        LibraryItem item = library.importMedia(request(CHAT, key).build(),
                () -> new FileInputStream(whatsAppFile)).item;
        assertTrue(whatsAppFile.delete());
        return item;
    }

    private static void assertOpenable(MediaLibrary library, String key, byte[] expected) throws Exception {
        assertOpenable(library, key, expected, "");
    }

    private static void assertOpenable(MediaLibrary library, String key, byte[] expected, String when) throws Exception {
        LibraryItem item = library.lookup(WA, ME, CHAT, null, key, false);
        assertNotNull(key + " must still be found " + when, item);
        assertTrue(key + " must still be available " + when, item.isAvailable());
        assertArrayEquals(key + " must still have its exact bytes " + when, expected,
                Files.readAllBytes(library.fileOf(item).toPath()));
    }

    /** One process start as the device does it: open, reconcile, purge expired trash. */
    private MediaLibrary restartWithMaintenance() throws Exception {
        MediaLibrary library = fixture.open();
        MediaLibrary.ReconcileReport report = library.reconcile(true);
        assertEquals("maintenance must find nothing to repair", 0,
                report.markedMissing + report.markedCorrupt + report.orphanBlobsQuarantined + report.unreferencedBlobsQuarantined);
        library.purgeExpiredTrash();
        return library;
    }

    @Test
    public void keptPhotosAndVideosStayOpenableForTwoYearsAcrossRestartsWithoutTheWhatsAppFile() throws Exception {
        MediaLibrary library = fixture.open();
        Map<String, byte[]> kept = new LinkedHashMap<>();
        kept.put("PHOTO", jpeg(1));
        kept.put("VIDEO", mp4(2, 250_000));
        kept.put("DUP_A", jpeg(3));
        kept.put("DUP_B", jpeg(3)); // same bytes, other message
        for (Map.Entry<String, byte[]> entry : kept.entrySet()) keepAndLoseSource(library, entry.getKey(), entry.getValue());

        // Elapsed time since import, in days: past 1 and 2 months, half a year, 1 and 2 years.
        long[] checkpoints = { 1, 7, 31, 32, 61, 62, 90, 180, 365, 730 };
        long start = fixture.clock.get();
        for (long days : checkpoints) {
            fixture.clock.set(start + days * DAY_MS);
            MediaLibrary afterRestart = restartWithMaintenance();
            for (Map.Entry<String, byte[]> entry : kept.entrySet()) {
                assertOpenable(afterRestart, entry.getKey(), entry.getValue(), "after " + days + " days");
            }
            assertEquals("day " + days, 4, afterRestart.listChat(WA, ME, java.util.Collections.singletonList(CHAT), false).size());
        }
    }

    @Test
    public void libraryLargerThan512MegabytesKeepsEveryItemOpenable() throws Exception {
        MediaLibrary library = fixture.open();
        long sixtyMb = 60L * 1024L * 1024L;
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 10; i++) { // ~600 MiB, past the 512 MiB Deleted-for-Me vault limit
            String key = "BIG" + i;
            library.importMedia(request(CHAT, key).build(), LibraryFixture.largeMp4(1000 + i, sixtyMb));
            keys.add(key);
        }
        assertTrue("library is past 512 MiB", library.usedBytes() > 512L * 1024L * 1024L);
        assertTrue("the default limit is far above 512 MiB", MediaLibrary.DEFAULT_QUOTA_BYTES >= 16L * MediaLibrary.GIB);

        fixture.clock.addAndGet(90 * DAY_MS);
        MediaLibrary afterRestart = restartWithMaintenance(); // re-hashes all ~600 MiB
        for (String key : keys) {
            LibraryItem item = afterRestart.lookup(WA, ME, CHAT, null, key, false);
            assertNotNull(key, item);
            assertTrue(key, item.isAvailable());
            assertTrue(key + " (the first one too)", afterRestart.blobs().verify(item.sha256, item.sizeBytes));
        }
    }

    @Test
    public void reachingTheLimitRefusesNewMediaButNeverEvictsOldMedia() throws Exception {
        MediaLibrary library = fixture.open();
        library.setQuotaBytes(MediaLibrary.MIN_QUOTA_BYTES);
        byte[] oldest = jpeg(10);
        keepAndLoseSource(library, "OLDEST", oldest);
        library.importMedia(request(CHAT, "FILL").build(), LibraryFixture.largeMp4(5, MediaLibrary.MIN_QUOTA_BYTES - 200_000));
        try {
            library.importMedia(request(CHAT, "OVER").build(), LibraryFixture.source(mp4(6, 400_000)));
            fail("over the limit must be refused");
        } catch (LibraryException expected) {
            assertEquals(LibraryException.Code.QUOTA_EXCEEDED, expected.code);
        }
        fixture.clock.addAndGet(400 * DAY_MS);
        MediaLibrary afterRestart = restartWithMaintenance();
        assertOpenable(afterRestart, "OLDEST", oldest);
        assertNotNull(afterRestart.lookup(WA, ME, CHAT, null, "FILL", false));
    }

    @Test
    public void lowDeviceStorageRefusesNewMediaButNeverEvictsOldMedia() throws Exception {
        MediaLibrary library = fixture.open();
        byte[] photo = jpeg(20);
        keepAndLoseSource(library, "KEPT", photo);
        fixture.usableBytes.set(64L * 1024L * 1024L); // at the reserve: device is "full"
        try {
            library.importMedia(request(CHAT, "NEW").build(), LibraryFixture.source(mp4(7, 10_000)));
            fail("a full device must refuse new media");
        } catch (LibraryException expected) {
            assertEquals(LibraryException.Code.INSUFFICIENT_STORAGE, expected.code);
        }
        fixture.clock.addAndGet(60 * DAY_MS);
        assertOpenable(restartWithMaintenance(), "KEPT", photo);
    }

    @Test
    public void onlyItemsTheUserTrashedEverExpire() throws Exception {
        MediaLibrary library = fixture.open();
        byte[] keep = jpeg(30);
        keepAndLoseSource(library, "KEEP", keep);
        LibraryItem trashed = keepAndLoseSource(library, "TRASHED", jpeg(31));
        library.trash(trashed.uuid);

        fixture.clock.addAndGet(29 * DAY_MS);
        restartWithMaintenance();
        assertNotNull("still restorable inside the 30-day window", fixture.open().get(trashed.uuid));

        fixture.clock.addAndGet(2 * DAY_MS);
        MediaLibrary later = restartWithMaintenance();
        assertEquals(null, later.get(trashed.uuid));
        assertOpenable(later, "KEEP", keep);
    }

    @Test
    public void backupRestoredYearsLaterStillOpensEveryItem() throws Exception {
        MediaLibrary library = fixture.open();
        byte[] photo = jpeg(40);
        byte[] video = mp4(41, 120_000);
        keepAndLoseSource(library, "P", photo);
        keepAndLoseSource(library, "V", video);
        java.io.ByteArrayOutputStream backup = new java.io.ByteArrayOutputStream();
        new LibraryBackup(library).export(backup, "a-long-password".toCharArray(), 1000, 4096);

        try (LibraryFixture newPhone = new LibraryFixture()) {
            newPhone.clock.set(fixture.clock.get() + 3 * 365 * DAY_MS);
            MediaLibrary restored = newPhone.open();
            new LibraryBackup(restored).restore(new java.io.ByteArrayInputStream(backup.toByteArray()),
                    "a-long-password".toCharArray(), 1000);
            newPhone.clock.addAndGet(365 * DAY_MS);
            MediaLibrary afterRestart = newPhone.open();
            afterRestart.reconcile(true);
            afterRestart.purgeExpiredTrash();
            assertOpenable(afterRestart, "P", photo);
            assertOpenable(afterRestart, "V", video);
        }
    }

    @Test
    public void copyOpensInAFreshOfflineProcessAfterTheOriginalBecameUnavailable() throws Exception {
        MediaLibrary library = fixture.open();
        java.util.List<String> args = new java.util.ArrayList<>();
        args.add(fixture.root.getPath());
        String[] keys = { "P1", "V1", "VO1" };
        byte[][] bytes = { jpeg(50), mp4(51, 300_000), jpeg(52) };
        for (int i = 0; i < keys.length; i++) {
            File original = new File(fixture.root, "wa-" + keys[i]);
            Files.write(original.toPath(), bytes[i]);
            ImportRequest req = LibraryFixture.request("c@s.whatsapp.net", keys[i])
                    .viewOnce(keys[i].startsWith("VO")).keepReceivedViewOnce(true).build();
            LibraryItem item = library.importMedia(req, () -> new FileInputStream(original)).item;
            assertTrue("original made unavailable", original.delete());
            args.add(keys[i] + "=" + item.sha256);
        }
        Process child = ChildProcess.start(ChildProcess.OfflineReader.class, java.util.Collections.emptyList(),
                args.toArray(new String[0]));
        String output = ChildProcess.finish(child, 120);
        assertTrue(output, output.contains("offline ok 3"));
    }
}
