package com.waenhancer.library.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Random;

public class StreamingAeadAndPolicyTest {
    private static final char[] PASSWORD = "pw".toCharArray();
    private static final int CHUNK = 1024;

    private static byte[] seal(byte[] plain) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (OutputStream sealed = StreamingAead.encrypt(out, PASSWORD, 1000, CHUNK)) {
            // Odd write sizes exercise chunk boundaries independently of the write pattern.
            int offset = 0;
            Random random = new Random(plain.length);
            while (offset < plain.length) {
                int n = Math.min(plain.length - offset, 1 + random.nextInt(700));
                sealed.write(plain, offset, n);
                offset += n;
            }
        }
        return out.toByteArray();
    }

    private static byte[] open(byte[] sealed) throws Exception {
        try (InputStream in = StreamingAead.decrypt(new ByteArrayInputStream(sealed), PASSWORD, 1000)) {
            return in.readAllBytes();
        }
    }

    @Test
    public void roundTripsAtAndAroundChunkBoundaries() throws Exception {
        for (int size : new int[] { 0, 1, CHUNK - 1, CHUNK, CHUNK + 1, 3 * CHUNK, 3 * CHUNK + 17 }) {
            byte[] plain = new byte[size];
            new Random(size).nextBytes(plain);
            byte[] sealed = seal(plain);
            assertArrayEquals("size " + size, plain, open(sealed));
            int chunks = size / CHUNK + 1;
            assertEquals("ciphertext layout for " + size,
                    StreamingAead.HEADER_BYTES + size + 16L * chunks, sealed.length);
        }
    }

    @Test
    public void reorderedDroppedOrTruncatedChunksAreRejected() throws Exception {
        byte[] plain = new byte[4 * CHUNK + 10];
        new Random(3).nextBytes(plain);
        byte[] sealed = seal(plain);
        int h = StreamingAead.HEADER_BYTES;
        int c = CHUNK + 16;

        byte[] swapped = sealed.clone();
        System.arraycopy(sealed, h, swapped, h + c, c);
        System.arraycopy(sealed, h + c, swapped, h, c);
        byte[] dropped = new byte[sealed.length - c];
        System.arraycopy(sealed, 0, dropped, 0, h + c);
        System.arraycopy(sealed, h + 2 * c, dropped, h + c, sealed.length - h - 2 * c);
        byte[] atBoundary = Arrays.copyOf(sealed, h + 2 * c);
        byte[] appended = Arrays.copyOf(sealed, sealed.length + 1);

        for (byte[] bad : new byte[][] { swapped, dropped, atBoundary, appended, Arrays.copyOf(sealed, sealed.length - 3) }) {
            try {
                open(bad);
                fail("manipulated stream must be rejected");
            } catch (LibraryException expected) {
                assertEquals(LibraryException.Code.BACKUP_INVALID, expected.code);
            }
        }
    }

    @Test
    public void unsafeKdfParametersAreRefusedBeforeDerivingAKey() throws Exception {
        byte[] sealed = seal(new byte[10]);
        byte[] weak = sealed.clone();
        // iterations field follows magic(8) + version(1)
        weak[9] = 0; weak[10] = 0; weak[11] = 0; weak[12] = 1;
        try (InputStream in = StreamingAead.decrypt(new ByteArrayInputStream(weak), PASSWORD)) {
            in.readAllBytes();
            fail("one PBKDF2 iteration must be refused by the production entry point");
        } catch (LibraryException expected) {
            assertEquals(LibraryException.Code.BACKUP_INVALID, expected.code);
        }
        assertTrue(StreamingAead.looksLikeBackup(sealed));
        assertFalse(StreamingAead.looksLikeBackup("WAEXFULL1".getBytes()));
    }

    @Test
    public void snifferRecognisesRealContainersAndRejectsLookalikes() {
        assertEquals("image/jpeg", MediaSniffer.sniff(LibraryFixture.jpeg(1), 16));
        assertEquals("image/png", MediaSniffer.sniff(LibraryFixture.png(1), 16));
        assertEquals("video/mp4", MediaSniffer.sniff(LibraryFixture.mp4(1, 10), 16));
        assertEquals("image/gif", MediaSniffer.sniff("GIF89a...........".getBytes(), 16));
        assertEquals("image/webp", MediaSniffer.sniff("RIFF\0\0\0\0WEBPVP8 ".getBytes(), 16));
        assertEquals("video/3gpp", MediaSniffer.sniff(new byte[] { 0, 0, 0, 20, 'f', 't', 'y', 'p', '3', 'g', 'p', '4', 0, 0, 0, 0 }, 16));
        assertEquals("image/heic", MediaSniffer.sniff(new byte[] { 0, 0, 0, 20, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c', 0, 0, 0, 0 }, 16));
        assertEquals("video/webm", MediaSniffer.sniff(new byte[] { 0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0, 0, 0, 0 }, 8));
        assertNull(MediaSniffer.sniff("%PDF-1.7 not media".getBytes(), 16));
        assertNull(MediaSniffer.sniff("RIFF\0\0\0\0WAVEfmt ".getBytes(), 16));
        assertNull(MediaSniffer.sniff(new byte[] { (byte) 0xFF, (byte) 0xD8 }, 2));
        assertNull(MediaSniffer.kindOf("application/pdf"));
    }

    @Test
    public void policyAllowsOnlyExplicitUserActionsAndNeverReceivedEphemeralMedia() throws Exception {
        assertEquals(ImportPolicy.BASIS_OWN_MEDIA, ImportPolicy.authorize(new ImportPolicy.Facts(true, true, false, true)));
        assertEquals(ImportPolicy.BASIS_OWN_MEDIA, ImportPolicy.authorize(new ImportPolicy.Facts(true, false, true, true)));
        assertEquals(ImportPolicy.BASIS_USER_EXPLICIT, ImportPolicy.authorize(new ImportPolicy.Facts(false, false, false, true)));
        for (ImportPolicy.Facts refused : new ImportPolicy.Facts[] {
                new ImportPolicy.Facts(false, true, false, true),
                new ImportPolicy.Facts(false, false, true, true),
                new ImportPolicy.Facts(true, false, false, false),
                null }) {
            try {
                ImportPolicy.authorize(refused);
                fail();
            } catch (LibraryException expected) {
                assertEquals(LibraryException.Code.NOT_AUTHORIZED, expected.code);
            }
        }
        assertTrue(ImportPolicy.isViewOnceMediaType(42));
        assertTrue(ImportPolicy.isViewOnceMediaType(43));
        assertFalse(ImportPolicy.isViewOnceMediaType(1));
    }
}
