package com.waenhancer.theme;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * How much glass this process is allowed to afford, and who is currently spending it.
 *
 * <p>A lens is not a style, it is a cost: one {@code RuntimeShader} plus a per-frame capture of
 * everything behind the surface. One of those pays for itself on a bar that sits still. Ten of
 * them attached to the rows of a list that scrolls is the single most likely way to make the whole
 * effect unusable, and it is a cost that only shows up on a device — which is precisely why the
 * decision is taken here, in plain Java, where it can be asserted in a unit test instead of
 * discovered in a dropped frame.</p>
 *
 * <p>Two things are decided:</p>
 *
 * <ol>
 *   <li><b>Eligibility.</b> {@link Kind} says whether a surface is the kind of thing that may hold
 *       a lens at all. Static chrome may; anything living inside something that scrolls may not,
 *       however much it would like to.</li>
 *   <li><b>Capacity.</b> Eligibility is not a grant. A ledger of the live holders is kept, and past
 *       {@link #MAX_LENSED_SURFACES} the next eligible surface is refused and falls back to
 *       {@link GlassRenderer}'s layered rim rather than being granted a lens the process cannot
 *       carry.</li>
 * </ol>
 *
 * <p>Holders are held weakly. A surface whose view tree is torn down without a call to release —
 * a hooked host process gives no guarantee of that — must not keep a slot occupied for the life of
 * the process.</p>
 */
public final class GlassBudget {

    /**
     * What a surface is, as far as the cost of drawing it is concerned.
     *
     * <p>Not a visual classification: two surfaces that should look identical can land in
     * different kinds, because what separates them is whether their backdrop is recaptured every
     * frame.</p>
     */
    public enum Kind {
        /**
         * A bar, a header, an input row: laid out once and then still, with the content moving
         * behind it. The lens's capture is what the surface is <em>for</em> here, and it is paid
         * for once per change rather than once per frame of a fling.
         */
        STATIC_CHROME(true),
        /**
         * Anything inside something that scrolls — list rows, message bubbles, cards in a
         * settings list. These get the layered renderer and never a lens, at any budget: the
         * refusal is about how many times per second the surface would have to recapture its
         * backdrop, not about how many of them there are.
         */
        LAYERED(false);

        private final boolean lensEligible;

        Kind(boolean lensEligible) {
            this.lensEligible = lensEligible;
        }

        /** Whether surfaces of this kind may ever be granted a lens. */
        public boolean isLensEligible() {
            return lensEligible;
        }
    }

    /**
     * How many lensed surfaces may be live at once.
     *
     * <p>Three, while the surfaces are being brought up one at a time: the floating bar is one of
     * them, which leaves room for two more to be measured against it. Raising this is a decision
     * to be taken after every surface has been measured on a device and none of them is still
     * moving, not while they are being added.</p>
     */
    public static final int MAX_LENSED_SURFACES = 3;

    private static final GlassBudget SHARED = new GlassBudget(MAX_LENSED_SURFACES);

    private final int capacity;
    private final Set<Object> holders =
            Collections.newSetFromMap(new WeakHashMap<Object, Boolean>());

    /** A ledger with its own capacity. Tests use this; the process uses {@link #shared()}. */
    public GlassBudget(int capacity) {
        this.capacity = Math.max(0, capacity);
    }

    /** The ledger every surface in this process spends against. */
    public static GlassBudget shared() {
        return SHARED;
    }

    /**
     * Asks for a lens slot on behalf of {@code holder}.
     *
     * <p>Idempotent: a holder that already has a slot keeps it and consumes no more capacity, so
     * this may be called from a layout pass without the budget draining as the surface settles.
     * A refusal is not an error — it means the caller paints itself with {@link GlassRenderer}
     * instead, which is a working surface rather than a missing one.</p>
     *
     * @param holder identity of the surface asking; held weakly
     * @param kind   what sort of surface it is
     * @param spec   the resolved surface, which may itself want no lens
     * @return whether this holder now has a slot
     */
    public synchronized boolean grant(Object holder, Kind kind, GlassSpec spec) {
        if (holder == null || kind == null) return false;
        if (holders.contains(holder)) return true;
        if (!kind.isLensEligible()) return false;
        if (spec == null || spec.lensStrength <= 0f) return false;
        if (holders.size() >= capacity) return false;
        holders.add(holder);
        return true;
    }

    /**
     * Gives back {@code holder}'s slot, if it had one.
     *
     * <p>Called on detach, and also when a device's driver rejects the shader: a lens that was
     * granted and then refused by the hardware has to stop occupying capacity that another
     * surface could still use for the layered path's sake.</p>
     */
    public synchronized void release(Object holder) {
        if (holder != null) holders.remove(holder);
    }

    /** Whether {@code holder} currently holds a slot. */
    public synchronized boolean holds(Object holder) {
        return holder != null && holders.contains(holder);
    }

    /** How many lensed surfaces are live. */
    public synchronized int lensedCount() {
        return holders.size();
    }

    /** How many this ledger will allow. */
    public int capacity() {
        return capacity;
    }

    /**
     * Total overdraw of a set of surfaces, in layers.
     *
     * <p>The other half of the budget, and the half that applies to the surfaces the lens refused:
     * a layered surface is cheap but not free, and enough of them stacked over one another is its
     * own way of spending a frame. Summing {@link GlassSpec#layerCount()} over everything a screen
     * declares is a figure that can be asserted before any of it is installed.</p>
     */
    public static int totalLayers(Iterable<GlassSpec> specs) {
        int total = 0;
        if (specs == null) return 0;
        for (GlassSpec spec : specs) {
            if (spec != null) total += spec.layerCount();
        }
        return total;
    }
}
