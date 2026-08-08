package com.waenhancer.config;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

/**
 * Guards against a control that exists in the editor but nothing reads.
 *
 * <p>Icon size, text size, icon-to-label spacing and the minimal FAB side margin were all offered
 * as sliders, persisted, and included in the backup allowlist, while no code in the hooked process
 * ever read them — so moving them changed nothing on the real bar.
 */
public class BottomBarControlWiringTest {

    private static final File RUNTIME = new File(
            "src/main/java/com/waenhancer/xposed/features/customization/FloatingBottomBar.java");
    private static final File EDITOR = new File(
            "src/main/java/com/waenhancer/activities/BottomBarCustomizationActivity.java");

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void everySchemaControlIsReadByTheHookedBar() throws IOException {
        String runtime = read(RUNTIME);
        for (String key : BottomBarPreferenceSchema.all().keySet()) {
            assertTrue("'" + key + "' is offered by the schema but FloatingBottomBar never reads "
                    + "it, so the control would not affect the real bar",
                    runtime.contains('"' + key + '"'));
        }
    }

    @Test
    public void everySchemaControlIsExposedByTheEditor() throws IOException {
        String editor = read(EDITOR);
        for (String key : BottomBarPreferenceSchema.all().keySet()) {
            assertTrue("'" + key + "' exists in the schema but the editor exposes no control "
                    + "for it", editor.contains('"' + key + '"'));
        }
    }

    @Test
    public void everySchemaControlIsResolvedByThePreviewModel() throws IOException {
        String model = read(new File(
                "src/main/java/com/waenhancer/config/BottomBarPreviewModel.java"));
        for (String key : BottomBarPreferenceSchema.all().keySet()) {
            assertTrue("'" + key + "' is not resolved by BottomBarPreviewModel, so the preview "
                    + "would not react to it", model.contains('"' + key + '"'));
        }
    }
}
