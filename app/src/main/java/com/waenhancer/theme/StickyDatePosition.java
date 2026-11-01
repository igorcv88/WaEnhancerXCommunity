package com.waenhancer.theme;

/** A reversible additive offset; native sticky animations remain relative to their own baseline. */
public final class StickyDatePosition {
    private boolean owned;
    private float nativeTranslation, applied;
    public float place(float current, float untransformedTop, float headerBottom, float safeTop, float gap) {
        if (!owned || Float.compare(current, applied) != 0) nativeTranslation = current;
        applied = nativeTranslation + Math.max(0f, Math.max(headerBottom, safeTop) + gap - untransformedTop);
        owned = true;
        return applied;
    }
    public float restore(float current) {
        float result = owned && Float.compare(current, applied) == 0 ? nativeTranslation : current;
        owned = false;
        return result;
    }
}
