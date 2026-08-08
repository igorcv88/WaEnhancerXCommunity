package com.waenhancer.xposed.features.others;

import android.content.Context;
import android.graphics.drawable.Drawable;

import com.waenhancer.xposed.utils.DesignUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Resolves every settings screen to a visible icon with a deterministic fallback. */
public final class SettingsIconRegistry {

    private static final String FALLBACK = "ic_general";
    private static final Map<String, String> ICONS;

    static {
        Map<String, String> icons = new LinkedHashMap<>();
        icons.put("general", "ic_general");
        icons.put("general_main", "ic_general");
        icons.put("ghost_mode", "ghost_enabled");
        icons.put("privacy", "ic_privacy");
        icons.put("privacy_main", "ic_privacy");
        icons.put("calls", "ic_call");
        icons.put("call_privacy", "ic_call");
        icons.put("media", "ic_media");
        icons.put("media_main", "ic_media");
        icons.put("customization", "ic_palette");
        icons.put("customization_main", "ic_palette");
        icons.put("automation", "ic_tasker");
        icons.put("tasker", "ic_tasker");
        icons.put("deleted_for_me", "deleted");
        icons.put("deleted_messages", "deleted");
        icons.put("about", "about");
        icons.put("home_screen_main", "ic_home_black_24dp");
        icons.put("conversation_main", "ic_home_tab_chats_unfilled");
        icons.put("optimization", "ic_database");
        icons.put("waenhancer", "ic_waenhancer_entry");
        ICONS = Collections.unmodifiableMap(icons);
    }

    private SettingsIconRegistry() {
    }

    public static String iconName(String screenId, String declaredName) {
        if (declaredName != null && !declaredName.trim().isEmpty()) {
            return declaredName.trim();
        }
        if (screenId == null) return FALLBACK;
        return ICONS.getOrDefault(screenId, FALLBACK);
    }

    public static Drawable resolve(Context context, String screenId, String declaredName) {
        String name = iconName(screenId, declaredName);
        Drawable drawable = DesignUtils.getDrawableByName(name);
        if (drawable == null && !FALLBACK.equals(name)) {
            drawable = DesignUtils.getDrawableByName(FALLBACK);
        }
        if (drawable == null) {
            try {
                drawable = context.getDrawable(android.R.drawable.ic_menu_preferences);
            } catch (RuntimeException ignored) {
                // Android's built-in icon is only the final fallback.
            }
        }
        return drawable;
    }

    public static Map<String, String> mappings() {
        return ICONS;
    }
}
