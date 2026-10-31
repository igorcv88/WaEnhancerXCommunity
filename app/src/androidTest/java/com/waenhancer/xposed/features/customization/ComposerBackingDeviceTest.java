package com.waenhancer.xposed.features.customization;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.annotation.UiThreadTest;
import org.junit.Test;
import static org.junit.Assert.*;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.SeekBar;

/** Android drawable/state regression tests. Run on an emulator/device; not a WhatsApp visual test. */
public class ComposerBackingDeviceTest {
    private Context getContext() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    @Test @UiThreadTest
    public void testRippleContentOnlyAndNativeActions() {
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(Color.BLACK);
        ColorDrawable mask = new ColorDrawable(Color.WHITE);
        RippleDrawable ripple = new RippleDrawable(ColorStateList.valueOf(Color.WHITE), fill, mask);
        View button = new View(getContext());
        button.setBackground(ripple);
        button.setPadding(3, 4, 5, 6);
        int[] clicks = {0};
        button.setOnClickListener(v -> clicks[0]++);
        ComposerBacking backing = new ComposerBacking();
        backing.clear(button, false);
        assertTrue(button.getBackground() instanceof RippleDrawable);
        RippleDrawable copy = (RippleDrawable) button.getBackground();
        assertNotSame(ripple, copy);
        assertEquals(255, fill.getAlpha());
        for (int i = 0; i < copy.getNumberOfLayers(); i++) {
            assertEquals(copy.getId(i) == android.R.id.mask ? 255 : 0, copy.getDrawable(i).getAlpha());
        }
        button.setPressed(true);
        assertTrue(button.isPressed());
        button.performClick();
        assertEquals(1, clicks[0]);
        assertEquals(3, button.getPaddingLeft());
        assertEquals(6, button.getPaddingBottom());
        backing.restore();
        assertSame(ripple, button.getBackground());
    }
    @Test @UiThreadTest
    public void testImplicitRippleMaskSurvivesFillRemoval() {
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(Color.BLACK);
        fill.setCornerRadius(20);
        RippleDrawable nativeRipple = new RippleDrawable(ColorStateList.valueOf(Color.WHITE), fill, null);
        RippleDrawable copy = (RippleDrawable) ComposerBacking.transparentFill(nativeRipple, false);
        assertNotNull(copy);
        assertNotNull(copy.findDrawableByLayerId(android.R.id.mask));
        android.graphics.drawable.LayerDrawable mask = (android.graphics.drawable.LayerDrawable)
                copy.findDrawableByLayerId(android.R.id.mask);
        assertEquals(255, mask.getDrawable(0).getAlpha());
        assertEquals(0, copy.getDrawable(0).getAlpha());
        assertEquals(255, fill.getAlpha());
    }
    @Test @UiThreadTest
    public void testNativeRebindForegroundTintAndConditionalRestore() {
        View input = new View(getContext());
        ColorDrawable old = new ColorDrawable(Color.BLACK), fresh = new ColorDrawable(Color.GRAY);
        input.setBackground(old);
        input.setForeground(new ColorDrawable(Color.BLACK));
        ColorStateList nativeTint = ColorStateList.valueOf(Color.GRAY);
        input.setBackgroundTintList(nativeTint);
        ComposerBacking backing = new ComposerBacking();
        backing.clear(input, true);
        assertNull(input.getBackgroundTintList());
        input.setBackground(fresh);
        ColorDrawable newForeground = new ColorDrawable(Color.GRAY);
        input.setForeground(newForeground);
        backing.clear(input, true);
        backing.restore();
        assertSame(fresh, input.getBackground());
        assertSame(newForeground, input.getForeground());
        assertSame(nativeTint, input.getBackgroundTintList());
        backing.clear(input, true);
        input.setBackground(old);
        input.setForeground(fresh);
        ColorStateList laterTint = ColorStateList.valueOf(Color.WHITE);
        input.setBackgroundTintList(laterTint);
        backing.restore();
        assertSame(old, input.getBackground());
        assertSame(fresh, input.getForeground());
        assertSame(laterTint, input.getBackgroundTintList());
    }
    @Test @UiThreadTest
    public void testExplicitNativeNullTintSupersedesOverride() {
        View input = new View(getContext());
        input.setBackground(new ColorDrawable(Color.BLACK));
        input.setBackgroundTintList(ColorStateList.valueOf(Color.GRAY));
        ComposerBacking backing = new ComposerBacking();
        backing.clear(input, true);
        input.setBackgroundTintList(null);
        backing.nativeAssignment(input, "tint");
        backing.restore();
        assertNull(input.getBackgroundTintList());
    }
    @Test @UiThreadTest
    public void testPlayerChildrenAndSeekRemainNative() {
        FrameLayout player = new FrameLayout(getContext());
        SeekBar wave = new SeekBar(getContext());
        player.addView(wave);
        player.setBackground(new ColorDrawable(Color.BLACK));
        ComposerBacking backing = new ComposerBacking();
        backing.clear(player, true);
        assertSame(wave, player.getChildAt(0));
        wave.setProgress(37);
        assertEquals(37, wave.getProgress());
        backing.restore();
        assertSame(wave, player.getChildAt(0));
    }
}
