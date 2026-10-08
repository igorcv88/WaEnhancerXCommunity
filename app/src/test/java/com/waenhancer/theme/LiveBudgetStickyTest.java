package com.waenhancer.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The device report after PR #73: surfaces near the budget's cut-off switched between live glass
 * and the fallback on every scroll or tap. Admission is now sticky; these pin that down.
 */
public class LiveBudgetStickyTest {

    /** A live surface keeps its slot when the set of surfaces asking around it changes. */
    @Test
    public void admittedSurfacesStayAdmittedAcrossReorders() {
        LiveBudget budget = new LiveBudget(1000);
        Object a = new Object(), b = new Object(), c = new Object();
        assertTrue(budget.admitDrawable(a, 400));
        assertTrue(budget.admitDrawable(b, 400));
        assertFalse(budget.admitDrawable(c, 400));       // new and does not fit: refused
        // The next frame asks in a different order (the list scrolled): nothing flips.
        for (int frame = 0; frame < 10; frame++) {
            assertFalse(budget.admitDrawable(c, 400));
            assertTrue(budget.admitDrawable(b, 400));
            assertTrue(budget.admitDrawable(a, 400));
        }
        // A surface leaving the screen frees its slot for the waiting one.
        budget.releaseDrawable(a);
        assertTrue(budget.admitDrawable(c, 400));
        assertEquals(800L, budget.drawableSpend());
    }

    /** Growth within the overrun keeps everyone; past it, only the newest admission gives way. */
    @Test
    public void overrunEvictsTheNewestOnly() {
        LiveBudget budget = new LiveBudget(1000);
        Object old = new Object(), mid = new Object(), young = new Object();
        assertTrue(budget.admitDrawable(old, 300));
        assertTrue(budget.admitDrawable(mid, 300));
        assertTrue(budget.admitDrawable(young, 300));
        // A pane grows (the keyboard opened): total 1100, inside the 1.25 overrun.
        assertTrue(budget.admitPane(new Object(), 0));
        assertTrue(budget.admitDrawable(old, 400));
        assertTrue(budget.admitDrawable(mid, 300));
        assertTrue(budget.admitDrawable(young, 300));
        // Past the overrun: the older ones keep their slots, the newest is evicted.
        assertTrue(budget.admitDrawable(old, 700));
        assertTrue(budget.admitDrawable(mid, 300));
        assertFalse(budget.admitDrawable(young, 300));
        assertFalse(budget.holdsDrawable(young));
        assertTrue(budget.holdsDrawable(old) && budget.holdsDrawable(mid));
    }

    /** Panes and drawables share one capacity. */
    @Test
    public void panesLeaveLessForDrawables() {
        LiveBudget budget = new LiveBudget(1000);
        assertTrue(budget.admitPane(new Object(), 700));
        assertFalse(budget.admitDrawable(new Object(), 400));
        assertTrue(budget.admitDrawable(new Object(), 300));
    }
}
