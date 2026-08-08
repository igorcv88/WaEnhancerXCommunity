package com.waenhancer.xposed.features.others;

/**
 * Presentation rules for the menu entries WaEnhancer injects into WhatsApp.
 *
 * <p>Carrying an icon and being promoted to the toolbar are independent decisions. A sub-menu has
 * no toolbar slot of its own, so promotion is restricted there — but the entry must still show its
 * icon, exactly like the native rows around it.
 */
public final class InjectedMenuPresentation {

    private InjectedMenuPresentation() {
    }

    /**
     * An injected entry always carries its icon, including inside a sub-menu.
     *
     * @param insideSubMenu whether the entry is being added to a {@link android.view.SubMenu}
     */
    public static boolean shouldSetIcon(boolean insideSubMenu) {
        return true;
    }

    /**
     * Only a top-level entry may be promoted to the toolbar.
     *
     * @param iconModeEnabled whether the user asked for toolbar icons (`show_home_menu` = "1")
     * @param insideSubMenu   whether the entry is being added to a {@link android.view.SubMenu}
     */
    public static boolean shouldShowAsAction(boolean iconModeEnabled, boolean insideSubMenu) {
        return iconModeEnabled && !insideSubMenu;
    }
}
