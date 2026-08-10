package com.waenhancer.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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
        assertTrue(UpdateVerifier.versionIsAcceptable(18001, 18002, false, false));
    }

    @Test
    public void theSameVersionIsNotAnUpdate() {
        assertFalse(UpdateVerifier.versionIsAcceptable(18001, 18001, false, false));
        assertFalse(UpdateVerifier.versionIsAcceptable(18001, 18001, true, false));
        assertTrue(UpdateVerifier.versionIsAcceptable(18001, 18001, false, true));
    }

    @Test
    public void aDowngradeIsRefusedUnlessExplicitlyRequested() {
        assertFalse(UpdateVerifier.versionIsAcceptable(18002, 18001, false, false));
        assertTrue(UpdateVerifier.versionIsAcceptable(18002, 18001, true, false));
    }

    @Test
    public void hexEncodingIsLowercaseAndPadded() {
        assertEquals("000f10ff", UpdateVerifier.hex(new byte[]{0x00, 0x0f, 0x10, (byte) 0xff}));
    }

    // ---- extractSha256: the digest published in the GitHub release notes -------------------
    // The release workflow appends "SHA-256: `<hex>`" to every set of notes. If this parser and
    // that format drift apart, the verifier silently loses its digest and refuses every update,
    // so these pin the exact shape the workflow emits.

    private static final String PUBLISHED =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    public void theDigestIsReadFromNotesInTheFormatTheWorkflowPublishes() {
        String notes = "Manual signed release from commit `abc123`.\n\n"
                + "SHA-256: `" + PUBLISHED + "`\n";
        assertEquals(PUBLISHED, UpdateVerifier.extractSha256(notes));
    }

    @Test
    public void theDigestIsStillFoundWithoutBackticksAndIsNormalisedToLowercase() {
        assertEquals(PUBLISHED,
                UpdateVerifier.extractSha256("SHA-256: " + PUBLISHED.toUpperCase()));
    }

    @Test
    public void theDigestIsFoundAfterAuthoredReleaseNotes() {
        String notes = "## What's new\n\n- Fixed the bottom bar\n- Nothing else\n\n"
                + "SHA-256: `" + PUBLISHED + "`";
        assertEquals(PUBLISHED, UpdateVerifier.extractSha256(notes));
    }

    /** No digest must read as "cannot be proven", which {@code verify} turns into a refusal. */
    @Test
    public void notesWithoutADigestYieldNothingRatherThanAPass() {
        assertNull(UpdateVerifier.extractSha256("A release with no checksum line."));
        assertNull(UpdateVerifier.extractSha256(""));
        assertNull(UpdateVerifier.extractSha256(null));
    }

    /** A truncated hash must not be accepted as a short digest that later "matches". */
    @Test
    public void aStringThatIsNotSixtyFourHexCharactersIsNotADigest() {
        assertNull(UpdateVerifier.extractSha256("SHA-256: `deadbeef`"));
        assertNull(UpdateVerifier.extractSha256(
                "SHA-256: `" + PUBLISHED.substring(0, 63) + "`"));
    }

    /** Whatever extractSha256 returns has to be what digestMatches accepts. */
    @Test
    public void theExtractedDigestIsAcceptedByTheComparator() {
        String extracted = UpdateVerifier.extractSha256("SHA-256: `" + PUBLISHED + "`");
        assertTrue(UpdateVerifier.digestMatches(extracted, PUBLISHED));
    }
}
