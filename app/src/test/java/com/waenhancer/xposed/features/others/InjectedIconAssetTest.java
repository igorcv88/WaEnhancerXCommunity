package com.waenhancer.xposed.features.others;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Guards the drawables WaEnhancer inflates inside the WhatsApp process.
 *
 * <p>Those are loaded from the module's own {@code XModuleResources} with a {@code null} theme, so
 * a {@code ?attr/...} colour has nothing to resolve against and collapses to transparent. The icon
 * then inflates successfully and draws nothing — a blank space rather than a missing icon. Assets
 * on this path must therefore use literal colours and let the calling code apply the tint.
 */
public class InjectedIconAssetTest {

    /** Drawables reachable from a surface injected into the WhatsApp process. */
    private static final List<String> HOST_PROCESS_ICONS = Arrays.asList(
            "ic_waenhancer_entry",
            "ic_chevron_right_solid",
            "ic_general",
            "ic_privacy",
            "ic_media",
            "ic_palette",
            "deleted",
            "about",
            "ic_home_black_24dp",
            "ghost_enabled",
            "ghost_disabled",
            "airplane_enabled",
            "airplane_disabled",
            "eye_enabled",
            "eye_disabled",
            "refresh",
            "ic_contacts");

    private static File drawable(String name) {
        return new File("src/main/res/drawable/" + name + ".xml");
    }

    /** Markup only: an XML comment may legitimately mention the pattern it warns about. */
    private static String markupOf(String name) throws IOException {
        String xml = new String(Files.readAllBytes(drawable(name).toPath()),
                StandardCharsets.UTF_8);
        return xml.replaceAll("(?s)<!--.*?-->", "");
    }

    @Test
    public void hostProcessIconsExist() {
        for (String name : HOST_PROCESS_ICONS) {
            assertTrue("missing drawable: " + name, drawable(name).isFile());
        }
    }

    @Test
    public void hostProcessIconsDoNotDependOnAThemeAttribute() throws IOException {
        for (String name : HOST_PROCESS_ICONS) {
            assertFalse(name + " uses a theme attribute and would draw blank in the WhatsApp "
                    + "process, where it is inflated with a null theme",
                    markupOf(name).contains("?attr/"));
        }
    }

    /**
     * Every category and sub-screen must ship its own icon. Relying on the generic fallback means
     * Calls, Automation and Optimization all render the same glyph.
     */
    @Test
    public void everyRegistryScreenShipsItsOwnAsset() {
        for (String iconName : SettingsIconRegistry.mappings().values()) {
            assertTrue("SettingsIconRegistry offers '" + iconName
                    + "' but the module does not ship it, so the screen falls back to ic_general",
                    drawable(iconName).isFile());
        }
    }

    @Test
    public void everyRegistryIconIsHostSafe() throws IOException {
        for (String iconName : SettingsIconRegistry.mappings().values()) {
            assertFalse(iconName + " is offered by SettingsIconRegistry but depends on a theme "
                    + "attribute", markupOf(iconName).contains("?attr/"));
        }
    }
}
