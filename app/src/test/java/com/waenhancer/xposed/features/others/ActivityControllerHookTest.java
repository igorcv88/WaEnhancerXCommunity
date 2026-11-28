package com.waenhancer.xposed.features.others;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 2.26.46.82 (dexdump): StatusTemporalRecipientsActivity declares no methods and inherits
 * onCreate, so an exact hook on it threw NoSuchMethodError and failed ActivityController.
 */
public class ActivityControllerHookTest {
    @Test
    public void statusRecipientsOnCreateUsesTheNearestDeclaration() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/waenhancer/xposed/features/others/ActivityController.java")), StandardCharsets.UTF_8);
        String compact = source.replaceAll("\\s+", "");
        assertFalse(compact.contains("findAndHookMethod(\"com.whatsapp.status.audienceselector.StatusTemporalRecipientsActivity\""));
        assertTrue(compact.contains("HostHooks.hookNearest(XposedHelpers.findClass(\"com.whatsapp.status.audienceselector.StatusTemporalRecipientsActivity\",classLoader),\"onCreate\",Bundle.class"));
    }
}
