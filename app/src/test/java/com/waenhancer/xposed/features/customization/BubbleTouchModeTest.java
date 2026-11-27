package com.waenhancer.xposed.features.customization;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BubbleTouchModeTest {
    @Test public void propertyIsExplicitAndUnknownValuesDoNotEnableWork() {
        assertEquals(BubbleTouchMode.UNSET, BubbleTouchMode.parse(""));
        assertEquals(BubbleTouchMode.INVALID, BubbleTouchMode.parse("DEBUG"));
        assertEquals(BubbleTouchMode.LATE_EMPTY, BubbleTouchMode.parse("late-empty"));
        for (BubbleTouchMode mode : new BubbleTouchMode[]{BubbleTouchMode.OFF,
                BubbleTouchMode.RESOLVE, BubbleTouchMode.EMPTY, BubbleTouchMode.LATE_EMPTY}) {
            assertFalse(mode.callbacks());
            assertFalse(mode.logs());
        }
    }

    @Test public void locationAndReportingStagesAccumulateOnlyTheirOwnWork() {
        assertFalse(BubbleTouchMode.CALLBACKS.list());
        assertTrue(BubbleTouchMode.LIST.list());
        assertFalse(BubbleTouchMode.LIST.row());
        assertFalse(BubbleTouchMode.ROW.hierarchy());
        assertFalse(BubbleTouchMode.HIERARCHY.reflects());
        assertTrue(BubbleTouchMode.REFLECT.reflects());
        assertFalse(BubbleTouchMode.REFLECT.formats());
        assertTrue(BubbleTouchMode.FORMAT.formats());
        assertFalse(BubbleTouchMode.FORMAT.logs());
        assertTrue(BubbleTouchMode.LOG.logs());
        assertFalse(BubbleTouchMode.LOG.resolvesRowHooks());
        assertFalse(BubbleTouchMode.LOG.adapter());
    }

    @Test public void adapterAndRowHookBranchesStaySeparateAndQuiet() {
        for (BubbleTouchMode mode : new BubbleTouchMode[]{BubbleTouchMode.ADAPTER_RESOLVE,
                BubbleTouchMode.ADAPTER_EMPTY, BubbleTouchMode.ADAPTER_WATCH}) {
            assertTrue(mode.adapter());
            assertFalse(mode.hierarchy());
            assertFalse(mode.logs());
        }
        for (BubbleTouchMode mode : new BubbleTouchMode[]{BubbleTouchMode.ROW_RESOLVE,
                BubbleTouchMode.ROW_HOOKS, BubbleTouchMode.ROW_WATCH}) {
            assertTrue(mode.resolvesRowHooks());
            assertFalse(mode.adapter());
            assertFalse(mode.reflects());
            assertFalse(mode.logs());
        }
        assertTrue(BubbleTouchMode.FULL.adapter());
        assertTrue(BubbleTouchMode.FULL.resolvesRowHooks());
        assertTrue(BubbleTouchMode.FULL.logs());
    }
}
