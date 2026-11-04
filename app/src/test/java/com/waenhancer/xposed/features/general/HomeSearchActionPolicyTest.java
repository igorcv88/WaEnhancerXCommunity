package com.waenhancer.xposed.features.general;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HomeSearchActionPolicyTest {
    @Test public void firstActionIsAlwaysAccepted() {
        assertFalse(HomeSearchActionPolicy.isDuplicate(1000, -1));
    }

    @Test public void repeatedCallbackFromSameTapIsIgnored() {
        assertTrue(HomeSearchActionPolicy.isDuplicate(1000, 1000));
        assertTrue(HomeSearchActionPolicy.isDuplicate(1174, 1000));
    }

    @Test public void laterTapCanCollapseTheBar() {
        assertFalse(HomeSearchActionPolicy.isDuplicate(1175, 1000));
        assertFalse(HomeSearchActionPolicy.isDuplicate(1450, 1000));
        assertFalse(HomeSearchActionPolicy.isDuplicate(800, 1000));
    }
}
