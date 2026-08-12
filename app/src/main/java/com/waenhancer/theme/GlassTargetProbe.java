package com.waenhancer.theme;

/**
 * Whether a view found in a host app's tree is plausibly the surface we went looking for.
 *
 * <p>Resource names move between WhatsApp builds; geometry does not. So discovery names its
 * candidates but does not trust them: every candidate is put through here, and the structural
 * search that runs when no name matches uses the same test to decide what to accept.</p>
 *
 * <p>Plain arithmetic on plain numbers, deliberately: this is the half of view discovery that can
 * be asserted without a device, and the half that decides whether a lens lands on the surface we
 * meant or on its container.</p>
 */
public final class GlassTargetProbe {

    /** Smallest and largest a floating round button may plausibly be, in dp. */
    private static final float ROUND_BUTTON_MIN_DP = 28f;
    private static final float ROUND_BUTTON_MAX_DP = 72f;

    /** How far from square a round button may be, as a fraction of its larger side. */
    private static final float ROUND_BUTTON_MAX_SKEW = 0.18f;

    private GlassTargetProbe() { }

    /**
     * Whether these measurements describe a small floating round button.
     *
     * <p>Squareness is what separates it from everything else at that size: an icon button in a row
     * of them is square too, so callers add position to this, but a capsule, a divider or a row
     * never is. The dp bounds are what keep an avatar thumbnail inside a message bubble and a
     * full-screen overlay out, both of which are square.</p>
     *
     * <p>A candidate that is not measured yet — zero width or height — is not rejected on the
     * merits but deferred by the caller, because a view that has not been laid out has no geometry
     * to judge.</p>
     */
    public static boolean isRoundButton(int widthPx, int heightPx, float density) {
        if (widthPx <= 0 || heightPx <= 0 || density <= 0f) return false;
        float larger = Math.max(widthPx, heightPx);
        float smaller = Math.min(widthPx, heightPx);
        if ((larger - smaller) / larger > ROUND_BUTTON_MAX_SKEW) return false;
        float largerDp = larger / density;
        return largerDp >= ROUND_BUTTON_MIN_DP && largerDp <= ROUND_BUTTON_MAX_DP;
    }
}
