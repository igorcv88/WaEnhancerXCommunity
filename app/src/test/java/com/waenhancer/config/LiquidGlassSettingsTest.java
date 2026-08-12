package com.waenhancer.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.waenhancer.testing.FakeSharedPreferences;
import com.waenhancer.theme.GlassSpec;

import org.junit.Before;
import org.junit.Test;

/**
 * The one place the theme and the bar's style picker have to agree.
 *
 * <p>The bar's row on the Liquid Glass page is not a preference of its own — it is a second view
 * onto {@code floating_bottom_bar_glass_variant}. Two controls over one piece of state is how a
 * settings screen starts contradicting itself, and the contradiction is invisible until a user
 * opens both screens. These are the assertions that stand in for that.</p>
 */
public class LiquidGlassSettingsTest {

    private FakeSharedPreferences prefs;

    @Before
    public void setUp() {
        prefs = new FakeSharedPreferences();
    }

    private String variant() {
        return prefs.getString(LiquidGlassSettings.BAR_VARIANT, null);
    }

    // ---- the bar row ---------------------------------------------------------------------

    /** Switching the theme on for the bar is choosing the Liquid style, not a parallel flag. */
    @Test
    public void turningTheBarOnSelectsTheLiquidStyle() {
        LiquidGlassSettings.setBarLiquid(prefs, true);

        assertEquals(GlassSpec.Variant.LIQUID.key(), variant());
        assertTrue(LiquidGlassSettings.isBarLiquid(prefs));
    }

    /** A style is not visible while the effect that draws it is off, so both move together. */
    @Test
    public void turningTheBarOnAlsoTurnsItsGlassOn() {
        prefs.edit().putBoolean(LiquidGlassSettings.BAR_GLASS, false).apply();

        LiquidGlassSettings.setBarLiquid(prefs, true);

        assertTrue(prefs.getBoolean(LiquidGlassSettings.BAR_GLASS, false));
    }

    /**
     * Opacity and variant are not independent: Liquid at the opacity Frost wants is an opaque bar
     * with a lit edge. Selecting the style adopts the opacity it was designed around, which is
     * exactly what the dropdown in the bar editor does.
     */
    @Test
    public void turningTheBarOnAdoptsTheStylesOwnOpacity() {
        prefs.edit().putFloat(LiquidGlassSettings.BAR_OPACITY, 80f).apply();

        LiquidGlassSettings.setBarLiquid(prefs, true);

        assertEquals(GlassSpec.Variant.LIQUID.recommendedOpacityPercent(),
                prefs.getFloat(LiquidGlassSettings.BAR_OPACITY, -1f), 0.001f);
    }

    /** Switching off restores the style in use before, rather than discarding the user's choice. */
    @Test
    public void turningTheBarOffRestoresThePreviousStyle() {
        prefs.edit().putString(LiquidGlassSettings.BAR_VARIANT,
                GlassSpec.Variant.FROST.key()).apply();

        LiquidGlassSettings.setBarLiquid(prefs, true);
        LiquidGlassSettings.setBarLiquid(prefs, false);

        assertEquals(GlassSpec.Variant.FROST.key(), variant());
        assertFalse(LiquidGlassSettings.isBarLiquid(prefs));
    }

    /** With nothing remembered, off means the engine's default rather than Liquid again. */
    @Test
    public void turningTheBarOffWithNothingRememberedFallsBackToTheDefault() {
        LiquidGlassSettings.setBarLiquid(prefs, false);

        assertEquals(GlassSpec.Variant.ADVANCED.key(), variant());
        assertFalse(LiquidGlassSettings.isBarLiquid(prefs));
    }

    /**
     * Turning on twice must not record Liquid as the style to go back to, or the off switch would
     * do nothing at all.
     */
    @Test
    public void turningTheBarOnTwiceDoesNotForgetWhereToGoBackTo() {
        prefs.edit().putString(LiquidGlassSettings.BAR_VARIANT,
                GlassSpec.Variant.CLEAR.key()).apply();

        LiquidGlassSettings.setBarLiquid(prefs, true);
        LiquidGlassSettings.setBarLiquid(prefs, true);
        LiquidGlassSettings.setBarLiquid(prefs, false);

        assertEquals(GlassSpec.Variant.CLEAR.key(), variant());
    }

    /** A style chosen in the bar editor is the "before" the theme's off switch has to restore. */
    @Test
    public void aStyleChosenInTheBarEditorBecomesTheOneRestored() {
        LiquidGlassSettings.setBarLiquid(prefs, true);
        LiquidGlassSettings.rememberBarVariant(prefs, GlassSpec.Variant.STABLE.key());

        LiquidGlassSettings.setBarLiquid(prefs, false);

        assertEquals(GlassSpec.Variant.STABLE.key(), variant());
    }

    /** Picking Liquid in the bar editor must not overwrite what to go back to with Liquid. */
    @Test
    public void pickingLiquidInTheBarEditorIsNotRecordedAsSomethingToRestore() {
        prefs.edit().putString(LiquidGlassSettings.BAR_PREVIOUS_VARIANT,
                GlassSpec.Variant.FROST.key()).apply();

        LiquidGlassSettings.rememberBarVariant(prefs, GlassSpec.Variant.LIQUID.key());

        assertEquals(GlassSpec.Variant.FROST.key(),
                prefs.getString(LiquidGlassSettings.BAR_PREVIOUS_VARIANT, null));
    }

    /** Liquid chosen in the bar editor shows up as on here, without this page being told. */
    @Test
    public void theBarRowReflectsAStylePickedElsewhere() {
        prefs.edit().putString(LiquidGlassSettings.BAR_VARIANT,
                GlassSpec.Variant.LIQUID.key()).apply();

        assertTrue(LiquidGlassSettings.isBarLiquid(prefs));
    }

    /** Liquid selected but the bar's glass switched off is not glass, whatever the style says. */
    @Test
    public void theBarRowIsOffWhenTheBarsGlassIsOff() {
        LiquidGlassSettings.setBarLiquid(prefs, true);
        prefs.edit().putBoolean(LiquidGlassSettings.BAR_GLASS, false).apply();

        assertFalse(LiquidGlassSettings.isBarLiquid(prefs));
    }

    // ---- the surfaces that are plain booleans ---------------------------------------------

    /** Injecting into someone else's view tree is opt-in. */
    @Test
    public void theScrollButtonIsOffUntilAskedFor() {
        assertFalse(LiquidGlassSettings.isScrollButtonEnabled(prefs));

        prefs.edit().putBoolean(LiquidGlassSettings.SCROLL_BUTTON, true).apply();

        assertTrue(LiquidGlassSettings.isScrollButtonEnabled(prefs));
    }

    // ---- the material ----------------------------------------------------------------------

    /** One opacity for every themed surface, read through the schema that clamps it. */
    @Test
    public void theMaterialsOpacityIsTheBarsSliderValue() {
        prefs.edit().putFloat(LiquidGlassSettings.BAR_OPACITY, 45f).apply();

        assertEquals(45f, LiquidGlassSettings.opacityPercent(prefs), 0.001f);
    }

    /** An out-of-range figure is clamped rather than passed through to a shader uniform. */
    @Test
    public void anImpossibleOpacityIsClamped() {
        prefs.edit().putFloat(LiquidGlassSettings.BAR_OPACITY, 900f).apply();

        assertEquals(100f, LiquidGlassSettings.opacityPercent(prefs), 0.001f);
    }

    /** Nothing here may throw into a hooked layout pass, however odd the stored value. */
    @Test
    public void absentPreferencesAnswerRatherThanThrow() {
        assertFalse(LiquidGlassSettings.isBarLiquid(null));
        assertFalse(LiquidGlassSettings.isScrollButtonEnabled(null));
        assertEquals(GlassSpec.Variant.LIQUID.recommendedOpacityPercent(),
                LiquidGlassSettings.opacityPercent(null), 0.001f);
        LiquidGlassSettings.setBarLiquid(null, true);
        LiquidGlassSettings.rememberBarVariant(null, "liquid");
    }
}
