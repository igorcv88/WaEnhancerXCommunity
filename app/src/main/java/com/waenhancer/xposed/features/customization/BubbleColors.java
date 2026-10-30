package com.waenhancer.xposed.features.customization;


import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

import com.waenhancer.xposed.core.Feature;
import com.waenhancer.xposed.core.devkit.Unobfuscator;
import com.waenhancer.xposed.utils.DesignUtils;
import com.waenhancer.xposed.utils.Utils;
import com.waenhancer.theme.CssSafetyManager;

import java.util.Objects;
import java.util.Properties;

import de.robv.android.xposed.XC_MethodHook;
import android.content.SharedPreferences;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

public class BubbleColors extends Feature {
    /** Shared colour source for the native bubbles and the optional glass material. */
    static final class Palette {
        final int incoming, outgoing;
        final boolean enabled;
        Palette(int incoming, int outgoing, boolean enabled) {
            this.incoming = incoming; this.outgoing = outgoing; this.enabled = enabled;
        }
        int forSide(boolean outgoingSide) { return outgoingSide ? outgoing : incoming; }
    }

    static Palette resolvePalette(SharedPreferences prefs) {
        boolean enabled = prefs.getBoolean("bubble_color", false);
        Properties properties = Utils.getPropertiesFromText(
                CssSafetyManager.effectiveCss(prefs), prefs.getBoolean("custom_filters", false));
        boolean css = Objects.equals(properties.getProperty("bubble_colors"), "true");
        if (!enabled && !css) return new Palette(0, 0, false);
        try {
            int left = enabled ? prefs.getInt("bubble_left", 0)
                    : Color.parseColor(DesignUtils.checkSystemColor(properties.getProperty("bubble_left", "#00000000")));
            int right = enabled ? prefs.getInt("bubble_right", 0)
                    : Color.parseColor(DesignUtils.checkSystemColor(properties.getProperty("bubble_right", "#00000000")));
            return new Palette(left, right, true);
        } catch (RuntimeException error) {
            return new Palette(0, 0, false);
        }
    }

    public BubbleColors(ClassLoader loader, SharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override
    public void doHook() throws Exception {

        Palette palette = resolvePalette(prefs);
        if (!palette.enabled)
            return;

        boolean bubbleColor = prefs.getBoolean("bubble_color", false);
        int bubbleLeftColor = palette.incoming;
        int bubbleRightColor = palette.outgoing;

        var dateWrapper = Unobfuscator.loadBallonDateDrawable(classLoader);

        XposedBridge.hookMethod(dateWrapper, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                var drawable = (Drawable) param.getResult();
                if (drawable == null)return;
                var position = (int) param.args[0];
                if (position == 3) {
                    // If bubble color is not enabled and color is 0, skip
                    if (!bubbleColor && bubbleRightColor == 0) {
                        return;
                    }
                    // If color is 0, clear the filter; otherwise apply the color
                    if (bubbleRightColor == 0) {
                        drawable.setColorFilter(null);
                    } else {
                        drawable.setColorFilter(new PorterDuffColorFilter(bubbleRightColor, PorterDuff.Mode.SRC_IN));
                    }
                } else {
                    // If bubble color is not enabled and color is 0, skip
                    if (!bubbleColor && bubbleLeftColor == 0) {
                        return;
                    }
                    // If color is 0, clear the filter; otherwise apply the color
                    if (bubbleLeftColor == 0) {
                        drawable.setColorFilter(null);
                    } else {
                        drawable.setColorFilter(new PorterDuffColorFilter(bubbleLeftColor, PorterDuff.Mode.SRC_IN));
                    }
                }
            }
        });

        var babblon = Unobfuscator.loadBallonBorderDrawable(classLoader);
        XposedBridge.hookMethod(babblon, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                var drawable = (Drawable) param.getResult();
                if (drawable == null)return;
                var position = (int) param.args[1];
                if (position == 3) {
                    // If bubble color is not enabled and color is 0, skip
                    if (!bubbleColor && bubbleRightColor == 0) {
                        return;
                    }
                    // If color is 0, clear the filter; otherwise apply the color
                    if (bubbleRightColor == 0) {
                        drawable.setColorFilter(null);
                    } else {
                        drawable.setColorFilter(new PorterDuffColorFilter(bubbleRightColor, PorterDuff.Mode.SRC_IN));
                    }
                } else {
                    // If bubble color is not enabled and color is 0, skip
                    if (!bubbleColor && bubbleLeftColor == 0) {
                        return;
                    }
                    // If color is 0, clear the filter; otherwise apply the color
                    if (bubbleLeftColor == 0) {
                        drawable.setColorFilter(null);
                    } else {
                        drawable.setColorFilter(new PorterDuffColorFilter(bubbleLeftColor, PorterDuff.Mode.SRC_IN));
                    }
                }
            }
        });


        var bubbleDrawableMethod = Unobfuscator.loadBubbleDrawableMethod(classLoader);

        XposedBridge.hookMethod(bubbleDrawableMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                var position = (int) param.args[0];
                var draw = (Drawable) param.getResult();
                var right = position == 3;
                if (right) {
                    // If bubble color is not enabled and color is 0, skip
                    if (!bubbleColor && bubbleRightColor == 0) {
                        return;
                    }
                    // If color is 0, clear the filter; otherwise apply the color
                    if (bubbleRightColor == 0) {
                        draw.setColorFilter(null);
                    } else {
                        draw.setColorFilter(new PorterDuffColorFilter(bubbleRightColor, PorterDuff.Mode.SRC_IN));
                    }
                } else {
                    // If bubble color is not enabled and color is 0, skip
                    if (!bubbleColor && bubbleLeftColor == 0) {
                        return;
                    }
                    // If color is 0, clear the filter; otherwise apply the color
                    if (bubbleLeftColor == 0) {
                        draw.setColorFilter(null);
                    } else {
                        draw.setColorFilter(new PorterDuffColorFilter(bubbleLeftColor, PorterDuff.Mode.SRC_IN));
                    }
                }
            }
        });

    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Bubble Colors";
    }
}
