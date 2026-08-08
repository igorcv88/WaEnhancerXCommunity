package com.waenhancer.xposed.features.others;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Regression tests for the injected home menu.
 *
 * <p>Every WaEnhancer entry is added to a sub-menu, so tying the icon to "not a sub-menu" silently
 * removed every icon except the sub-menu's own header.
 */
public class InjectedMenuPresentationTest {

    @Test
    public void iconIsStillSetInsideASubMenu() {
        assertTrue(InjectedMenuPresentation.shouldSetIcon(true));
    }

    @Test
    public void iconIsSetAtTopLevel() {
        assertTrue(InjectedMenuPresentation.shouldSetIcon(false));
    }

    @Test
    public void subMenuEntryIsNeverPromotedToTheToolbar() {
        assertFalse(InjectedMenuPresentation.shouldShowAsAction(true, true));
    }

    @Test
    public void topLevelEntryIsPromotedOnlyInIconMode() {
        assertTrue(InjectedMenuPresentation.shouldShowAsAction(true, false));
        assertFalse(InjectedMenuPresentation.shouldShowAsAction(false, false));
    }
}
