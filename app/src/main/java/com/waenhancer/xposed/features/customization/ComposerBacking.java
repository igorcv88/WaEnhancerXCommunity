package com.waenhancer.xposed.features.customization;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import com.waenhancer.theme.OwnedProperty;
import java.util.ArrayList;
import java.util.WeakHashMap;

/** Only called for a resolved backing owner while its existing pane is live. */
final class ComposerBacking {
    private final boolean voice;
    private final java.util.Set<View> resolved = java.util.Collections.newSetFromMap(new WeakHashMap<>());
    ComposerBacking() { this(false); }
    ComposerBacking(boolean voice) { this.voice = voice; }
    private final WeakHashMap<View, OwnedProperty<Drawable>> backgrounds = new WeakHashMap<>();
    private final WeakHashMap<View, OwnedProperty<Drawable>> foregrounds = new WeakHashMap<>();
    private final WeakHashMap<View, OwnedProperty<ColorStateList>> tints = new WeakHashMap<>();
    void nativeAssignment(View view, String property) {
        if ("background".equals(property)) backgrounds.remove(view);
        else if ("foreground".equals(property)) foregrounds.remove(view);
        else if ("tint".equals(property)) tints.remove(view);
    }
    int size() { return backgrounds.size() + foregrounds.size(); }
    boolean owns(View view) { return resolved.contains(view); }

    void clear(View view, boolean structural) {
        clearSlot(view, false, structural);
        // Never erase an unknown foreground (it may draw controls or progress).
        String role = com.waenhancer.theme.GlassProbe.role(view);
        if (!role.equals("voice_note_draft_quoted_message_frame") && !role.equals("composer_quoted_message_frame"))
            clearSlot(view, true, false); // The identified reply foreground has one dedicated owner.
    }
    private void clearSlot(View view, boolean foreground, boolean structural) {
        WeakHashMap<View, OwnedProperty<Drawable>> map = foreground ? foregrounds : backgrounds;
        Drawable current = foreground ? view.getForeground() : view.getBackground();
        OwnedProperty<Drawable> entry = map.get(view);
        if (entry != null && entry.owns(current)) {
            if (!foreground) clearTint(view);
            return;
        }
        if (current == null) return;
        Drawable replacement = transparentFill(current, structural);
        if (replacement == null) return;
        resolved.add(view);
        if (entry == null) { entry = new OwnedProperty<>(); map.put(view, entry); }
        Drawable nativeValue = current instanceof com.waenhancer.theme.GlassMaterialDrawable
                ? ((com.waenhancer.theme.GlassMaterialDrawable) current).original() : current;
        entry.install(nativeValue, replacement);
        replacement.setState(current.getState());
        replacement.setLevel(current.getLevel());
        android.graphics.Rect hotspot = new android.graphics.Rect();
        current.getHotspotBounds(hotspot);
        replacement.setHotspotBounds(hotspot.left, hotspot.top, hotspot.right, hotspot.bottom);
        if (foreground) view.setForeground(replacement);
        else {
            setBackground(view, replacement);
            clearTint(view);
        }
        if (com.waenhancer.theme.GlassProbe.enabled()) com.waenhancer.theme.GlassProbe.event(
                voice ? "VOICE_BG_CLEARED" : "INPUT_BG_CLEARED", view,
                "slot=" + (foreground ? "foreground" : "background") + " native="
                        + com.waenhancer.theme.GlassProbe.drawable(nativeValue));
    }
    private void clearTint(View view) {
        ColorStateList current = view.getBackgroundTintList();
        OwnedProperty<ColorStateList> entry = tints.get(view);
        if (entry != null && entry.owns(current)) return;
        if (current == null) return;
        if (entry == null) { entry = new OwnedProperty<>(); tints.put(view, entry); }
        entry.install(current, null);
        view.setBackgroundTintList(null);
    }
    /** Clone only a known fill tree. A RippleDrawable keeps its mask, selector and hotspot. */
    static Drawable transparentFill(Drawable original, boolean structural) {
        if (original instanceof RippleDrawable) {
            Drawable.ConstantState state = original.getConstantState();
            if (state == null) return null;
            RippleDrawable copy = (RippleDrawable) state.newDrawable().mutate();
            // Without an explicit mask RippleDrawable derives it from its content alpha.
            // Preserve that alpha tree before clearing fills, otherwise ripple would disappear.
            boolean hasMask = false;
            for (int i = 0; i < copy.getNumberOfLayers(); i++)
                if (copy.getId(i) == android.R.id.mask) hasMask = true;
            if (!hasMask && copy.getNumberOfLayers() > 0) {
                RippleDrawable maskSource = (RippleDrawable) state.newDrawable().mutate();
                Drawable[] children = new Drawable[maskSource.getNumberOfLayers()];
                for (int i = 0; i < children.length; i++) children[i] = maskSource.getDrawable(i);
                LayerDrawable mask = new LayerDrawable(children);
                mask.setPaddingMode(maskSource.getPaddingMode());
                for (int i = 0; i < children.length; i++) {
                    mask.setLayerInset(i, maskSource.getLayerInsetLeft(i), maskSource.getLayerInsetTop(i),
                            maskSource.getLayerInsetRight(i), maskSource.getLayerInsetBottom(i));
                    mask.setLayerSize(i, maskSource.getLayerWidth(i), maskSource.getLayerHeight(i));
                    mask.setLayerGravity(i, maskSource.getLayerGravity(i));
                }
                int index = copy.addLayer(mask);
                copy.setId(index, android.R.id.mask);
            }
            boolean changed = false;
            for (int i = 0; i < copy.getNumberOfLayers(); i++) {
                if (copy.getId(i) == android.R.id.mask) continue;
                if (!clearFillTree(copy.getDrawable(i))) return null;
                changed = true;
            }
            return changed ? copy : null;
        }
        if (original instanceof ColorDrawable || original instanceof GradientDrawable || structural)
            return new ColorDrawable(Color.TRANSPARENT);
        return null;
    }
    private static boolean clearFillTree(Drawable drawable) {
        if (drawable instanceof ColorDrawable || drawable instanceof GradientDrawable) {
            drawable.setAlpha(0); return true;
        }
        if (drawable instanceof InsetDrawable) return clearFillTree(((InsetDrawable) drawable).getDrawable());
        if (drawable instanceof LayerDrawable && !(drawable instanceof RippleDrawable)) {
            LayerDrawable layers = (LayerDrawable) drawable;
            for (int i = 0; i < layers.getNumberOfLayers(); i++)
                if (!clearFillTree(layers.getDrawable(i))) return false;
            return layers.getNumberOfLayers() > 0;
        }
        return false;
    }
    void restore() {
        restoreSlots(backgrounds, false);
        restoreSlots(foregrounds, true);
        for (View view : new ArrayList<>(tints.keySet())) {
            OwnedProperty<ColorStateList> entry = tints.get(view);
            if (view != null && entry.owns(view.getBackgroundTintList()))
                view.setBackgroundTintList(entry.original());
        }
        tints.clear();
        resolved.clear();
    }
    private void restoreSlots(WeakHashMap<View, OwnedProperty<Drawable>> map, boolean foreground) {
        for (View view : new ArrayList<>(map.keySet())) {
            if (view == null) continue;
            OwnedProperty<Drawable> entry = map.get(view);
            if (!entry.owns(foreground ? view.getForeground() : view.getBackground())) continue;
            if (foreground) view.setForeground(entry.original()); else setBackground(view, entry.original());
            if (com.waenhancer.theme.GlassProbe.enabled()) com.waenhancer.theme.GlassProbe.event(
                    voice ? "VOICE_BG_RESTORED" : "INPUT_BG_RESTORED", view,
                    "slot=" + (foreground ? "foreground" : "background"));
        }
        map.clear();
    }
    private static void setBackground(View view, Drawable value) {
        int l = view.getPaddingLeft(), t = view.getPaddingTop(), r = view.getPaddingRight(), b = view.getPaddingBottom();
        view.setBackground(value);
        if (l != view.getPaddingLeft() || t != view.getPaddingTop() || r != view.getPaddingRight() || b != view.getPaddingBottom())
            view.setPadding(l, t, r, b);
    }
}
