package com.waenhancer.backup;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class FullBackupCryptoTest {
    @Test public void roundTripRequiresTheSamePassword() throws Exception {
        byte[] encrypted = FullBackupCrypto.encrypt("private payload".getBytes(StandardCharsets.UTF_8), "correct horse".toCharArray());
        assertArrayEquals("private payload".getBytes(StandardCharsets.UTF_8), FullBackupCrypto.decrypt(encrypted, "correct horse".toCharArray()));
    }
    @Test(expected = BackupCodec.BackupException.class) public void modifiedContainerIsRejected() throws Exception {
        byte[] encrypted = FullBackupCrypto.encrypt(new byte[] { 1, 2, 3 }, "password".toCharArray());
        encrypted[encrypted.length - 1] ^= 1;
        FullBackupCrypto.decrypt(encrypted, "password".toCharArray());
    }
}
