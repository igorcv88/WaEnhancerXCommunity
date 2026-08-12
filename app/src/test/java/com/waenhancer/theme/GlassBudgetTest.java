package com.waenhancer.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/**
 * The cost of glass, pinned without a device.
 *
 * <p>Whether a lens is affordable is the one thing about this engine that cannot be seen in a
 * screenshot: a bar with three lenses behind it looks exactly like a bar with one until the list
 * starts moving. These are the assertions that stand in for that.</p>
 */
public class GlassBudgetTest {

    private static GlassSpec liquid() {
        return GlassSpec.resolve(GlassSpec.Variant.LIQUID, true, 0, 0x3B82F6, 35f, true, false);
    }

    private static GlassSpec stable() {
        return GlassSpec.resolve(GlassSpec.Variant.STABLE, true, 0, 0x3B82F6, 35f, true, false);
    }

    // ---- eligibility ------------------------------------------------------------------------

    /**
     * A surface inside something that scrolls is refused whatever the capacity, because the cost
     * being refused is per frame rather than per surface.
     */
    @Test
    public void layeredSurfacesNeverGetALensHoweverMuchRoomThereIs() {
        GlassBudget budget = new GlassBudget(99);

        assertFalse(budget.grant(new Object(), GlassBudget.Kind.LAYERED, liquid()));
        assertEquals(0, budget.lensedCount());
    }

    /** A variant with no lens does not consume a slot it would not use. */
    @Test
    public void aVariantWithoutALensSpendsNothing() {
        GlassBudget budget = new GlassBudget(3);

        assertFalse(budget.grant(new Object(), GlassBudget.Kind.STATIC_CHROME, stable()));
        assertEquals(0, budget.lensedCount());
    }

    @Test
    public void aNullSpecIsRefusedRatherThanCharged() {
        GlassBudget budget = new GlassBudget(3);

        assertFalse(budget.grant(new Object(), GlassBudget.Kind.STATIC_CHROME, null));
        assertEquals(0, budget.lensedCount());
    }

    // ---- capacity ---------------------------------------------------------------------------

    /** Past the cap the next eligible surface is refused, not squeezed in. */
    @Test
    public void theCapIsTheCap() {
        GlassBudget budget = new GlassBudget(3);
        for (int i = 0; i < 3; i++) {
            assertTrue("holder " + i,
                    budget.grant(new Object[]{i}, GlassBudget.Kind.STATIC_CHROME, liquid()));
        }

        assertFalse(budget.grant(new Object(), GlassBudget.Kind.STATIC_CHROME, liquid()));
        assertEquals(3, budget.lensedCount());
    }

    /**
     * Granting is called from layout passes, so a holder asking twice must not drain the budget —
     * that would leave a bar that re-lays out a few times holding every slot in the process.
     */
    @Test
    public void grantingIsIdempotentPerHolder() {
        GlassBudget budget = new GlassBudget(1);
        Object holder = new Object();

        assertTrue(budget.grant(holder, GlassBudget.Kind.STATIC_CHROME, liquid()));
        assertTrue(budget.grant(holder, GlassBudget.Kind.STATIC_CHROME, liquid()));
        assertEquals(1, budget.lensedCount());
    }

    /** A released slot is spendable again — the case of a driver rejecting the shader. */
    @Test
    public void releasingReturnsCapacity() {
        GlassBudget budget = new GlassBudget(1);
        Object first = new Object();
        Object second = new Object();
        assertTrue(budget.grant(first, GlassBudget.Kind.STATIC_CHROME, liquid()));
        assertFalse(budget.grant(second, GlassBudget.Kind.STATIC_CHROME, liquid()));

        budget.release(first);

        assertFalse(budget.holds(first));
        assertTrue(budget.grant(second, GlassBudget.Kind.STATIC_CHROME, liquid()));
        assertEquals(1, budget.lensedCount());
    }

    /** Releasing something that never held a slot is not an error; detach is best-effort. */
    @Test
    public void releasingAStrangerIsHarmless() {
        GlassBudget budget = new GlassBudget(2);
        budget.release(new Object());
        budget.release(null);

        assertEquals(0, budget.lensedCount());
    }

    // ---- the shipped figure -----------------------------------------------------------------

    /**
     * The floating bar is one of these. Anything that drops the cap to one silently takes the lens
     * away from every surface added after it, which looks like nothing at all having happened.
     */
    @Test
    public void theShippedCapLeavesRoomBesideTheBottomBar() {
        assertTrue(GlassBudget.MAX_LENSED_SURFACES >= 2);
        assertEquals(GlassBudget.MAX_LENSED_SURFACES, GlassBudget.shared().capacity());
    }

    // ---- overdraw ---------------------------------------------------------------------------

    /** The layered path is cheap but not free, and a screenful of it is countable in advance. */
    @Test
    public void totalLayersCountsWhatAScreenDeclares() {
        assertEquals(stable().layerCount() + liquid().layerCount(),
                GlassBudget.totalLayers(Arrays.asList(stable(), liquid())));
        assertEquals(0, GlassBudget.totalLayers(null));
        assertEquals(stable().layerCount(),
                GlassBudget.totalLayers(Arrays.asList(stable(), null)));
    }
}
