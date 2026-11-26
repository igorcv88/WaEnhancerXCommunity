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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/**
 * The reported failure: a received view-once photo, left "unopened" by Unlimited View Once, is
 * expired by WhatsApp and then shows "Photo expired". With the owner's "Keep received view-once"
 * switch on, the copy is taken automatically while WhatsApp still has the file and must keep
 * opening after WhatsApp deletes it, whatever the elapsed time.
 */
public class ViewOnceKeepAndRestoreTest {
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static final String CHAT = "5511777770000@s.whatsapp.net";
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
    public void receivedViewOnceIsKeptAutomaticallyOnlyWhenTheModuleSwitchIsOn() throws Exception {
        MediaLibrary library = fixture.open();
        try {
            library.importMedia(request(CHAT, "VO1").viewOnce(true).userInitiated(false).build(), source(jpeg(1)));
            fail("switch off: refused");
        } catch (LibraryException expected) {
            assertEquals(LibraryException.Code.NOT_AUTHORIZED, expected.code);
        }
        LibraryItem kept = library.importMedia(request(CHAT, "VO1").viewOnce(true).userInitiated(false)
                .keepReceivedViewOnce(true).build(), source(jpeg(1))).item;
        assertEquals(ImportPolicy.BASIS_AUTO_VIEW_ONCE, kept.basis);
        try {
            library.importMedia(request("status@broadcast", "ST1").statusBroadcast(true).viewOnce(true)
                    .userInitiated(true).keepReceivedViewOnce(true).build(), source(jpeg(2)));
            fail("others' status posts stay refused");
        } catch (LibraryException expected) {
            assertEquals(LibraryException.Code.NOT_AUTHORIZED, expected.code);
        }
        try {
            library.importMedia(request(CHAT, "NORMAL").userInitiated(false).keepReceivedViewOnce(true).build(),
                    source(jpeg(3)));
            fail("the switch does not turn on automatic capture of normal media");
        } catch (LibraryException expected) {
            assertEquals(LibraryException.Code.NOT_AUTHORIZED, expected.code);
        }
    }

    @Test
    public void autoKeptViewOnceOpensAfterWhatsAppExpiresItAtAnyAge() throws Exception {
        MediaLibrary library = fixture.open();
        byte[] photo = jpeg(10);
        byte[] video = mp4(11, 90_000);
        File waPhoto = new File(fixture.root, "wa-photo");
        File waVideo = new File(fixture.root, "wa-video");
        Files.write(waPhoto.toPath(), photo);
        Files.write(waVideo.toPath(), video);
        library.importMedia(request(CHAT, "VOP").viewOnce(true).userInitiated(false).keepReceivedViewOnce(true).build(),
                () -> new FileInputStream(waPhoto));
        library.importMedia(request(CHAT, "VOV").viewOnce(true).userInitiated(false).keepReceivedViewOnce(true).build(),
                () -> new FileInputStream(waVideo));
        // WhatsApp expires the unopened view-once and drops its file.
        assertTrue(waPhoto.delete());
        assertTrue(waVideo.delete());

        long start = fixture.clock.get();
        for (long days : new long[] { 13, 14, 15, 30, 31, 60, 61, 365 }) {
            fixture.clock.set(start + days * DAY_MS);
            MediaLibrary later = fixture.open();
            later.reconcile(true);
            later.purgeExpiredTrash();
            LibraryItem p = later.lookup(WA, ME, CHAT, null, "VOP", false);
            LibraryItem v = later.lookup(WA, ME, CHAT, null, "VOV", false);
            assertNotNull("day " + days, p);
            assertNotNull("day " + days, v);
            assertArrayEquals(photo, Files.readAllBytes(later.fileOf(p).toPath()));
            assertArrayEquals(video, Files.readAllBytes(later.fileOf(v).toPath()));
            assertTrue(InlinePresentation.shouldPresent(true, p.isAvailable(), false, false));
        }
    }

    @Test
    public void repeatedAutomaticKeepsOfTheSameViewOnceAreIdempotent() throws Exception {
        MediaLibrary library = fixture.open();
        for (int i = 0; i < 5; i++) {
            library.importMedia(request(CHAT, "VOX").viewOnce(true).userInitiated(false).keepReceivedViewOnce(true).build(),
                    source(jpeg(20)));
        }
        assertEquals(1, library.listAll(false).size());
        assertEquals(1, library.blobs().listBlobs().size());
    }

    @Test
    public void storedPathsResolveOnlyInsideTheGivenRootsWithoutTraversal() {
        List<String> roots = Arrays.asList("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp", "/data/user/0/com.whatsapp/files");
        assertEquals(Arrays.asList("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/IMG-1.jpg",
                "/data/user/0/com.whatsapp/files/Media/WhatsApp Images/IMG-1.jpg"),
                WhatsAppMediaPaths.candidates("Media/WhatsApp Images/IMG-1.jpg", roots));
        assertEquals(Arrays.asList("/abs/x.jpg"), WhatsAppMediaPaths.candidates("/abs/x.jpg", roots));
        assertTrue(WhatsAppMediaPaths.candidates("../../data/data/x", roots).isEmpty());
        assertTrue(WhatsAppMediaPaths.candidates(null, roots).isEmpty());
    }

    @Test
    public void restoreIntoWhatsAppOnlyForMissingNormalMediaInsideItsOwnRoots() {
        List<String> roots = Arrays.asList("/storage/emulated/0/Android/media/com.whatsapp");
        String target = "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/IMG-1.jpg";
        assertTrue(WhatsAppMediaPaths.isSafeRestoreTarget(target, roots, false, false));
        assertFalse("never overwrite", WhatsAppMediaPaths.isSafeRestoreTarget(target, roots, true, false));
        assertFalse("never write view-once back", WhatsAppMediaPaths.isSafeRestoreTarget(target, roots, false, true));
        assertFalse(WhatsAppMediaPaths.isSafeRestoreTarget("/storage/emulated/0/DCIM/x.jpg", roots, false, false));
        assertFalse(WhatsAppMediaPaths.isSafeRestoreTarget("/storage/emulated/0/Android/media/com.whatsapp.evil/x", roots, false, false));
        assertFalse(WhatsAppMediaPaths.isSafeRestoreTarget(
                "/storage/emulated/0/Android/media/com.whatsapp/../../x", roots, false, false));
        assertFalse(WhatsAppMediaPaths.isSafeRestoreTarget("/storage/emulated/0/Android/media/com.whatsapp/", roots, false, false));
    }
}
