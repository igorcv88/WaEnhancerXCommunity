package com.waenhancer.xposed.features.customization;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class HomeTabSelectionTest {
    private static final List<Integer> MENU_BEFORE_GROUPS = Arrays.asList(200, 300, 400, 600);
    private static final List<Integer> WITH_GROUPS = Arrays.asList(200, 500, 300, 400, 600);

    @Test public void staleNativeMenuCannotSkipInsertedGroups() {
        List<Integer> current = HomeTabSelection.currentOrder(5, true, WITH_GROUPS,
                MENU_BEFORE_GROUPS, MENU_BEFORE_GROUPS);
        assertEquals(WITH_GROUPS, current);
        assertEquals(1, HomeTabSelection.target(current, Arrays.asList(300), 0, 1));
        assertEquals(3, HomeTabSelection.target(current, Arrays.asList(300), 0, 2));
    }

    @Test public void mismatchedGroupsOrderDoesNotGuessPagerPosition() {
        List<Integer> current = HomeTabSelection.currentOrder(5, true,
                MENU_BEFORE_GROUPS, MENU_BEFORE_GROUPS, MENU_BEFORE_GROUPS);
        assertTrue(current.isEmpty());
        assertEquals(1, HomeTabSelection.target(current, Arrays.asList(300), 0, 1));
    }

    @Test public void liveAdapterOrderMaySupplyInsertedGroups() {
        List<Integer> current = HomeTabSelection.currentOrder(5, true,
                Collections.emptyList(), WITH_GROUPS, MENU_BEFORE_GROUPS);
        assertEquals(WITH_GROUPS, current);
    }

    @Test public void hiddenTabsAreSkippedUsingCurrentPositions() {
        List<Integer> current = HomeTabSelection.currentOrder(5, true, WITH_GROUPS,
                WITH_GROUPS, MENU_BEFORE_GROUPS);
        assertEquals(3, HomeTabSelection.target(current, Arrays.asList(300), 0, 2));
        assertEquals(1, HomeTabSelection.target(current, Arrays.asList(300), 4, 2));
        assertEquals(4, HomeTabSelection.target(current, Arrays.asList(300), 1, 4));
    }

    @Test public void nativeTabsWithoutGroupCanStillBeResolved() {
        List<Integer> current = HomeTabSelection.currentOrder(4, false,
                Collections.emptyList(), MENU_BEFORE_GROUPS, MENU_BEFORE_GROUPS);
        assertEquals(MENU_BEFORE_GROUPS, current);
        assertEquals(2, HomeTabSelection.target(current, Arrays.asList(300), 0, 1));
    }
}
