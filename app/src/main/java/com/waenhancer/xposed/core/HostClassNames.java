package com.waenhancer.xposed.core;

import java.util.List;

/**
 * Host classes that keep their name but move between packages. Each list holds every name the
 * class has had, newest first; a lookup takes the first that the host defines.
 *
 * <p>Checked against the DEX of both audited hosts: 2.26.38.74 defines {@code settings.ui.About}
 * and {@code settings.ui.SettingsTabActivity}; 2.26.46.82 moved them to {@code settings.ui.help}
 * and {@code settings.ui.main}. The census in {@code tools/compatibility} records which names each
 * host defines.</p>
 */
public final class HostClassNames {
    public static final List<String> ABOUT = List.of(
            "com.whatsapp.settings.ui.help.About",
            "com.whatsapp.settings.ui.About",
            "com.whatsapp.settings.About");

    public static final List<String> SETTINGS_TAB = List.of(
            "com.whatsapp.settings.ui.main.SettingsTabActivity",
            "com.whatsapp.settings.ui.SettingsTabActivity");

    /** Finds a class by name, or returns null when the host does not define it. */
    public interface Finder {
        Class<?> find(String name);
    }

    private HostClassNames() {}

    public static Class<?> first(List<String> names, Finder finder) {
        for (String name : names) {
            try {
                Class<?> found = finder.find(name);
                if (found != null) return found;
            } catch (Throwable ignored) {
                // Not defined under this name; try the next.
            }
        }
        return null;
    }

    public static Class<?> first(List<String> names, ClassLoader loader) {
        return first(names, name -> {
            try {
                return Class.forName(name, false, loader);
            } catch (ClassNotFoundException e) {
                return null;
            }
        });
    }

    public static boolean isOneOf(List<String> names, Class<?> type) {
        return type != null && names.contains(type.getName());
    }
}
