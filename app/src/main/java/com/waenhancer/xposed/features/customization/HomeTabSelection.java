package com.waenhancer.xposed.features.customization;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Index routing for the actual ViewPager, never for a stale bottom-menu snapshot. */
public final class HomeTabSelection {
    private static final int GROUPS = 500;

    private HomeTabSelection() { }

    /**
     * Only a list matching the pager's current adapter count may identify positions.
     * When Groups is enabled, the separate-groups producer owns the latest tab order;
     * an older menu list without Groups must never redirect a swipe past it.
     */
    public static List<Integer> currentOrder(int pagerCount, boolean groupsEnabled,
                                             List<Integer> groupsOrder, List<Integer> adapterOrder,
                                             List<Integer> menuOrder) {
        if (pagerCount <= 0) return Collections.emptyList();
        if (groupsEnabled) {
            if (valid(groupsOrder, pagerCount) && groupsOrder.contains(GROUPS))
                return new ArrayList<>(groupsOrder);
            if (valid(adapterOrder, pagerCount) && adapterOrder.contains(GROUPS))
                return new ArrayList<>(adapterOrder);
            return Collections.emptyList();
        }
        if (valid(adapterOrder, pagerCount)) return new ArrayList<>(adapterOrder);
        if (valid(menuOrder, pagerCount)) return new ArrayList<>(menuOrder);
        return Collections.emptyList();
    }

    private static boolean valid(List<Integer> order, int count) {
        if (order == null || order.size() != count) return false;
        for (Integer id : order) if (id == null) return false;
        return true;
    }

    /** Fail open when the adapter order is unknown; never skip a visible position. */
    public static int target(List<Integer> order, List<?> hidden,
                             int current, int requested) {
        if (requested < 0 || requested >= order.size()
                || hidden == null || !hidden.contains(order.get(requested))) return requested;
        int direction = requested > current ? 1 : -1;
        for (int index = requested + direction; index >= 0 && index < order.size(); index += direction) {
            if (!hidden.contains(order.get(index))) return index;
        }
        return current;
    }
}
