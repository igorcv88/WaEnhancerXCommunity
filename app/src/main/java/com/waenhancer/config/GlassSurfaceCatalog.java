package com.waenhancer.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Semantic resource names observed in WaThemer, not obfuscated class/field pins. */
public final class GlassSurfaceCatalog {
    private static final Map<String, LiquidGlassSettings.Surface> TARGETS;
    static {
        Map<String, LiquidGlassSettings.Surface> targets = new LinkedHashMap<>();
        put(targets, LiquidGlassSettings.Surface.TOOLBARS,
                "toolbar", "search_view_toolbar", "action_mode_bar", "call_info_collapsing_toolbar",
                "community_navigation_toolbar");
        put(targets, LiquidGlassSettings.Surface.SEARCH,
                "my_search_bar", "search_bar", "conversations_filter_pinned_button_container");
        put(targets, LiquidGlassSettings.Surface.FAB,
                "fab", "extended_mini_fab", "fab_second", "create_new_broadcast_button", "community_navigation_add_group_button",
                "empty_community_row_button", "community_nux_next_button");
        put(targets, LiquidGlassSettings.Surface.COMPOSER,
                "input_layout", "conversation_entry_action_button", "voice_note_lock_container");
        put(targets, LiquidGlassSettings.Surface.QUOTES,
                "quoted_message_preview_container", "quoted_message_preview_container_v2",
                "quoted_message_frame", "bubble_quoted_message_frame", "composer_quoted_message_frame",
                "voice_note_draft_quoted_message_frame", "voice_note_draft_layout_v2");
        put(targets, LiquidGlassSettings.Surface.CARDS,
                "contact_details_card", "group_details_card", "newsletter_details_card",
                "business_details_card", "participants_card", "call_controls_card", "card_container",
                "call_log_actions", "business_details_actions", "group_details_actions");
        put(targets, LiquidGlassSettings.Surface.PANELS,
                "design_bottom_sheet", "bottom_sheet", "audio_chat_bottom_sheet",
                "psa_bottom_sheet_root", "virality_bottom_sheet", "paper_clip_layout",
                "reactions_tray_container", "sticker_pack_preview_bottom_sheet_layout",
                "view_replies_bottom_sheet");
        TARGETS = Collections.unmodifiableMap(targets);
    }
    private GlassSurfaceCatalog() { }
    private static void put(Map<String, LiquidGlassSettings.Surface> targets,
                            LiquidGlassSettings.Surface surface, String... names) {
        for (String name : names) targets.put(name, surface);
    }
    public static LiquidGlassSettings.Surface surface(String name) { return TARGETS.get(name); }
    public static Map<String, LiquidGlassSettings.Surface> targets() { return TARGETS; }
    public static float radiusDp(LiquidGlassSettings.Surface surface) {
        return switch (surface) {
            case FAB, COMPOSER, SEARCH -> 1000f;
            case BUBBLES, QUOTES -> 16f;
            case TOOLBARS -> 22f;
            case CARDS, PANELS -> 24f;
        };
    }
}
