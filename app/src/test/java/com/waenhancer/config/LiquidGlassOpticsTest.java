package com.waenhancer.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.waenhancer.testing.FakeSharedPreferences;
import com.waenhancer.theme.GlassOptics;
import com.waenhancer.theme.LensModel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The experimental switches: off by default, every combination resolves to a meaningful one, the
 * presets do what they say, and every key crosses the preference bridge.
 */
public class LiquidGlassOpticsTest {

    private FakeSharedPreferences prefs;

    @Before
    public void setUp() {
        prefs = new FakeSharedPreferences();
    }

    @After
    public void tearDown() {
        GlassOptics.publish(GlassOptics.LEGACY);
    }

    /** Installing a build with the corrections changes nothing until asked. */
    @Test
    public void defaultIsTheOriginalRenderer() {
        assertSame(GlassOptics.LEGACY, LiquidGlassOptics.read(prefs));
        assertSame(GlassOptics.LEGACY, LiquidGlassOptics.read(null));
        assertFalse(GlassOptics.LEGACY.corrected);
    }

    /** The master switch alone selects every correction. */
    @Test
    public void masterSwitchAloneIsAllImprovements() {
        prefs.edit().putBoolean(LiquidGlassOptics.MASTER, true).apply();
        GlassOptics optics = LiquidGlassOptics.read(prefs);
        assertEquals(GlassOptics.ALL, optics);
        assertTrue(optics.geometry && optics.filtering && optics.adaptive && optics.color && optics.temporal);
        assertFalse(optics.clearProfile);
    }

    /** Every stored combination resolves; dependencies hold in all of them. */
    @Test
    public void everyCombinationRespectsItsDependencies() {
        String[] keys = {LiquidGlassOptics.MASTER, LiquidGlassOptics.GEOMETRY, LiquidGlassOptics.FILTERING,
                LiquidGlassOptics.ADAPTIVE, LiquidGlassOptics.COLOR, LiquidGlassOptics.TEMPORAL,
                LiquidGlassOptics.CLEAR};
        for (int mask = 0; mask < (1 << keys.length); mask++) {
            FakeSharedPreferences p = new FakeSharedPreferences();
            for (int i = 0; i < keys.length; i++) p.edit().putBoolean(keys[i], (mask & (1 << i)) != 0).apply();
            GlassOptics o = LiquidGlassOptics.read(p);
            boolean master = (mask & 1) != 0;
            if (!master) {
                assertSame("mask " + mask, GlassOptics.LEGACY, o);
                continue;
            }
            assertTrue(o.corrected);
            assertEquals((mask & 2) != 0, o.geometry);
            assertEquals((mask & 4) != 0, o.filtering);
            assertEquals((mask & 16) != 0, o.color);
            assertEquals((mask & 32) != 0, o.temporal);
            // Adaptive contrast measures the filtered backdrop; Clear relies on the protection.
            assertEquals((mask & 8) != 0 && o.filtering, o.adaptive);
            assertEquals((mask & 64) != 0 && o.adaptive, o.clearProfile);
            if (o.adaptive) assertTrue(o.filtering);
            if (o.clearProfile) assertTrue(o.adaptive);
        }
    }

    @Test
    public void presetsSetAndClearTheMasterSwitch() {
        prefs.edit().putBoolean(LiquidGlassOptics.FILTERING, false)
                .putString(LiquidGlassOptics.DEBUG, "grid").apply();
        LiquidGlassOptics.applyAllImprovements(prefs);
        assertEquals(GlassOptics.ALL, LiquidGlassOptics.read(prefs));
        LiquidGlassOptics.applyOriginal(prefs);
        assertSame(GlassOptics.LEGACY, LiquidGlassOptics.read(prefs));
        // Group choices survive the round trip to Original.
        prefs.edit().putBoolean(LiquidGlassOptics.COLOR, false).apply();
        prefs.edit().putBoolean(LiquidGlassOptics.MASTER, true).apply();
        assertFalse(LiquidGlassOptics.read(prefs).color);
    }

    /** Values that arrive as another type across the bridge must not take the hook down. */
    @Test
    public void malformedValuesFallBackToDefaults() {
        prefs.edit().putString(LiquidGlassOptics.MASTER, "true")
                .putInt(LiquidGlassOptics.GEOMETRY, 7)
                .putString(LiquidGlassOptics.DISPLACEMENT, "not a number")
                .putString(LiquidGlassOptics.DEBUG, "nonsense").apply();
        GlassOptics o = LiquidGlassOptics.read(prefs);
        assertTrue(o.corrected);
        assertTrue(o.geometry);
        assertEquals(LensModel.DEFAULT_DISPLACEMENT, o.displacement, 0f);
        assertEquals(GlassOptics.Debug.NONE, o.debug);
    }

    /** The developer amount is clamped to the stable range; the cap cannot be tuned past. */
    @Test
    public void displacementIsClampedToTheStableRange() {
        prefs.edit().putBoolean(LiquidGlassOptics.MASTER, true)
                .putFloat(LiquidGlassOptics.DISPLACEMENT, 0.9f).apply();
        assertEquals(LensModel.MAX_EFFECTIVE, LiquidGlassOptics.read(prefs).displacement, 0f);
        prefs.edit().putFloat(LiquidGlassOptics.DISPLACEMENT, 0.01f).apply();
        assertEquals(LensModel.MIN_DISPLACEMENT, LiquidGlassOptics.read(prefs).displacement, 0f);
    }

    @Test
    public void debugViewsParse() {
        for (GlassOptics.Debug debug : GlassOptics.Debug.values()) {
            assertEquals(debug, GlassOptics.Debug.from(debug.key()));
        }
        assertEquals(GlassOptics.Debug.NONE, GlassOptics.Debug.from(null));
    }

    /** Publishing reports a change only when the switches differ, so surfaces rebuild once. */
    @Test
    public void publishReportsChangesOnce() {
        GlassOptics.publish(GlassOptics.LEGACY);
        assertTrue(GlassOptics.publish(GlassOptics.ALL));
        assertFalse(GlassOptics.publish(GlassOptics.ALL));
        assertEquals(GlassOptics.ALL, GlassOptics.current());
        assertTrue(GlassOptics.publish(null));
        assertSame(GlassOptics.LEGACY, GlassOptics.current());
    }

    /** The hook reads these inside WhatsApp, so they must be public schema entries. */
    @Test
    public void everyKeyCrossesTheBridge() {
        String[] keys = {LiquidGlassOptics.MASTER, LiquidGlassOptics.GEOMETRY, LiquidGlassOptics.FILTERING,
                LiquidGlassOptics.ADAPTIVE, LiquidGlassOptics.COLOR, LiquidGlassOptics.TEMPORAL,
                LiquidGlassOptics.CLEAR, LiquidGlassOptics.DEBUG, LiquidGlassOptics.DISPLACEMENT};
        for (String key : keys) {
            PreferenceSchema.Entry entry = PreferenceSchema.entry(key);
            assertTrue(key + " missing from the schema", entry != null);
            assertEquals(key, PreferenceSchema.Store.PUBLIC, entry.store);
            assertTrue(key + " must be exportable", PreferenceSchema.isExportable(key));
        }
    }
}
