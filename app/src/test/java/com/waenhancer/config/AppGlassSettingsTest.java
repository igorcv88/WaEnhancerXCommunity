package com.waenhancer.config;

import com.waenhancer.testing.FakeSharedPreferences;
import org.junit.Test;
import static org.junit.Assert.*;

public class AppGlassSettingsTest {
    @Test public void expandingTheThemeDoesNotEnableAnythingOnUpgrade() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        prefs.edit().putBoolean(LiquidGlassSettings.BAR_GLASS, true)
                .putString(LiquidGlassSettings.BAR_VARIANT, "liquid").apply();
        assertTrue(LiquidGlassSettings.isBarLiquid(prefs));
        assertFalse(LiquidGlassSettings.hasAppSurfaces(prefs));
        for (LiquidGlassSettings.Surface surface : LiquidGlassSettings.Surface.values()) {
            assertFalse(LiquidGlassSettings.isEnabled(prefs, surface));
        }
    }

    @Test public void corruptToggleDisablesOnlyItsSurface() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        prefs.edit().putString(LiquidGlassSettings.Surface.BUBBLES.key, "true")
                .putBoolean(LiquidGlassSettings.Surface.COMPOSER.key, true).apply();
        assertFalse(LiquidGlassSettings.isEnabled(prefs, LiquidGlassSettings.Surface.BUBBLES));
        assertTrue(LiquidGlassSettings.isEnabled(prefs, LiquidGlassSettings.Surface.COMPOSER));
        assertTrue(LiquidGlassSettings.hasAppSurfaces(prefs));
    }

    @Test public void unknownAndWholeScreenTargetsNeverBecomeGlass() {
        assertNull(GlassSurfaceCatalog.surface("root_view"));
        assertNull(GlassSurfaceCatalog.surface("conversations_coordinator_layout"));
        assertNull(GlassSurfaceCatalog.surface("entry"));
        assertNull(GlassSurfaceCatalog.surface("scroll_bottom"));
        assertNull(GlassSurfaceCatalog.surface("bottom_nav_container"));
        assertNull(GlassSurfaceCatalog.surface("renamed_in_future_whatsapp"));
        assertNull(GlassSurfaceCatalog.surface(null));
    }
}
