package com.waenhancer.library.core;

import static com.waenhancer.library.core.LibraryFixture.ME;
import static com.waenhancer.library.core.LibraryFixture.W4B;
import static com.waenhancer.library.core.LibraryFixture.WA;
import static com.waenhancer.library.core.LibraryFixture.jpeg;
import static com.waenhancer.library.core.LibraryFixture.mp4;
import static com.waenhancer.library.core.LibraryFixture.png;
import static com.waenhancer.library.core.LibraryFixture.request;
import static com.waenhancer.library.core.LibraryFixture.source;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;


import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class MediaLibraryImportTest {
    private static final String ALICE = "5511888880000@s.whatsapp.net";
    private static final String ALICE_LID = "123456789012345@lid";
    private static final String BOB = "5511777770000@s.whatsapp.net";

    private LibraryFixture fixture;
    private MediaLibrary library;

    @Before
    public void setUp() throws Exception {
        fixture = new LibraryFixture();
        library = fixture.open();
    }

    @After
    public void tearDown() throws Exception {
        fixture.close();
    }

    @Test
    public void photoAndVideoAreStoredWithContentDerivedTypeAndExactBytes() throws Exception {
        byte[] photo = jpeg(1);
        byte[] video = mp4(2, 200_000);
        MediaLibrary.ImportResult p = library.importMedia(request(ALICE, "AAA1").build(), source(photo));
        MediaLibrary.ImportResult v = library.importMedia(request(ALICE, "AAA2").fromMe(true).build(), source(video));

        assertEquals(MediaLibrary.ImportStatus.CREATED, p.status);
        assertEquals("image", p.item.mediaKind);
        assertEquals("image/jpeg", p.item.mimeType);
        assertEquals(ImportPolicy.BASIS_USER_EXPLICIT, p.item.basis);
        assertEquals("video", v.item.mediaKind);
        assertEquals("video/mp4", v.item.mimeType);
        assertEquals(ImportPolicy.BASIS_OWN_MEDIA, v.item.basis);
        assertArrayEquals(photo, Files.readAllBytes(library.fileOf(p.item).toPath()));
        assertArrayEquals(video, Files.readAllBytes(library.fileOf(v.item).toPath()));
        assertEquals(photo.length + video.length, library.usedBytes());
    }

    @Test
    public void mimeHintIsIgnoredAndNonMediaIsRefusedWithoutLeavingFiles() throws Exception {
        byte[] text = "not an image, just text pretending".getBytes();
        try {
            library.importMedia(request(ALICE, "TXT1").build(), source(text));
            fail("text must be refused");
        } catch (LibraryException expected) {
            assertEquals(LibraryException.Code.UNSUPPORTED_MEDIA, expected.code);
        }
        assertEquals(0, LibraryFixture.count(fixture.connection(), "items"));
        assertEquals(0, library.blobs().listBlobs().size());
        assertEquals(0, new File(fixture.libraryDir(), "staging").list().length);
    }

    @Test
    public void identicalBytesOnDifferentMessagesShareOneBlobAndCostQuotaOnce() throws Exception {
        byte[] photo = jpeg(7);
        MediaLibrary.ImportResult first = library.importMedia(request(ALICE, "M1").build(), source(photo));
        MediaLibrary.ImportResult second = library.importMedia(request(BOB, "M2").build(), source(photo));
        byte[] video = mp4(3, 50_000);
        MediaLibrary.ImportResult third = library.importMedia(request(ALICE, "M3").build(), source(video));
        MediaLibrary.ImportResult fourth = library.importMedia(request(ALICE, "M4").fromMe(true).build(), source(video));

        assertEquals(MediaLibrary.ImportStatus.CREATED, first.status);
        assertEquals(MediaLibrary.ImportStatus.DEDUPLICATED, second.status);
        assertEquals(MediaLibrary.ImportStatus.DEDUPLICATED, fourth.status);
        assertEquals(first.item.sha256, second.item.sha256);
        assertNotEquals(first.item.uuid, second.item.uuid);
        assertEquals(third.item.sha256, fourth.item.sha256);
        assertEquals(2, library.blobs().listBlobs().size());
        assertEquals(photo.length + video.length, library.usedBytes());
        assertEquals(4, library.listAll(false).size());
    }

    @Test
    public void reimportingTheSameMessageIsIdempotentAndDoesNotReadTheStream() throws Exception {
        MediaLibrary.ImportResult first = library.importMedia(request(ALICE, "IDEM").build(), source(jpeg(1)));
        AtomicInteger opened = new AtomicInteger();
        MediaLibrary.ImportResult again = library.importMedia(request(ALICE, "IDEM").build(), LibraryFixture.unreadable(opened));

        assertEquals(MediaLibrary.ImportStatus.ALREADY_PRESENT, again.status);
        assertEquals(first.item.uuid, again.item.uuid);
        assertEquals(0, opened.get());
        assertEquals(1, library.listAll(false).size());
    }

    @Test
    public void consecutiveImportsKeepEveryFileDistinctAndOrderedByMessageTime() throws Exception {
        for (int i = 0; i < 40; i++) {
            byte[] bytes = i % 2 == 0 ? jpeg(100 + i) : mp4(100 + i, 4_000 + i);
            library.importMedia(request(ALICE, "SEQ" + i).messageTimestamp(1_000 + i).build(), source(bytes));
        }
        List<LibraryItem> items = library.listChat(WA, ME, Arrays.asList(ALICE), false);
        assertEquals(40, items.size());
        assertEquals("SEQ39", items.get(0).messageKey);
        assertEquals("SEQ0", items.get(39).messageKey);
        assertEquals(40, library.blobs().listBlobs().size());
        for (LibraryItem item : items) assertTrue(library.blobs().verify(item.sha256, item.sizeBytes));
    }

    @Test
    public void quotaIsEvaluatedAfterDeduplication() throws Exception {
        library.setQuotaBytes(MediaLibrary.MIN_QUOTA_BYTES);
        long payload = MediaLibrary.MIN_QUOTA_BYTES - 4096;
        library.importMedia(request(ALICE, "Q1").build(), LibraryFixture.largeMp4(9, payload));

        // The same bytes for another message are free: the import must succeed at a full quota.
        assertEquals(MediaLibrary.ImportStatus.DEDUPLICATED,
                library.importMedia(request(BOB, "Q2").build(), LibraryFixture.largeMp4(9, payload)).status);

        try {
            library.importMedia(request(BOB, "Q3").build(), source(jpeg(5)));
            library.importMedia(request(BOB, "Q4").build(), source(mp4(10, 8192)));
            fail("new bytes beyond the quota must be refused");
        } catch (LibraryException expected) {
            assertEquals(LibraryException.Code.QUOTA_EXCEEDED, expected.code);
        }
        assertNull(library.lookup(WA, ME, BOB, null, "Q4", false));
        assertEquals(0, new File(fixture.libraryDir(), "staging").list().length);
    }

    @Test
    public void insufficientDeviceStorageIsRefusedAndLeavesNothingBehind() throws Exception {
        fixture.usableBytes.set(64L * 1024L * 1024L + 1024L); // just above the 64 MiB test reserve
        try {
            library.importMedia(request(ALICE, "FULL").build(), source(mp4(1, 10_000)));
            fail("import must be refused when the reserve would be used");
        } catch (LibraryException expected) {
            assertEquals(LibraryException.Code.INSUFFICIENT_STORAGE, expected.code);
        }
        assertEquals(0, library.listAll(false).size());
        assertEquals(0, library.blobs().listBlobs().size());
        assertEquals(0, new File(fixture.libraryDir(), "staging").list().length);
    }

    @Test
    public void receivedViewOnceAndStatusKeepNativeExpiryButOwnViewOnceCanBeKept() throws Exception {
        for (ImportPolicyCase refused : new ImportPolicyCase[] {
                new ImportPolicyCase(false, true, false, true),
                new ImportPolicyCase(false, false, true, true),
                new ImportPolicyCase(true, false, false, false) }) {
            AtomicInteger opened = new AtomicInteger();
            try {
                library.importMedia(request(ALICE, "VO" + refused.hashCode()).fromMe(refused.fromMe)
                        .viewOnce(refused.viewOnce).statusBroadcast(refused.status)
                        .userInitiated(refused.user).build(), LibraryFixture.unreadable(opened));
                fail("must be refused");
            } catch (LibraryException expected) {
                assertEquals(LibraryException.Code.NOT_AUTHORIZED, expected.code);
            }
            assertEquals("refused media must not even be read", 0, opened.get());
        }
        MediaLibrary.ImportResult own = library.importMedia(request(ALICE, "OWNVO").fromMe(true).viewOnce(true).build(),
                source(jpeg(3)));
        assertEquals(ImportPolicy.BASIS_OWN_MEDIA, own.item.basis);
        assertEquals(1, library.listAll(false).size());
    }

    private static final class ImportPolicyCase {
        final boolean fromMe, viewOnce, status, user;

        ImportPolicyCase(boolean fromMe, boolean viewOnce, boolean status, boolean user) {
            this.fromMe = fromMe;
            this.viewOnce = viewOnce;
            this.status = status;
            this.user = user;
        }
    }

    @Test
    public void accountsAndWhatsAppFlavoursAreIsolated() throws Exception {
        byte[] photo = jpeg(4);
        library.importMedia(request(ALICE, "ISO").build(), source(photo));
        library.importMedia(request(ALICE, "ISO").packageName(W4B).build(), source(photo));
        library.importMedia(request(ALICE, "ISO").ownJid("5511666660000@s.whatsapp.net").build(), source(photo));

        assertEquals(3, library.listAll(false).size());
        assertEquals("one blob is shared across accounts", 1, library.blobs().listBlobs().size());
        assertEquals(1, library.listChat(WA, ME, Arrays.asList(ALICE), false).size());
        assertEquals(1, library.listChat(W4B, ME, Arrays.asList(ALICE), false).size());
        assertEquals(0, library.listChat(W4B, "unknown@s.whatsapp.net", Arrays.asList(ALICE), false).size());
        LibraryItem wa = library.lookup(WA, ME, ALICE, null, "ISO", false);
        LibraryItem w4b = library.lookup(W4B, ME, ALICE, null, "ISO", false);
        assertNotNull(wa);
        assertNotNull(w4b);
        assertNotEquals(wa.uuid, w4b.uuid);
        assertEquals(WA, wa.packageName);
        assertEquals(W4B, w4b.packageName);
    }

    @Test
    public void phoneJidAndLidAddressTheSameConversationDeterministically() throws Exception {
        library.importMedia(request(ALICE_LID, "LID1").chatAltJid(ALICE).build(), source(jpeg(11)));
        AtomicInteger opened = new AtomicInteger();
        MediaLibrary.ImportResult viaPhone = library.importMedia(request(ALICE, "LID1").build(),
                LibraryFixture.unreadable(opened));
        assertEquals(MediaLibrary.ImportStatus.ALREADY_PRESENT, viaPhone.status);
        assertNotNull(library.lookup(WA, ME, ALICE, null, "LID1", false));
        assertNotNull(library.lookup(WA, ME, ALICE_LID, ALICE, "LID1", false));
        assertEquals(1, library.listChat(WA, ME, Arrays.asList(ALICE), false).size());
        assertEquals(1, library.listChat(WA, ME, Arrays.asList(ALICE_LID), false).size());
        assertNull("a different direction is a different message",
                library.lookup(WA, ME, ALICE, null, "LID1", true));
    }

    @Test
    public void corruptedExistingBlobIsNotReusedButRepairedFromFreshBytes() throws Exception {
        byte[] photo = jpeg(21);
        MediaLibrary.ImportResult first = library.importMedia(request(ALICE, "C1").build(), source(photo));
        File stored = library.fileOf(first.item);
        try (RandomAccessFile file = new RandomAccessFile(stored, "rw")) {
            file.seek(photo.length / 2);
            file.write(0x55 ^ photo[photo.length / 2]);
        }
        assertFalse(library.blobs().verify(first.item.sha256, first.item.sizeBytes));

        // Another message with the same bytes must not be linked to the damaged file as-is.
        MediaLibrary.ImportResult second = library.importMedia(request(BOB, "C2").build(), source(photo));
        assertEquals(MediaLibrary.ImportStatus.CREATED, second.status);
        assertTrue(library.blobs().verify(first.item.sha256, first.item.sizeBytes));
        assertArrayEquals(photo, Files.readAllBytes(stored.toPath()));
        assertEquals(1, library.blobs().listBlobs().size());
    }

    @Test
    public void reimportRepairsAMissingBlobInPlaceKeepingTheItemIdentity() throws Exception {
        byte[] video = mp4(5, 30_000);
        MediaLibrary.ImportResult first = library.importMedia(request(ALICE, "MISS").build(), source(video));
        assertTrue(library.fileOf(first.item).delete());
        MediaLibrary.ReconcileReport report = library.reconcile(false);
        assertEquals(1, report.markedMissing);
        assertFalse(library.get(first.item.uuid).isAvailable());
        assertEquals("the row is kept so the user can repair it", 1, library.listAll(false).size());

        MediaLibrary.ImportResult repaired = library.importMedia(request(ALICE, "MISS").build(), source(video));
        assertEquals(MediaLibrary.ImportStatus.REPAIRED, repaired.status);
        assertEquals(first.item.uuid, repaired.item.uuid);
        assertTrue(repaired.item.isAvailable());
    }

    @Test
    public void controlledDeletionUsesTrashAndKeepsSharedBytes() throws Exception {
        byte[] photo = jpeg(31);
        LibraryItem a = library.importMedia(request(ALICE, "D1").build(), source(photo)).item;
        LibraryItem b = library.importMedia(request(BOB, "D2").build(), source(photo)).item;

        assertFalse("visible items cannot be purged directly", library.purge(a.uuid));
        assertTrue(library.trash(a.uuid));
        assertNull(library.lookup(WA, ME, ALICE, null, "D1", false));
        assertEquals(1, library.listAll(true).size());
        assertTrue(library.restoreFromTrash(a.uuid));
        assertNotNull(library.lookup(WA, ME, ALICE, null, "D1", false));

        assertTrue(library.trash(a.uuid));
        assertTrue(library.purge(a.uuid));
        assertNull(library.get(a.uuid));
        assertTrue("the other message still owns the bytes", library.blobs().verify(b.sha256, b.sizeBytes));
        assertTrue(library.trash(b.uuid));
        assertTrue(library.purge(b.uuid));
        assertEquals(0, library.blobs().listBlobs().size());
        assertEquals(0, library.usedBytes());
    }

    @Test
    public void expiredTrashIsPurgedOnlyAfterRetention() throws Exception {
        LibraryItem item = library.importMedia(request(ALICE, "T1").build(), source(png(2))).item;
        library.trash(item.uuid);
        fixture.clock.addAndGet(MediaLibrary.TRASH_RETENTION_MS - 1000);
        assertEquals(0, library.purgeExpiredTrash());
        fixture.clock.addAndGet(2000);
        assertEquals(1, library.purgeExpiredTrash());
        assertNull(library.get(item.uuid));
    }

    @Test
    public void reimportOfATrashedMessageRestoresItWithoutReadingBytes() throws Exception {
        LibraryItem item = library.importMedia(request(ALICE, "TR").build(), source(jpeg(8))).item;
        library.trash(item.uuid);
        AtomicInteger opened = new AtomicInteger();
        MediaLibrary.ImportResult again = library.importMedia(request(ALICE, "TR").build(), LibraryFixture.unreadable(opened));
        assertEquals(MediaLibrary.ImportStatus.RESTORED_FROM_TRASH, again.status);
        assertEquals(0, opened.get());
        assertFalse(library.get(item.uuid).isTrashed());
    }

    @Test
    public void invalidMetadataIsRefusedBeforeReadingBytes() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        for (String key : new String[] { "", "has space", "../../etc", "x".repeat(200) }) {
            try {
                library.importMedia(request(ALICE, key).build(), LibraryFixture.unreadable(opened));
                fail("key " + key + " must be refused");
            } catch (LibraryException expected) {
                assertEquals(LibraryException.Code.INVALID_REQUEST, expected.code);
            }
        }
        try {
            library.importMedia(request(ALICE, "OK").ownJid(null).build(), LibraryFixture.unreadable(opened));
            fail("missing account must be refused");
        } catch (LibraryException expected) {
            assertEquals(LibraryException.Code.INVALID_REQUEST, expected.code);
        }
        assertEquals(0, opened.get());
    }

    @Test
    public void catalogAndFilesPersistAcrossARestart() throws Exception {
        byte[] photo = jpeg(41);
        LibraryItem item = library.importMedia(request(ALICE, "P1").caption("beach").build(), source(photo)).item;
        library.setQuotaBytes(2L * MediaLibrary.GIB);

        MediaLibrary reopened = fixture.open();
        LibraryItem again = reopened.lookup(WA, ME, ALICE, null, "P1", false);
        assertNotNull(again);
        assertEquals(item.uuid, again.uuid);
        assertEquals("beach", again.caption);
        assertEquals(2L * MediaLibrary.GIB, reopened.quotaBytes());
        assertArrayEquals(photo, Files.readAllBytes(reopened.fileOf(again).toPath()));
    }
}
