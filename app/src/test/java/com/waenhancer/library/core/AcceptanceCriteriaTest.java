package com.waenhancer.library.core;

import static com.waenhancer.library.core.LibraryFixture.ME;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Acceptance criteria from the independent review: complete validation of imported media,
 * periodic SHA-256 verification, explicit detection of ambiguous associations, reports of files
 * without records and records without files, JID/LID change, WhatsApp update and backup restore,
 * a joint backup of files and catalog, and a resumable first-run import.
 */
public class AcceptanceCriteriaTest {
    private static final String PHONE = "5511888880000@s.whatsapp.net";
    private static final String LID = "123456789012345@lid";

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

    private void assertRefusedIncomplete(byte[] bytes, String what) throws Exception {
        try {
            library.importMedia(request(PHONE, "BAD" + Math.abs(what.hashCode())).build(), source(bytes));
            fail(what + " must be refused");
        } catch (LibraryException expected) {
            assertEquals(what, LibraryException.Code.INCOMPLETE_MEDIA, expected.code);
        }
        assertEquals(what + ": nothing stored", 0, library.listAll(false).size());
        assertEquals(0, library.blobs().listBlobs().size());
        assertEquals(0, new File(fixture.libraryDir(), "staging").list().length);
    }

    // ---------------------------------------------------------------- 1. complete validation

    @Test
    public void truncatedOrInconsistentPhotosAndVideosAreRefusedAndNothingIsStored() throws Exception {
        byte[] photo = jpeg(1);
        assertRefusedIncomplete(Arrays.copyOf(photo, photo.length - 2), "JPEG without EOI (truncated download)");
        assertRefusedIncomplete(Arrays.copyOf(photo, 40), "JPEG cut inside its headers");

        byte[] image = png(2);
        byte[] badCrc = image.clone();
        badCrc[40] ^= 0x10;
        assertRefusedIncomplete(badCrc, "PNG with a damaged chunk");
        assertRefusedIncomplete(Arrays.copyOf(image, image.length - 12), "PNG without IEND");

        byte[] video = mp4(3, 50_000);
        assertRefusedIncomplete(Arrays.copyOf(video, video.length - 1000), "MP4 whose media data is cut");
        byte[] noMoov = video.clone();
        System.arraycopy("free".getBytes(StandardCharsets.US_ASCII), 0, noMoov, 24 + 4, 4); // moov -> free
        assertRefusedIncomplete(noMoov, "MP4 without a movie header");

        assertRefusedIncomplete("GIF89a\u0001\u0000\u0001\u0000\u0000\u0000\u0000".getBytes(StandardCharsets.ISO_8859_1),
                "GIF without trailer");
        byte[] webp = "RIFFÿ\u0000\u0000\u0000WEBPVP8 \u0000\u0000\u0000\u0000".getBytes(StandardCharsets.ISO_8859_1);
        assertRefusedIncomplete(webp, "WebP whose RIFF size does not match");

        // Complete files of the same kinds are accepted.
        assertEquals(MediaLibrary.ImportStatus.CREATED, library.importMedia(request(PHONE, "OK1").build(), source(photo)).status);
        assertEquals(MediaLibrary.ImportStatus.CREATED, library.importMedia(request(PHONE, "OK2").build(), source(image)).status);
        assertEquals(MediaLibrary.ImportStatus.CREATED, library.importMedia(request(PHONE, "OK3").build(), source(video)).status);
    }

    @Test
    public void validatorAcceptsCompleteContainersOfEverySupportedKind() throws Exception {
        File dir = Files.createTempDirectory(fixture.root.toPath(), "v").toFile();
        File gif = new File(dir, "a.gif");
        Files.write(gif.toPath(), "GIF89a\u0001\u0000\u0001\u0000\u0000\u0000\u0000;".getBytes(StandardCharsets.ISO_8859_1));
        assertNull(MediaValidator.validate(gif, "image/gif"));
        File webp = new File(dir, "a.webp");
        byte[] w = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 \u0000\u0000\u0000\u0000".getBytes(StandardCharsets.ISO_8859_1);
        w[4] = (byte) (w.length - 8);
        Files.write(webp.toPath(), w);
        assertNull(MediaValidator.validate(webp, "image/webp"));
        File webm = new File(dir, "a.webm");
        Files.write(webm.toPath(), new byte[] { 0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, (byte) 0x80,
                0x18, 0x53, (byte) 0x80, 0x67, (byte) 0xFF, 1, 2, 3, 4, 5, 6, 7, 8 });
        assertNull("unknown-size segment (live recording)", MediaValidator.validate(webm, "video/webm"));
        File mkvCut = new File(dir, "b.webm");
        Files.write(mkvCut.toPath(), new byte[] { 0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, (byte) 0x80,
                0x18, 0x53, (byte) 0x80, 0x67, (byte) 0x88, 1, 2, 3, 4, 5, 6 });
        assertEquals("mkv_truncated", MediaValidator.validate(mkvCut, "video/webm"));
    }

    @Test
    public void deviceDecodeProbeCanRejectAFileThatIsStructurallyFine() throws Exception {
        library.setDecodeProbe((file, mime) -> mime.startsWith("video/") ? "no_video_track" : null);
        assertRefusedIncomplete(mp4(4, 10_000), "video the device cannot decode");
        assertEquals(MediaLibrary.ImportStatus.CREATED,
                library.importMedia(request(PHONE, "P").build(), source(jpeg(4))).status);
    }

    @Test
    public void backupContainingAnIncompleteFileIsRejectedWithoutChanges() throws Exception {
        LibraryItem item = library.importMedia(request(PHONE, "B1").build(), source(jpeg(5))).item;
        // Damage the stored file in a way that keeps size and passes the export's own hash check
        // only if the record is updated: simulate a legacy library that stored a truncated file.
        byte[] truncated = Arrays.copyOf(jpeg(5), jpeg(5).length - 2);
        Files.write(library.fileOf(item).toPath(), truncated);
        String sha = BlobStore.hash(library.fileOf(item));
        File moved = library.blobs().path(sha);
        assertTrue(moved.getParentFile().isDirectory() || moved.getParentFile().mkdirs());
        Files.move(library.fileOf(item).toPath(), moved.toPath());
        fixture.connection().exec("PRAGMA foreign_keys = OFF");
        JdbcSql raw = fixture.connection();
        raw.exec("PRAGMA foreign_keys = OFF");
        raw.update("UPDATE blobs SET sha256 = ?, size_bytes = ? WHERE sha256 = ?", sha, truncated.length, item.sha256);
        raw.update("UPDATE items SET sha256 = ? WHERE uuid = ?", sha, item.uuid);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new LibraryBackup(library).export(out, "password1".toCharArray(), 1000, 4096);
        try (LibraryFixture other = new LibraryFixture()) {
            MediaLibrary target = other.open();
            try {
                new LibraryBackup(target).restore(new ByteArrayInputStream(out.toByteArray()), "password1".toCharArray(), 1000);
                fail("an incomplete file in a backup must be rejected");
            } catch (LibraryException expected) {
                assertEquals(LibraryException.Code.BACKUP_INVALID, expected.code);
            }
            assertEquals(0, target.listAll(false).size());
        }
    }

    // ---------------------------------------------------------------- 2. periodic SHA-256

    @Test
    public void periodicVerificationRotatesThroughEveryFileAndCatchesSilentDamage() throws Exception {
        List<LibraryItem> items = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            items.add(library.importMedia(request(PHONE, "R" + i).build(), source(mp4(10 + i, 20_000))).item);
            fixture.clock.addAndGet(1000);
        }
        // Same-size damage: invisible to size checks, only SHA-256 finds it.
        try (RandomAccessFile file = new RandomAccessFile(library.fileOf(items.get(4)), "rw")) {
            file.seek(5000);
            file.write(0x7F);
        }
        Set<String> seen = new HashSet<>();
        int damaged = 0;
        for (int pass = 0; pass < 3; pass++) {
            fixture.clock.addAndGet(60_000);
            LibraryIntegrity.VerifyReport report = LibraryIntegrity.verifyBatch(library, 40_000);
            assertTrue("budget respected (<= 2 files of 20 KB)", report.checked <= 2);
            damaged += report.damaged;
            for (Object row : fixture.connection().query("SELECT sha256 FROM blobs ORDER BY verified_at DESC LIMIT " + report.checked)) {
                seen.add(row.toString());
            }
        }
        assertEquals("three bounded passes covered all six files", 6, seen.size());
        assertEquals(1, damaged);
        assertFalse(library.get(items.get(4).uuid).isAvailable());
        // After repair from the original bytes, the next rotation reports it healthy again.
        library.importMedia(request(PHONE, "R4").build(), source(mp4(14, 20_000)));
        assertTrue(library.get(items.get(4).uuid).isAvailable());
    }

    // ---------------------------------------------------------------- 3. ambiguous associations

    @Test
    public void sameMessageStoredUnderPhoneAndLidIsDetectedNeverGuessedAndResolvedWithoutLoss() throws Exception {
        byte[] photo = jpeg(20);
        // Before WhatsApp ever reported the pair, the message arrived once per addressing form.
        LibraryItem viaPhone = library.importMedia(request(PHONE, "DUP").build(), source(photo)).item;
        fixture.clock.addAndGet(1000);
        LibraryItem viaLid = library.importMedia(request(LID, "DUP").build(), source(photo)).item;
        assertNotEquals(viaPhone.uuid, viaLid.uuid);
        assertNotNull(library.lookup(WA, ME, PHONE, null, "DUP", false));

        library.learnChatAlias(WA, ME, LID, PHONE); // WhatsApp reveals both address one chat
        assertNull("an ambiguous association is never presented", library.lookup(WA, ME, PHONE, null, "DUP", false));
        assertTrue(library.isAmbiguous(WA, ME, LID, null, "DUP", false));
        try {
            library.importMedia(request(PHONE, "DUP").build(), source(photo));
            fail("import must refuse to pick one of two matches");
        } catch (LibraryException expected) {
            assertEquals(LibraryException.Code.AMBIGUOUS, expected.code);
        }
        LibraryIntegrity.Report report = LibraryIntegrity.inspect(library, false);
        assertEquals(1, report.ambiguous.size());
        assertTrue(report.ambiguous.get(0).identicalContent());

        assertEquals(1, LibraryIntegrity.resolveIdentical(library));
        LibraryItem kept = library.lookup(WA, ME, LID, null, "DUP", false);
        assertNotNull(kept);
        assertEquals("the oldest stays", viaPhone.uuid, kept.uuid);
        assertArrayEquals(photo, Files.readAllBytes(library.fileOf(kept).toPath()));
        assertEquals(1, library.listAll(true).size()); // the duplicate row is restorable from trash
        assertTrue(LibraryIntegrity.inspect(library, true).ambiguous.isEmpty());
    }

    @Test
    public void ambiguityWithDifferentContentWaitsForTheUserAndKeepsBothFiles() throws Exception {
        LibraryItem a = library.importMedia(request(PHONE, "CONF").build(), source(jpeg(21))).item;
        LibraryItem b = library.importMedia(request(LID, "CONF").build(), source(jpeg(22))).item;
        library.learnChatAlias(WA, ME, PHONE, LID);
        assertEquals("never resolved by a guess", 0, LibraryIntegrity.resolveIdentical(library));
        LibraryIntegrity.Report report = LibraryIntegrity.inspect(library, false);
        assertEquals(1, report.ambiguous.size());
        assertFalse(report.ambiguous.get(0).identicalContent());

        assertEquals(1, LibraryIntegrity.resolveKeeping(library, b.uuid));
        assertEquals(b.uuid, library.lookup(WA, ME, PHONE, null, "CONF", false).uuid);
        assertTrue("the other file is kept (trash, restorable)", library.blobs().verify(a.sha256, a.sizeBytes));
        assertTrue(library.restoreFromTrash(a.uuid));
        assertNull("restoring it makes the ambiguity visible again", library.lookup(WA, ME, PHONE, null, "CONF", false));
    }

    // ---------------------------------------------------------------- 4. reports, no file lost

    @Test
    public void reportListsFilesWithoutRecordsAndRecordsWithoutFilesAndMaintenanceLosesNoFile() throws Exception {
        LibraryItem lost = library.importMedia(request(PHONE, "LOST").build(), source(jpeg(30))).item;
        library.importMedia(request(PHONE, "FINE").build(), source(jpeg(31))).item.getClass();
        BlobStore.Staged orphan = library.blobs().stage(new ByteArrayInputStream(mp4(32, 9_000)), 1 << 20);
        String orphanSha = orphan.sha256;
        library.blobs().commit(orphan); // bytes on disk, catalog row never written (crash)
        assertTrue(library.fileOf(lost).delete());

        LibraryIntegrity.Report before = LibraryIntegrity.inspect(library, true);
        assertEquals(Arrays.asList(orphanSha), before.filesWithoutRecord);
        assertEquals(Arrays.asList(lost.uuid), before.recordsWithoutFile);
        assertFalse(before.clean());
        assertEquals("inspection changes nothing", before.filesWithoutRecord, LibraryIntegrity.inspect(library, true).filesWithoutRecord);
        assertTrue(library.get(lost.uuid).isAvailable()); // state not touched by inspect

        MediaLibrary.ReconcileReport reconcile = library.reconcile(false);
        assertEquals(1, reconcile.orphanBlobsQuarantined);
        assertEquals(1, reconcile.markedMissing);
        assertTrue("the orphan file is kept, in quarantine", library.blobs().quarantined(orphanSha).isFile());
        assertEquals(Arrays.asList(orphanSha), LibraryIntegrity.inspect(library, false).quarantined);
        assertNotNull("the record without a file is kept, visible as unavailable", library.get(lost.uuid));
        // Keeping the same bytes again re-uses the content and clears the quarantine copy.
        library.importMedia(request(PHONE, "LATER").build(), source(mp4(32, 9_000)));
        assertTrue(LibraryIntegrity.inspect(library, false).quarantined.isEmpty());
    }

    // ---------------------------------------------------------------- 5. JID/LID, update, backup

    @Test
    public void whatsAppUpdateThatMovesChatsFromPhoneJidToLidKeepsEveryAssociation() throws Exception {
        for (int i = 0; i < 10; i++) library.importMedia(request(PHONE, "U" + i).build(), source(jpeg(40 + i)));
        // After the update WhatsApp addresses the chat by LID; on the first bind it still reports
        // the phone JID as the alternative.
        MediaLibrary afterUpdate = fixture.open();
        assertNotNull(afterUpdate.lookup(WA, ME, LID, PHONE, "U0", false));
        // Lookups that only carry the LID fail until the pair is known...
        assertNull(afterUpdate.lookup(WA, ME, LID, null, "U1", false));
        // ...and resolve for every message once any request taught the pair (persisted).
        afterUpdate.importMedia(request(LID, "NEW").chatAltJid(PHONE).build(), source(jpeg(60)));
        MediaLibrary restarted = fixture.open();
        for (int i = 0; i < 10; i++) {
            LibraryItem item = restarted.lookup(WA, ME, LID, null, "U" + i, false);
            assertNotNull("U" + i, item);
            assertEquals(PHONE, item.chatJid);
        }
        assertEquals(11, restarted.listChat(WA, ME, Arrays.asList(LID), false).size());
        assertTrue(LibraryIntegrity.inspect(restarted, false).ambiguous.isEmpty());
    }

    @Test
    public void schemaUpgradeSeedsAliasesFromPairsAlreadyRecorded() throws Exception {
        JdbcSql sql = new JdbcSql(new File(fixture.root, "legacy.db"));
        try {
            new LibrarySchema(LibrarySchema.defaultSteps().subList(0, 3), 3).migrate(sql);
            String sha = "c".repeat(64);
            sql.insert("INSERT INTO accounts (package_name, own_jid, created_at) VALUES ('com.whatsapp', ?, 0)", ME);
            sql.insert("INSERT INTO blobs (sha256, size_bytes, mime_type, created_at, verified_at) VALUES (?, 10, 'image/jpeg', 0, 0)", sha);
            sql.insert("INSERT INTO items (uuid, account_id, chat_jid, chat_alt_jid, message_key, from_me, media_kind, "
                    + "mime_type, sha256, imported_at, basis) VALUES ('u', 1, ?, ?, 'OLD', 0, 'image', 'image/jpeg', ?, 0, "
                    + "'user_explicit')", LID, PHONE, sha);
            new LibrarySchema().migrate(sql);
            assertEquals(LibrarySchema.CURRENT_VERSION, sql.userVersion());
            assertEquals(2, LibraryFixture.count(sql, "chat_aliases"));
        } finally {
            sql.close();
        }
    }

    @Test
    public void jointBackupRestoresFilesAndTheWholeCatalogIdentically() throws Exception {
        library.setQuotaBytes(3L * MediaLibrary.GIB);
        library.importMedia(request(LID, "J1").chatAltJid(PHONE).build(), source(jpeg(70)));
        library.importMedia(request(PHONE, "J2").fromMe(true).viewOnce(true).build(), source(mp4(71, 30_000)));
        library.importMedia(request("9988776655@s.whatsapp.net", "J3").viewOnce(true).keepReceivedViewOnce(true)
                .userInitiated(false).build(), source(png(72)));
        LibraryItem trashed = library.importMedia(request(PHONE, "J4").build(), source(jpeg(73))).item;
        library.trash(trashed.uuid);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new LibraryBackup(library).export(out, "password1".toCharArray(), 1000, 4096);
        try (LibraryFixture other = new LibraryFixture()) {
            MediaLibrary restored = other.open();
            new LibraryBackup(restored).restore(new ByteArrayInputStream(out.toByteArray()), "password1".toCharArray(), 1000);
            assertEquals(LibraryIntegrity.canonicalDump(library), LibraryIntegrity.canonicalDump(restored));
            for (boolean t : new boolean[] { false, true }) {
                for (LibraryItem item : library.listAll(t)) {
                    assertArrayEquals(Files.readAllBytes(library.fileOf(item).toPath()),
                            Files.readAllBytes(restored.fileOf(restored.get(item.uuid)).toPath()));
                }
            }
            assertTrue(LibraryIntegrity.inspect(restored, true).clean());
            assertNotNull("aliases came with the backup", restored.lookup(WA, ME, PHONE, null, "J1", false));
        }
    }

    // ---------------------------------------------------------------- 6. first-run import

    private static final class FakeWhatsApp implements InitialImport.Candidates {
        final List<InitialImport.Candidate> rows = new ArrayList<>();
        long maxRowId;

        @Override
        public List<InitialImport.Candidate> page(long afterRowId, int limit) {
            List<InitialImport.Candidate> page = new ArrayList<>();
            for (InitialImport.Candidate c : rows) {
                if (c.rowId > afterRowId && page.size() < limit) page.add(c);
            }
            return page;
        }

        @Override
        public long maxRowId() {
            return maxRowId;
        }
    }

    private InitialImport.Candidate candidate(FakeWhatsApp wa, long rowId, String key, byte[] bytes) throws IOException {
        File file = null;
        if (bytes != null) {
            file = new File(fixture.root, "wa-" + key);
            Files.write(file.toPath(), bytes);
        }
        InitialImport.Candidate c = new InitialImport.Candidate(rowId, LID, PHONE, key, false, rowId * 1000, file);
        wa.rows.add(c);
        wa.maxRowId = Math.max(wa.maxRowId, rowId);
        return c;
    }

    private final int[] failAtRow = { -1 };

    private InitialImport.Importer importer() {
        return c -> {
            if (c.rowId == failAtRow[0]) throw new IOException("library busy");
            try {
                MediaLibrary.ImportResult result = library.importMedia(request(c.chatJid, c.messageKey).chatAltJid(c.chatAltJid)
                        .viewOnce(true).keepReceivedViewOnce(true).userInitiated(false).messageTimestamp(c.timestamp).build(),
                        () -> new java.io.FileInputStream(c.file));
                return result.status == MediaLibrary.ImportStatus.ALREADY_PRESENT
                        ? InitialImport.Settled.ALREADY_PRESENT : InitialImport.Settled.IMPORTED;
            } catch (LibraryException refused) {
                if (refused.code == LibraryException.Code.AMBIGUOUS) return InitialImport.Settled.AMBIGUOUS;
                if (refused.code == LibraryException.Code.IO) throw refused;
                return InitialImport.Settled.REFUSED;
            }
        };
    }

    private InitialImport.Progress progress() {
        return new InitialImport.Progress() {
            @Override
            public MediaLibrary.SweepState load() {
                return library.sweepState(WA, ME);
            }

            @Override
            public void save(MediaLibrary.SweepState state) throws IOException {
                library.saveSweepState(WA, ME, state);
            }
        };
    }

    @Test
    public void firstRunImportsEveryAvailableViewOnceThenOnlyNewOnesAndResumesAfterFailures() throws Exception {
        FakeWhatsApp wa = new FakeWhatsApp();
        for (int i = 1; i <= 25; i++) candidate(wa, i * 3, "V" + i, i % 5 == 0 ? null : (i % 2 == 0 ? jpeg(100 + i) : mp4(100 + i, 8_000)));
        candidate(wa, 80, "BROKEN", Arrays.copyOf(jpeg(9), 30)); // incomplete file: refused, not retried forever
        failAtRow[0] = 39; // transient failure on V13
        InitialImport.Report first = InitialImport.run(wa, importer(), progress(), 4, () -> false);
        assertTrue(first.initialRun);
        assertFalse("stopped at the failure", first.completed);
        assertEquals(1, first.failed);
        assertEquals(36, library.sweepState(WA, ME).watermark);
        assertFalse(library.sweepState(WA, ME).initialDone);

        failAtRow[0] = -1; // the library is reachable again: resume, nothing twice
        InitialImport.Report second = InitialImport.run(wa, importer(), progress(), 4, () -> false);
        assertTrue(second.completed);
        assertTrue(library.sweepState(WA, ME).initialDone);
        assertEquals("20 with files, 5 without", 20, library.listAll(false).size());
        assertEquals(1, second.refused);

        // A new view-once arrives: the incremental run takes only it.
        candidate(wa, 90, "NEW", jpeg(200));
        InitialImport.Report third = InitialImport.run(wa, importer(), progress(), 4, () -> false);
        assertFalse(third.initialRun);
        assertEquals(1, third.scanned);
        assertEquals(1, third.imported);
        assertEquals(21, library.listAll(false).size());

        // WhatsApp's database is recreated (reinstall/restore): row ids restart below the watermark.
        FakeWhatsApp reinstalled = new FakeWhatsApp();
        candidate(reinstalled, 1, "V1", mp4(101, 8_000));
        candidate(reinstalled, 2, "AFTER", jpeg(201));
        InitialImport.Report fourth = InitialImport.run(reinstalled, importer(), progress(), 4, () -> false);
        assertTrue(fourth.watermarkReset);
        assertEquals(1, fourth.alreadyPresent);
        assertEquals(1, fourth.imported);
        assertEquals(22, library.listAll(false).size());
        for (LibraryItem item : library.listAll(false)) assertTrue(item.viewOnce);
    }
}
