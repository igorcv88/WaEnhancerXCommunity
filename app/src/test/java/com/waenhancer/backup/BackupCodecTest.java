package com.waenhancer.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class BackupCodecTest {

    @Test
    public void legacyBackupSkipsSensitiveAndUnknownKeys() throws Exception {
        String json = "{"
                + "\"floating_bottom_bar_radius\":96,"
                + "\"private_key\":\"secret\","
                + "\"future_unknown\":true"
                + "}";

        BackupCodec.ImportPlan plan = BackupCodec.parseAndValidate(
                json.getBytes(StandardCharsets.UTF_8));

        assertTrue(plan.legacy);
        assertEquals(64f, ((Number) plan.values.get(
                "floating_bottom_bar_radius")).floatValue(), 0.001f);
        assertTrue(plan.sensitiveKeys.contains("private_key"));
        assertTrue(plan.unknownKeys.contains("future_unknown"));
        assertFalse(plan.values.containsKey("private_key"));
    }

    @Test
    public void versionedBackupRejectsUnknownSchema() {
        String json = "{\"schemaVersion\":99,\"settings\":{}}";
        try {
            BackupCodec.parseAndValidate(json.getBytes(StandardCharsets.UTF_8));
            fail("Expected unsupported schema to fail");
        } catch (BackupCodec.BackupException expected) {
            assertTrue(expected.getMessage().contains("Unsupported backup schema"));
        }
    }

    @Test
    public void invalidJsonFailsBeforeAnyImportPlanExists() {
        try {
            BackupCodec.parseAndValidate("{".getBytes(StandardCharsets.UTF_8));
            fail("Expected malformed JSON to fail");
        } catch (BackupCodec.BackupException expected) {
            assertTrue(expected.getMessage().contains("valid JSON"));
        }
    }

    @Test
    public void knownSecretsAreAlwaysSensitive() {
        assertTrue(BackupCodec.isSensitive("tasker_secret"));
        assertTrue(BackupCodec.isSensitive("encrypted_config"));
        assertTrue(BackupCodec.isSensitive("pro_plugin_path"));
        assertFalse(BackupCodec.isSensitive("floating_bottom_bar_radius"));
    }
}
