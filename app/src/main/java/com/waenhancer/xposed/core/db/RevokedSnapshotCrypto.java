package com.waenhancer.xposed.core.db;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.security.KeyStore;
import java.nio.charset.StandardCharsets;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** UID-private, non-exportable key. Never regenerates an existing unreadable key. */
final class RevokedSnapshotCrypto {
    private static final String ALIAS = "waex-antirevoke-v1";
    private SecretKey key;

    private synchronized SecretKey key() throws Exception {
        if (key != null) return key;
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) {
            key = (SecretKey) store.getKey(ALIAS, null);
        } else {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            key = generator.generateKey();
        }
        if (key == null) throw new java.security.GeneralSecurityException("Snapshot key unavailable");
        return key;
    }

    void prepare() throws Exception { key(); }

    byte[] encrypt(byte[] plain, String identity) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        cipher.updateAAD(identity.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(plain);
        byte[] result = new byte[1 + 12 + encrypted.length];
        result[0] = 1;
        if (cipher.getIV().length != 12) throw new java.security.GeneralSecurityException("Unexpected IV");
        System.arraycopy(cipher.getIV(), 0, result, 1, 12);
        System.arraycopy(encrypted, 0, result, 13, encrypted.length);
        return result;
    }

    byte[] decrypt(byte[] encrypted, String identity) throws Exception {
        if (encrypted.length < 29 || encrypted[0] != 1)
            throw new java.security.GeneralSecurityException("Invalid snapshot envelope");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, encrypted, 1, 12));
        cipher.updateAAD(identity.getBytes(StandardCharsets.UTF_8));
        return cipher.doFinal(encrypted, 13, encrypted.length - 13);
    }
}
