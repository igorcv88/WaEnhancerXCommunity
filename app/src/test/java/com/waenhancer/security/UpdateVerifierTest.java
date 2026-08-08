package com.waenhancer.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/** The updater installed whatever it downloaded. These are the checks that stop that. */
public class UpdateVerifierTest {

    /** Known-answer test so a broken digest implementation cannot pass silently. */
    @Test
    public void sha256MatchesTheKnownAnswerForAbc() throws Exception {
        String digest = UpdateVerifier.sha256(
                new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                digest);
    }

    @Test
    public void digestComparisonIgnoresCaseAndSurroundingSpace() {
        String digest = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        assertTrue(UpdateVerifier.digestMatches(digest, digest.toUpperCase()));
        assertTrue(UpdateVerifier.digestMatches("  " + digest + "\n", digest));
    }

    @Test
    public void digestComparisonRejectsAnythingElse() {
        String digest = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        assertFalse(UpdateVerifier.digestMatches(digest, digest.replace('a', 'b')));
        assertFalse("a prefix must not pass",
                UpdateVerifier.digestMatches(digest, digest.substring(0, 20)));
        assertFalse(UpdateVerifier.digestMatches(digest, null));
        assertFalse(UpdateVerifier.digestMatches(null, digest));
        assertFalse("an absent published digest must not wave the update through",
                UpdateVerifier.digestMatches("", digest));
    }

    @Test
    public void aNewerVersionIsAccepted() {
        assertTrue(UpdateVerifier.versionIsAcceptable(18001, 18002, false));
    }

    @Test
    public void theSameVersionIsNotAnUpdate() {
        assertFalse(UpdateVerifier.versionIsAcceptable(18001, 18001, false));
        assertFalse(UpdateVerifier.versionIsAcceptable(18001, 18001, true));
    }

    @Test
    public void aDowngradeIsRefusedUnlessExplicitlyRequested() {
        assertFalse(UpdateVerifier.versionIsAcceptable(18002, 18001, false));
        assertTrue(UpdateVerifier.versionIsAcceptable(18002, 18001, true));
    }

    @Test
    public void hexEncodingIsLowercaseAndPadded() {
        assertEquals("000f10ff", UpdateVerifier.hex(new byte[]{0x00, 0x0f, 0x10, (byte) 0xff}));
    }
}
