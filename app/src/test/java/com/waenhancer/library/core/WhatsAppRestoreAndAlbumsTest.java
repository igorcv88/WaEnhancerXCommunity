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
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Restore-into-WhatsApp on real files, and the view-once / normal separation. */
public class WhatsAppRestoreAndAlbumsTest {
    private LibraryFixture fixture;
    private File waRoot;
    private List<String> roots;

    @Before
    public void setUp() throws Exception {
        fixture = new LibraryFixture();
        waRoot = new File(fixture.root, "Android/media/com.whatsapp/WhatsApp");
        assertTrue(waRoot.mkdirs());
        roots = Collections.singletonList(waRoot.getCanonicalPath());
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

    @Test
    public void restoresExactBytesAtTheRecordedPathOnlyWhenEnabled() throws Exception {
        byte[] photo = jpeg(1);
        File target = target("IMG-1.jpg");
        assertEquals(WhatsAppRestore.Outcome.DISABLED,
                WhatsAppRestore.restore(false, false, false, target, roots, bytes(photo)));
        assertFalse("off by default means nothing is written", target.exists());

        assertEquals(WhatsAppRestore.Outcome.RESTORED,
                WhatsAppRestore.restore(true, false, false, target, roots, bytes(photo)));
        assertArrayEquals(photo, Files.readAllBytes(target.toPath()));
        assertEquals("no temporary file is left", 1, target.getParentFile().list().length);
    }

    @Test
    public void neverOverwritesNeverWritesViewOnceAndRespectsAUserDeletion() throws Exception {
        File existing = target("IMG-2.jpg");
        assertTrue(existing.getParentFile().mkdirs());
        Files.write(existing.toPath(), new byte[] { 1, 2, 3 });
        assertEquals(WhatsAppRestore.Outcome.ALREADY_PRESENT,
                WhatsAppRestore.restore(true, false, false, existing, roots, bytes(jpeg(2))));
        assertArrayEquals(new byte[] { 1, 2, 3 }, Files.readAllBytes(existing.toPath()));

        File viewOnce = target("VO.jpg");
        assertEquals(WhatsAppRestore.Outcome.VIEW_ONCE,
                WhatsAppRestore.restore(true, true, false, viewOnce, roots, bytes(jpeg(3))));
        assertFalse(viewOnce.exists());

        File cleared = target("CLEARED.jpg");
        assertEquals(WhatsAppRestore.Outcome.USER_CLEARED,
                WhatsAppRestore.restore(true, false, true, cleared, roots, bytes(jpeg(4))));
        assertFalse(cleared.exists());
    }

    @Test
    public void refusesPathsOutsideWhatsAppsRootsIncludingTraversal() throws Exception {
        File outside = new File(fixture.root, "DCIM/x.jpg");
        assertEquals(WhatsAppRestore.Outcome.OUTSIDE_ROOTS,
                WhatsAppRestore.restore(true, false, false, outside, roots, bytes(jpeg(5))));
        File traversal = new File(waRoot, "../../../../DCIM/y.jpg");
        assertEquals(WhatsAppRestore.Outcome.OUTSIDE_ROOTS,
                WhatsAppRestore.restore(true, false, false, traversal, roots, bytes(jpeg(5))));
        assertFalse(new File(fixture.root, "DCIM").exists());
    }

    @Test
    public void interruptedCopyLeavesNoFileUnderWhatsAppsName() throws Exception {
        File target = target("VID-1.mp4");
        byte[] video = mp4(6, 200_000);
        WhatsAppRestore.Source breaking = () -> new InputStream() {
            private final InputStream delegate = new ByteArrayInputStream(video);
            private int served;

            @Override
            public int read() throws IOException {
                if (++served > 50_000) throw new IOException("library became unreachable");
                return delegate.read();
            }
        };
        assertEquals(WhatsAppRestore.Outcome.FAILED,
                WhatsAppRestore.restore(true, false, false, target, roots, breaking));
        assertFalse(target.exists());
        assertEquals(0, target.getParentFile().list().length);
    }

    @Test
    public void viewOnceAndNormalMediaStaySeparateForTheTabAndTheSaveAlbum() throws Exception {
        MediaLibrary library = fixture.open();
        String chat = "c@s.whatsapp.net";
        library.importMedia(request(chat, "N1").build(), source(jpeg(10)));
        library.importMedia(request(chat, "V1").viewOnce(true).keepReceivedViewOnce(true).userInitiated(false).build(),
                source(jpeg(11)));
        library.importMedia(request(chat, "V2").viewOnce(true).keepReceivedViewOnce(true).userInitiated(false).build(),
                source(mp4(12, 5_000)));
        library.importMedia(request(chat, "OWNVO").fromMe(true).viewOnce(true).build(), source(jpeg(13)));

        List<LibraryItem> viewOnce = library.listChatByOrigin(WA, ME, Arrays.asList(chat), true);
        List<LibraryItem> normal = library.listChatByOrigin(WA, ME, Arrays.asList(chat), false);
        assertEquals(3, viewOnce.size());
        assertEquals(1, normal.size());
        assertEquals("N1", normal.get(0).messageKey);
        for (LibraryItem item : viewOnce) {
            assertEquals(GalleryAlbums.VIEW_ONCE, GalleryAlbums.relativePathFor(item.viewOnce));
        }
        assertEquals(GalleryAlbums.NORMAL, GalleryAlbums.relativePathFor(normal.get(0).viewOnce));
        LibraryItem first = viewOnce.get(0);
        String name = GalleryAlbums.displayName(first.sha256, 1_700_000_000_000L, first.mimeType);
        assertTrue(name, name.matches("WA-\\d{8}-[0-9a-f]{8}\\.(jpg|mp4)"));
    }

    @Test
    public void viewOnceFlagSurvivesBackupAndRestore() throws Exception {
        MediaLibrary library = fixture.open();
        library.importMedia(request("c@s.whatsapp.net", "VB").viewOnce(true).keepReceivedViewOnce(true)
                .userInitiated(false).build(), source(jpeg(20)));
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        new LibraryBackup(library).export(out, "password1".toCharArray(), 1000, 4096);
        try (LibraryFixture other = new LibraryFixture()) {
            MediaLibrary restored = other.open();
            new LibraryBackup(restored).restore(new ByteArrayInputStream(out.toByteArray()), "password1".toCharArray(), 1000);
            assertTrue(restored.lookup(WA, ME, "c@s.whatsapp.net", null, "VB", false).viewOnce);
        }
    }

    @Test
    public void mediaScreenContactIsRecognisedOnlyFromRealChatJids() {
        assertEquals("5511999990000@s.whatsapp.net", ChatJids.first(Arrays.asList(42, "title", "5511999990000@s.whatsapp.net")));
        assertEquals("123456789012345@lid", ChatJids.first(Arrays.asList(null, "123456789012345@lid")));
        assertEquals("5511999990000-1600000000@g.us", ChatJids.first(Arrays.asList("5511999990000-1600000000@g.us")));
        assertEquals(null, ChatJids.first(Arrays.asList("status@broadcast", "x@s.whatsapp.net", "a b@lid", "")));
        assertEquals(null, ChatJids.first(null));
    }
}
