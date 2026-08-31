package com.waenhancer.diagnostics;

import java.util.LinkedHashMap;
import java.util.Map;

/** Stable, human-readable mapping of existing hooks to regression surfaces. */
public final class FeatureCatalog {
    public static final class Entry {
        public final String surface;
        public final boolean required;
        public final boolean manual;
        Entry(String surface, boolean required, boolean manual) {
            this.surface = surface; this.required = required; this.manual = manual;
        }
    }
    private FeatureCatalog() {}

    public static Map<String, Entry> entries() {
        LinkedHashMap<String, Entry> m = new LinkedHashMap<>();
        add(m, "Home / chat list", false, false, "MenuHome", "CustomToolbar", "ConversationItemListener");
        add(m, "Conversations", false, true, "HideSeen", "AntiRevoke", "SeenTick", "ShowEditMessage", "ChatScrollButtons");
        add(m, "Groups", false, true, "GroupAdmin", "SeparateGroup", "PinnedLimit", "ChatLimit");
        add(m, "Status", false, true, "HideSeenView", "StatusDownload", "CopyStatus", "DeleteStatus", "IGStatus", "AutoStatusForward");
        add(m, "Privacy", false, true, "TypingPrivacy", "FreezeLastSeen", "CallPrivacy", "CustomPrivacy", "HideChat");
        add(m, "Media", false, true, "ViewOnce", "DownloadViewOnce", "MediaQuality", "MediaPreview", "CallRecording");
        add(m, "Calls", false, true, "CallType", "CallPrivacy", "CallRecording");
        add(m, "Customization / UI", false, false, "CustomView", "BubbleColors", "CustomThemeV2", "FloatingBottomBar", "HideTabs");
        add(m, "Automation / Tasker", false, true, "Tasker", "AutoStatusForward");
        add(m, "Lazy tools", false, false, "AudioTranscript", "VideoNoteAttachment", "DownloadVideoNote", "SettingsInjector");
        // Mandatory smoke suite: one privacy/network suppression hook and one message mutation hook.
        m.put("HideSeen", new Entry("Conversations", true, true));
        m.put("AntiRevoke", new Entry("Conversations", true, true));
        return m;
    }

    private static void add(Map<String, Entry> map, String surface, boolean required, boolean manual, String... names) {
        for (String name : names) map.putIfAbsent(name, new Entry(surface, required, manual));
    }
}
