package com.waenhancer.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.waenhancer.testing.FakeSharedPreferences;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

/**
 * The migration is the most dangerous change in this block, so its whole sequence is exercised
 * here: snapshot, copy, verify, refuse, remove, roll back, and survive both upgrade and
 * downgrade.
 */
public class PreferenceMigrationTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private FakeSharedPreferences publicStore;
    private FakeSharedPreferences privateStore;
    private File snapshots;

    @Before
    public void setUp() throws Exception {
        publicStore = new FakeSharedPreferences();
        privateStore = new FakeSharedPreferences();
        snapshots = folder.newFolder("migration_snapshots");
    }

    private void seedSecrets() {
        publicStore.edit()
                .putString("groq_api_key", "gsk_secret")
                .putString("assemblyai_key", "aai_secret")
                .putBoolean("floating_bottom_bar", true)
                .commit();
    }

    @Test
    public void copyMovesPrivateValuesWithoutRemovingThePublicCopy() {
        seedSecrets();

        PreferenceMigration.Result result =
                PreferenceMigration.copyPrivateValues(publicStore, privateStore, snapshots);

        assertTrue(result.error, result.isSuccess());
        assertTrue(result.copied.contains("groq_api_key"));
        assertEquals("gsk_secret", privateStore.getString("groq_api_key", null));
        // Additive: the public value is still there, so a downgrade still works.
        assertEquals("gsk_secret", publicStore.getString("groq_api_key", null));
        assertTrue(result.removed.isEmpty());
    }

    @Test
    public void copyWritesASnapshotBeforeTouchingAnything() {
        seedSecrets();

        PreferenceMigration.copyPrivateValues(publicStore, privateStore, snapshots);

        File[] files = snapshots.listFiles();
        assertNotNull(files);
        assertEquals(1, files.length);
    }

    @Test
    public void copyIsIdempotent() {
        seedSecrets();

        PreferenceMigration.copyPrivateValues(publicStore, privateStore, snapshots);
        PreferenceMigration.Result second =
                PreferenceMigration.copyPrivateValues(publicStore, privateStore, snapshots);

        assertTrue(second.isSuccess());
        assertTrue("a repeat run must copy nothing", second.copied.isEmpty());
    }

    @Test
    public void publicSettingsAreNeverMovedIntoThePrivateStore() {
        seedSecrets();

        PreferenceMigration.copyPrivateValues(publicStore, privateStore, snapshots);

        assertFalse("a public setting must stay in the public store only",
                privateStore.contains("floating_bottom_bar"));
    }

    @Test
    public void secretsAreNotRemovedWhileTheHookedProcessCannotReadThem() {
        seedSecrets();
        PreferenceMigration.copyPrivateValues(publicStore, privateStore, snapshots);

        PreferenceMigration.Result result = PreferenceMigration.removeMigratedSecrets(
                publicStore, privateStore, snapshots, false);

        assertNotNull("removal must be refused", result.error);
        assertEquals("gsk_secret", publicStore.getString("groq_api_key", null));
    }

    @Test
    public void secretsAreNotRemovedBeforeTheCopyIsVerified() {
        seedSecrets();

        PreferenceMigration.Result result = PreferenceMigration.removeMigratedSecrets(
                publicStore, privateStore, snapshots, true);

        assertNotNull(result.error);
        assertEquals("gsk_secret", publicStore.getString("groq_api_key", null));
    }

    @Test
    public void secretsLeaveThePublicStoreOnlyAfterAVerifiedCopyAndAWorkingBridge() {
        seedSecrets();
        PreferenceMigration.copyPrivateValues(publicStore, privateStore, snapshots);

        PreferenceMigration.Result result = PreferenceMigration.removeMigratedSecrets(
                publicStore, privateStore, snapshots, true);

        assertNull(result.error);
        assertTrue(result.removed.contains("groq_api_key"));
        assertFalse("no secret may remain in the world-readable store",
                publicStore.contains("groq_api_key"));
        assertFalse(publicStore.contains("assemblyai_key"));
        // The value itself is preserved, just no longer world-readable.
        assertEquals("gsk_secret", privateStore.getString("groq_api_key", null));
    }

    @Test
    public void removalIsRefusedWhenThePrivateCopyDisagrees() {
        seedSecrets();
        PreferenceMigration.copyPrivateValues(publicStore, privateStore, snapshots);
        privateStore.edit().putString("groq_api_key", "tampered").commit();

        PreferenceMigration.Result result = PreferenceMigration.removeMigratedSecrets(
                publicStore, privateStore, snapshots, true);

        assertNotNull(result.error);
        assertTrue(result.mismatched.contains("groq_api_key"));
        assertEquals("gsk_secret", publicStore.getString("groq_api_key", null));
    }

    @Test
    public void rollbackRestoresTheStoreFromASnapshot() {
        seedSecrets();
        PreferenceMigration.copyPrivateValues(publicStore, privateStore, snapshots);
        PreferenceMigration.removeMigratedSecrets(publicStore, privateStore, snapshots, true);
        assertFalse(publicStore.contains("groq_api_key"));

        File[] files = snapshots.listFiles();
        assertNotNull(files);
        java.util.Arrays.sort(files,
                (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

        assertTrue(PreferenceMigration.rollback(publicStore, files[0]));
        assertEquals("gsk_secret", publicStore.getString("groq_api_key", null));
    }

    @Test
    public void rollbackNeverClearsValuesTheSnapshotDoesNotMention() {
        seedSecrets();
        PreferenceMigration.copyPrivateValues(publicStore, privateStore, snapshots);
        publicStore.edit().putBoolean("hideread", true).commit();

        File[] files = snapshots.listFiles();
        assertNotNull(files);
        assertTrue(PreferenceMigration.rollback(publicStore, files[0]));

        assertTrue("a setting made after the snapshot must survive a rollback",
                publicStore.getBoolean("hideread", false));
    }

    @Test
    public void aCorruptSnapshotIsRejectedInsteadOfDamagingTheStore() throws Exception {
        seedSecrets();
        File corrupt = folder.newFile("corrupt.json");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(corrupt)) {
            out.write("{ not json".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        assertFalse(PreferenceMigration.rollback(publicStore, corrupt));
        assertEquals("gsk_secret", publicStore.getString("groq_api_key", null));
    }

    @Test
    public void upgradeFromAStoreWithNoSecretsIsANoOp() {
        publicStore.edit().putBoolean("floating_bottom_bar", true).commit();

        PreferenceMigration.Result result =
                PreferenceMigration.copyPrivateValues(publicStore, privateStore, snapshots);

        assertTrue(result.isSuccess());
        assertTrue(result.copied.isEmpty());
        File[] files = snapshots.listFiles();
        assertTrue("nothing to migrate means nothing to snapshot",
                files == null || files.length == 0);
    }

    @Test
    public void downgradeStillFindsSettingsWhileTheCopyIsAdditive() {
        seedSecrets();
        PreferenceMigration.copyPrivateValues(publicStore, privateStore, snapshots);

        // A build that knows only the public store reads it directly.
        assertEquals("gsk_secret", publicStore.getString("groq_api_key", null));
        assertTrue(publicStore.getBoolean("floating_bottom_bar", false));
    }
}
