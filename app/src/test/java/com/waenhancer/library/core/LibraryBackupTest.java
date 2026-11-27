package com.waenhancer.library.core;

import static com.waenhancer.library.core.LibraryFixture.W4B;
import static com.waenhancer.library.core.LibraryFixture.WA;
import static com.waenhancer.library.core.LibraryFixture.ME;
import static com.waenhancer.library.core.LibraryFixture.jpeg;
import static com.waenhancer.library.core.LibraryFixture.mp4;
import static com.waenhancer.library.core.LibraryFixture.png;
import static com.waenhancer.library.core.LibraryFixture.request;
import static com.waenhancer.library.core.LibraryFixture.source;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

public class LibraryBackupTest {
    private static final char[] PASSWORD = "library-secret".toCharArray();
    private static final int FAST_KDF = 1000;
    private static final int SMALL_CHUNK = 4096;

    private LibraryFixture source;
    private LibraryFixture target;

    @Before
    public void setUp() throws Exception {
        source = new LibraryFixture();
        target = new LibraryFixture();
    }

    @After
    public void tearDown() throws Exception {
        source.close();
        target.close();
    }

    private byte[] populatedBackup(MediaLibrary library) throws Exception {
        library.importMedia(request("a@s.whatsapp.net", "P1").caption("first").build(), source(jpeg(1)));
        library.importMedia(request("a@s.whatsapp.net", "V1").fromMe(true).build(), source(mp4(2, 70_000)));
        library.importMedia(request("b@s.whatsapp.net", "P2").build(), source(jpeg(1))); // same bytes as P1
        library.importMedia(request("a@s.whatsapp.net", "P3").packageName(W4B).build(), source(png(3)));
        LibraryItem trashed = library.importMedia(request("b@s.whatsapp.net", "P4").build(), source(png(4))).item;
        library.trash(trashed.uuid);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LibraryBackup.ExportReport report = new LibraryBackup(library).export(out, PASSWORD, FAST_KDF, SMALL_CHUNK);
        assertEquals(5, report.items);
        assertEquals(4, report.blobs);
        return out.toByteArray();
    }

    @Test
    public void roundTripRestoresPhotosVideosDuplicatesTrashAndAccounts() throws Exception {
        MediaLibrary from = source.open();
        byte[] backup = populatedBackup(from);
        MediaLibrary to = target.open();
        LibraryBackup.RestoreReport report = new LibraryBackup(to).restore(new ByteArrayInputStream(backup), PASSWORD, FAST_KDF);

        assertEquals(5, report.itemsRestored);
        assertEquals(4, report.blobsCreated);
        assertEquals(4, to.listAll(false).size());
        assertEquals(1, to.listAll(true).size());
        assertEquals(4, to.blobs().listBlobs().size());
        LibraryItem video = to.lookup(WA, ME, "a@s.whatsapp.net", null, "V1", true);
        assertNotNull(video);
        assertEquals(ImportPolicy.BASIS_OWN_MEDIA, video.basis);
        assertArrayEquals(Files.readAllBytes(from.fileOf(from.lookup(WA, ME, "a@s.whatsapp.net", null, "V1", true)).toPath()),
                Files.readAllBytes(to.fileOf(video).toPath()));
        assertEquals("first", to.lookup(WA, ME, "a@s.whatsapp.net", null, "P1", false).caption);
        assertNotNull(to.lookup(W4B, ME, "a@s.whatsapp.net", null, "P3", false));
        assertEquals(0, to.listChat(WA, ME, Collections.singletonList("a@s.whatsapp.net"), false).stream()
                .filter(i -> i.messageKey.equals("P3")).count());
        assertEquals(0, to.reconcile(true).markedCorrupt);
    }

    @Test
    public void restoringTwiceMergesWithoutDuplicatingItemsOrBytes() throws Exception {
        byte[] backup = populatedBackup(source.open());
        MediaLibrary to = target.open();
        new LibraryBackup(to).restore(new ByteArrayInputStream(backup), PASSWORD, FAST_KDF);
        LibraryBackup.RestoreReport second = new LibraryBackup(to).restore(new ByteArrayInputStream(backup), PASSWORD, FAST_KDF);
        assertEquals(0, second.itemsRestored);
        assertEquals(5, second.itemsAlreadyPresent);
        assertEquals(5, to.listAll(false).size() + to.listAll(true).size());
        assertEquals(4, to.blobs().listBlobs().size());
    }

    @Test
    public void restoreKeepsLocalItemsAndOnlyAddsMissingOnes() throws Exception {
        byte[] backup = populatedBackup(source.open());
        MediaLibrary to = target.open();
        LibraryItem local = to.importMedia(request("a@s.whatsapp.net", "P1").build(), source(jpeg(99))).item;
        LibraryBackup.RestoreReport report = new LibraryBackup(to).restore(new ByteArrayInputStream(backup), PASSWORD, FAST_KDF);
        assertEquals(4, report.itemsRestored);
        assertEquals(1, report.itemsAlreadyPresent);
        assertEquals("the local copy of P1 is not overwritten", local.sha256,
                to.lookup(WA, ME, "a@s.whatsapp.net", null, "P1", false).sha256);
    }

    @Test
    public void wrongPasswordTamperingAndTruncationChangeNothing() throws Exception {
        byte[] backup = populatedBackup(source.open());
        MediaLibrary to = target.open();
        to.importMedia(request("z@s.whatsapp.net", "LOCAL").build(), source(jpeg(50)));

        byte[] tampered = backup.clone();
        tampered[tampered.length / 2] ^= 0x40;
        byte[] header = backup.clone();
        header[10] ^= 0x01;
        byte[][] attempts = {
                backup, // with the wrong password below
                tampered,
                header,
                Arrays.copyOf(backup, backup.length - 1),
                Arrays.copyOf(backup, StreamingAead.HEADER_BYTES + SMALL_CHUNK + 16), // exactly one full chunk
                Arrays.copyOf(backup, 20),
        };
        for (int i = 0; i < attempts.length; i++) {
            char[] password = i == 0 ? "wrong".toCharArray() : PASSWORD;
            try {
                new LibraryBackup(to).restore(new ByteArrayInputStream(attempts[i]), password, FAST_KDF);
                fail("attempt " + i + " must be rejected");
            } catch (LibraryException expected) {
                assertEquals("attempt " + i, LibraryException.Code.BACKUP_INVALID, expected.code);
            }
            assertEquals(1, to.listAll(false).size());
            assertEquals(1, to.blobs().listBlobs().size());
            assertEquals(0, new File(target.libraryDir(), "staging").list().length);
        }
    }

    @Test
    public void restoreThatWouldExceedTheQuotaChangesNothing() throws Exception {
        MediaLibrary from = source.open();
        from.importMedia(request("a@s.whatsapp.net", "BIG").build(),
                LibraryFixture.largeMp4(3, MediaLibrary.MIN_QUOTA_BYTES - 1_000_000));
        from.importMedia(request("a@s.whatsapp.net", "SMALL").build(), source(mp4(4, 2_000_000)));
        File bigBackup = new File(source.root, "big.waexlib");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(bigBackup)) {
            new LibraryBackup(from).export(out, PASSWORD, FAST_KDF, StreamingAead.DEFAULT_CHUNK);
        }

        MediaLibrary to = target.open();
        to.setQuotaBytes(MediaLibrary.MIN_QUOTA_BYTES);
        to.importMedia(request("z@s.whatsapp.net", "LOCAL").build(), source(jpeg(50)));
        try {
            try (java.io.FileInputStream in = new java.io.FileInputStream(bigBackup)) {
                new LibraryBackup(to).restore(in, PASSWORD, FAST_KDF);
            }
            fail("quota must be enforced");
        } catch (LibraryException expected) {
            assertEquals(LibraryException.Code.QUOTA_EXCEEDED, expected.code);
        }
        assertEquals(1, to.listAll(false).size());
        assertEquals(1, to.blobs().listBlobs().size());
        assertEquals(0, new File(target.libraryDir(), "staging").list().length);
    }

    @Test
    public void unavailableItemsAreReportedAndLeftOutOfTheExport() throws Exception {
        MediaLibrary from = source.open();
        LibraryItem lost = from.importMedia(request("a@s.whatsapp.net", "LOST").build(), source(jpeg(1))).item;
        from.importMedia(request("a@s.whatsapp.net", "OK").build(), source(jpeg(2)));
        assertTrue(from.fileOf(lost).delete());
        from.reconcile(false);
        LibraryBackup.ExportReport report = new LibraryBackup(from).export(new ByteArrayOutputStream(), PASSWORD,
                FAST_KDF, SMALL_CHUNK);
        assertEquals(1, report.items);
        assertEquals(1, report.skippedUnavailable);
    }

    @Test
    public void largeLibraryExportsAndRestoresUnderA48MegabyteHeap() throws Exception {
        long payload = 160L * 1024L * 1024L;
        Process child = ChildProcess.start(ChildProcess.BoundedBackup.class,
                Collections.singletonList("-Xmx48m"), source.root.getPath(), String.valueOf(payload));
        String output = ChildProcess.finish(child, 300);
        assertTrue(output, output.contains("restored " + (payload + mp4(0, 0).length)));
    }
}
