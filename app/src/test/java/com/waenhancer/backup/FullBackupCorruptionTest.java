package com.waenhancer.backup;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The failure paths of the encrypted full-backup container.
 *
 * <p>The round-trip test on its own is false confidence: it proves the happy path and nothing
 * about what a restore does with a truncated file, a flipped bit, an attacker-chosen KDF cost
 * or the wrong password. Those are the cases a user actually meets, so each one is pinned to a
 * refusal here rather than to whatever the container happens to do.</p>
 */
public class FullBackupCorruptionTest {

    private static final byte[] PAYLOAD =
            "{\"formatVersion\":1,\"secrets\":{\"groq_api_key\":\"s3cret\"}}".getBytes(StandardCharsets.UTF_8);
    private static final char[] PASSWORD = "correct horse battery".toCharArray();

    private static byte[] sealed() throws Exception {
        return FullBackupCrypto.encrypt(PAYLOAD, PASSWORD);
    }

    @Test
    public void theWrongPasswordIsRefusedRatherThanReturningGarbage() throws Exception {
        try {
            FullBackupCrypto.decrypt(sealed(), "not the password".toCharArray());
            fail("A wrong password must not produce plaintext");
        } catch (BackupCodec.BackupException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("password"));
        }
    }

    @Test(expected = BackupCodec.BackupException.class)
    public void anEmptyPasswordIsRefused() throws Exception {
        FullBackupCrypto.decrypt(sealed(), new char[0]);
    }

    @Test(expected = BackupCodec.BackupException.class)
    public void aTruncatedContainerIsRefused() throws Exception {
        byte[] encrypted = sealed();
        FullBackupCrypto.decrypt(Arrays.copyOf(encrypted, encrypted.length - 8), PASSWORD);
    }

    @Test(expected = BackupCodec.BackupException.class)
    public void aContainerShorterThanItsHeaderIsRefused() throws Exception {
        FullBackupCrypto.decrypt(new byte[] { 1, 2, 3, 4 }, PASSWORD);
    }

    @Test(expected = BackupCodec.BackupException.class)
    public void afileThatIsNotABackupIsRefused() throws Exception {
        byte[] foreign = new byte[128];
        Arrays.fill(foreign, (byte) 0x41);
        FullBackupCrypto.decrypt(foreign, PASSWORD);
    }

    /** The salt and IV are authenticated as AAD, so moving them must break the tag. */
    @Test
    public void everyHeaderByteIsAuthenticated() throws Exception {
        byte[] encrypted = sealed();
        int headerLength = "WAEXFULL1".length() + 4 + 16 + 12;
        for (int index = "WAEXFULL1".length(); index < headerLength; index++) {
            byte[] tampered = encrypted.clone();
            tampered[index] ^= 0x01;
            try {
                FullBackupCrypto.decrypt(tampered, PASSWORD);
                fail("Header byte " + index + " is not authenticated");
            } catch (BackupCodec.BackupException expected) {
                assertFalse(expected.getMessage().isEmpty());
            }
        }
    }

    /** A backup claiming a trivial KDF cost must be refused before any key is derived. */
    @Test
    public void anUnsafeIterationCountIsRefused() throws Exception {
        byte[] encrypted = sealed();
        ByteBuffer.wrap(encrypted).putInt("WAEXFULL1".length(), 1);
        try {
            FullBackupCrypto.decrypt(encrypted, PASSWORD);
            fail("A downgraded KDF cost must not be accepted");
        } catch (BackupCodec.BackupException expected) {
            assertTrue(expected.getMessage().contains("KDF"));
        }
    }

    @Test
    public void anAbsurdIterationCountIsRefusedInsteadOfHangingTheDevice() throws Exception {
        byte[] encrypted = sealed();
        ByteBuffer.wrap(encrypted).putInt("WAEXFULL1".length(), Integer.MAX_VALUE);
        try {
            FullBackupCrypto.decrypt(encrypted, PASSWORD);
            fail("An unbounded KDF cost must not be accepted");
        } catch (BackupCodec.BackupException expected) {
            assertTrue(expected.getMessage().contains("KDF"));
        }
    }

    @Test
    public void everyCiphertextByteIsAuthenticated() throws Exception {
        byte[] encrypted = sealed();
        int headerLength = "WAEXFULL1".length() + 4 + 16 + 12;
        for (int index = headerLength; index < encrypted.length; index++) {
            byte[] tampered = encrypted.clone();
            tampered[index] ^= 0x01;
            try {
                FullBackupCrypto.decrypt(tampered, PASSWORD);
                fail("Ciphertext byte " + index + " is not authenticated");
            } catch (BackupCodec.BackupException expected) {
                // refusal is the contract
            }
        }
    }

    @Test
    public void twoBackupsOfTheSamePayloadDifferAndBothOpen() throws Exception {
        byte[] first = sealed();
        byte[] second = sealed();
        assertFalse("The salt or IV is being reused", Arrays.equals(first, second));
        assertArrayEquals(PAYLOAD, FullBackupCrypto.decrypt(first, PASSWORD));
        assertArrayEquals(PAYLOAD, FullBackupCrypto.decrypt(second, PASSWORD));
    }

    @Test
    public void theSealedContainerDoesNotCarryThePlaintext() throws Exception {
        String encrypted = new String(sealed(), StandardCharsets.ISO_8859_1);
        assertEquals(-1, encrypted.indexOf("groq_api_key"));
        assertEquals(-1, encrypted.indexOf("s3cret"));
    }
}
