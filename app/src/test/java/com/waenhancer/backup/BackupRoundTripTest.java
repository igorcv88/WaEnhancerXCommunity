package com.waenhancer.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.waenhancer.testing.FakeSharedPreferences;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;

/** Export/import round trip, allowlist enforcement and out-of-range normalization. */
public class BackupRoundTripTest {

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void exportKeepsAllowedKeysAndDropsEverythingElse() throws Exception {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        prefs.edit()
                .putBoolean("floating_bottom_bar", true)
                .putFloat("floating_bottom_bar_radius", 28f)
                .putString("wae_color_preset", "blue")
                .putStringSet("hidetabs",
                        new LinkedHashSet<>(Arrays.asList("status", "communities")))
                .putString("github_token", "ghp_shouldNeverBeExported")
                .putString("groq_api_key", "gsk_shouldNeverBeExported")
                .putString("some_internal_cache", "x")
                .commit();

        JSONObject root = new JSONObject(BackupCodec.exportSettings(prefs, "1.8.0-alpha1"));
        JSONObject settings = root.getJSONObject("settings");

        assertEquals(BackupCodec.SCHEMA_VERSION, root.getInt("schemaVersion"));
        assertTrue(settings.has("floating_bottom_bar"));
        assertTrue(settings.has("hidetabs"));
        assertFalse(settings.has("github_token"));
        // A user secret is described by the schema but is never exportable.
        assertFalse(settings.has("groq_api_key"));
        assertFalse(settings.has("some_internal_cache"));
    }

    @Test
    public void versionedRoundTripPreservesTypes() throws Exception {
        FakeSharedPreferences source = new FakeSharedPreferences();
        source.edit()
                .putBoolean("floating_bottom_bar", true)
                .putFloat("floating_bottom_bar_radius", 30f)
                .putString("wae_color_preset", "purple")
                .putStringSet("hidetabs", new LinkedHashSet<>(Arrays.asList("status")))
                .commit();

        String exported = BackupCodec.exportSettings(source, "1.8.0-alpha1");
        BackupCodec.ImportPlan plan = BackupCodec.parseAndValidate(bytes(exported));

        assertFalse(plan.legacy);
        assertEquals(Boolean.TRUE, plan.values.get("floating_bottom_bar"));
        assertEquals(30f, ((Number) plan.values.get("floating_bottom_bar_radius")).floatValue(), 0.001f);
        assertEquals("purple", plan.values.get("wae_color_preset"));
        assertTrue(plan.values.get("hidetabs") instanceof java.util.Set);
    }

    @Test
    public void outOfRangeNumbersAreClampedAndReported() throws Exception {
        String json = "{\"schemaVersion\":1,\"settings\":{"
                + "\"floating_bottom_bar_radius\":{\"type\":\"int\",\"value\":9999},"
                + "\"floating_bottom_bar_icon_size\":{\"type\":\"int\",\"value\":-5}}}";

        BackupCodec.ImportPlan plan = BackupCodec.parseAndValidate(bytes(json));

        assertEquals(64f, ((Number) plan.values.get("floating_bottom_bar_radius")).floatValue(), 0.001f);
        assertEquals(16f, ((Number) plan.values.get("floating_bottom_bar_icon_size")).floatValue(), 0.001f);
        assertTrue(plan.normalizedKeys.contains("floating_bottom_bar_radius"));
    }

    @Test
    public void legacyBooleanScrollHideMigratesToTheModeString() throws Exception {
        String json = "{\"floating_bottom_bar_scroll_hide\":true}";

        BackupCodec.ImportPlan plan = BackupCodec.parseAndValidate(bytes(json));

        assertEquals("tabs", plan.values.get("floating_bottom_bar_scroll_hide_mode"));
    }

    @Test
    public void oversizedBackupIsRejectedBeforeParsing() {
        byte[] huge = new byte[BackupCodec.MAX_BYTES + 1];
        Arrays.fill(huge, (byte) 'a');
        try {
            BackupCodec.parseAndValidate(huge);
            fail("Expected the size limit to reject the file");
        } catch (BackupCodec.BackupException expected) {
            assertTrue(expected.getMessage().contains("2 MB"));
        }
    }

    @Test
    public void versionedEntryWithAnUnsupportedTypeIsRejected() {
        String json = "{\"schemaVersion\":1,\"settings\":{"
                + "\"wae_color_preset\":{\"type\":\"widget\",\"value\":\"x\"}}}";
        try {
            BackupCodec.parseAndValidate(bytes(json));
            fail("Expected an unsupported type to fail");
        } catch (BackupCodec.BackupException expected) {
            assertTrue(expected.getMessage().contains("Invalid value"));
        }
    }

    @Test
    public void sensitiveKeysAreRejectedInTheVersionedFormatToo() throws Exception {
        String json = "{\"schemaVersion\":1,\"settings\":{"
                + "\"tasker_secret\":{\"type\":\"string\",\"value\":\"s3cret\"},"
                + "\"wae_color_preset\":{\"type\":\"string\",\"value\":\"red\"}}}";

        BackupCodec.ImportPlan plan = BackupCodec.parseAndValidate(bytes(json));

        assertTrue(plan.sensitiveKeys.contains("tasker_secret"));
        assertFalse(plan.values.containsKey("tasker_secret"));
        assertEquals("red", plan.values.get("wae_color_preset"));
    }
}
