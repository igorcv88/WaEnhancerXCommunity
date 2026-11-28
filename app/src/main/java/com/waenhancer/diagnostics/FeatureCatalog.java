package com.waenhancer.diagnostics;

import android.content.SharedPreferences;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Every feature the loader registers, mapped to its regression surface and evidence rules. */
public final class FeatureCatalog {
    public static final class Entry {
        public final String surface;
        public final boolean required;
        public final boolean manual;
        Entry(String surface, boolean required, boolean manual) {
            this.surface = surface; this.required = required; this.manual = manual;
        }
    }

    /** A feature whose doHook starts with {@code if (!prefs.getBoolean(key, def)) return;}. */
    static final class Gate {
        final String key;
        final boolean defaultValue;
        Gate(String key, boolean defaultValue) { this.key = key; this.defaultValue = defaultValue; }
    }

    /**
     * Features whose hooked callbacks emit {@code reached}/{@code acted}. {@code FeatureCatalogTest}
     * derives this set from the sources, so it cannot claim or miss instrumentation.
     */
    static final Set<String> RUNTIME_PROBES = Set.of(
            "AntiRevoke", "AppLiquidGlass", "DownloadViewOnce", "FreezeLastSeen", "HideSeen",
            "MediaLibraryHook", "ViewOnce");

    /**
     * The behaviour path each mandatory smoke case must observe. The case "open a direct chat with
     * an unread message" is satisfied only by a suppressed direct read receipt, not by any
     * callback of the feature.
     */
    static final Map<String, String> REQUIRED_PATHS = Map.of(
            "HideSeen", "direct-read-suppressed",
            "AntiRevoke", "message-revoke-blocked");

    /** Leading preference gates, checked against each feature's doHook by FeatureCatalogTest. */
    static final Map<String, Gate> GATES = Map.ofEntries(
            Map.entry("AudioTranscript", new Gate("audio_transcription", false)),
            Map.entry("BackupRestore", new Gate("force_restore_backup_feature", false)),
            Map.entry("CopySelectionMessage", new Gate("copy_selection_message", false)),
            Map.entry("CopyStatus", new Gate("copystatus", false)),
            Map.entry("DndMode", new Gate("dndmode_actual", false)),
            Map.entry("GoogleTranslate", new Gate("google_translate", false)),
            Map.entry("HideSeenView", new Gate("hide_seen_view", false)),
            Map.entry("IGStatus", new Gate("igstatus", false)),
            Map.entry("LiteMode", new Gate("lite_mode", false)),
            Map.entry("LockedChatsEnhancer", new Gate("lockedchats_enhancer", false)),
            Map.entry("MediaLibraryHook", new Gate("media_library", false)),
            Map.entry("MediaPreview", new Gate("media_preview", true)),
            Map.entry("NewChat", new Gate("newchat", true)),
            Map.entry("SeparateGroup", new Gate("separategroups", false)),
            Map.entry("ShareLimit", new Gate("removeforwardlimit", false)),
            Map.entry("ShowEditMessage", new Gate("antieditmessages", false)),
            Map.entry("Stickers", new Gate("alertsticker", false)),
            Map.entry("ViewOnce", new Gate("viewonce", false)));

    private FeatureCatalog() {}

    public static Map<String, Entry> entries() {
        LinkedHashMap<String, String> surfaces = new LinkedHashMap<>();
        surface(surfaces, "Home / chat list", "MenuHome", "CustomToolbar", "ConversationItemListener", "SeparateGroup", "HideTabs");
        surface(surfaces, "Conversations", "HideSeen", "AntiRevoke", "SeenTick", "ShowEditMessage", "ChatScrollButtons", "RecoverDeleteForMe", "OutOfChatPlaybackScope", "Stickers");
        surface(surfaces, "Groups", "GroupAdmin", "PinnedLimit", "ChatLimit");
        surface(surfaces, "Status", "HideSeenView", "StatusDownload", "CopyStatus", "DeleteStatus", "IGStatus", "AutoStatusForward");
        surface(surfaces, "Privacy", "TypingPrivacy", "FreezeLastSeen", "CallPrivacy", "CustomPrivacy", "HideChat", "ShowOnline", "DndMode", "LockedChatsEnhancer");
        surface(surfaces, "Media", "ViewOnce", "DownloadViewOnce", "MediaQuality", "MediaPreview", "MediaLibraryHook", "CallRecording");
        surface(surfaces, "Calls", "CallType");
        surface(surfaces, "Customization / UI", "CustomView", "BubbleColors", "CustomThemeV2", "FloatingBottomBar", "AppLiquidGlass", "ConversationScrollButtonGlass");
        surface(surfaces, "Automation / Tasker", "Tasker");
        surface(surfaces, "Lazy tools", "AudioTranscript", "VideoNoteAttachment", "DownloadVideoNote", "SettingsInjector");
        LinkedHashMap<String, Entry> m = new LinkedHashMap<>();
        for (String name : FeatureManifest.FEATURES) {
            boolean required = REQUIRED_PATHS.containsKey(name);
            m.put(name, new Entry(surfaces.getOrDefault(name, "Other"), required, required));
        }
        return m;
    }

    public static boolean hasRuntimeProbe(String feature) {
        return RUNTIME_PROBES.contains(feature);
    }

    public static int runtimeProbeCount() {
        return RUNTIME_PROBES.size();
    }

    static String requiredPath(String feature) {
        return REQUIRED_PATHS.get(feature);
    }

    static ValidationModel.Enablement enablement(SharedPreferences prefs, String feature) {
        var settings = prefs.getAll();
        switch (feature) {
            case "HideSeen":
                // The manual smoke case exercises opening a direct chat, not the other paths.
                return known(Boolean.TRUE.equals(settings.get("hideread"))
                        || Boolean.TRUE.equals(settings.get("ghostmode_actual")));
            case "AntiRevoke":
                Object mode = settings.get("antirevoke");
                return known(mode instanceof String && ((String) mode).matches("[1-9][0-9]?"));
            case "FreezeLastSeen":
                return known(Boolean.TRUE.equals(settings.get("freezelastseen"))
                        || Boolean.TRUE.equals(settings.get("ghostmode_actual")));
            default:
                Gate gate = GATES.get(feature);
                if (gate == null) return ValidationModel.Enablement.UNKNOWN;
                Object value = settings.get(gate.key);
                return known(value instanceof Boolean ? (Boolean) value : gate.defaultValue);
        }
    }

    private static ValidationModel.Enablement known(boolean enabled) {
        return enabled ? ValidationModel.Enablement.ENABLED : ValidationModel.Enablement.DISABLED;
    }

    private static void surface(Map<String, String> map, String surface, String... names) {
        for (String name : names) map.putIfAbsent(name, surface);
    }
}
