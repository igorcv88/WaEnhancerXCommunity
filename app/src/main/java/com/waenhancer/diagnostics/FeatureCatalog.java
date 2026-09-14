package com.waenhancer.diagnostics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

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

    /**
     * Features that currently emit a real callback-level {@code diagnosticTriggered()} event.
     *
     * <p>The catalog is intentionally larger than this set. A catalog entry means the feature is
     * worth regression-testing; it does not mean runtime telemetry has already been implemented for
     * that feature. Keeping this distinction explicit prevents the diagnostics UI from telling the
     * user that an uninstrumented hook was merely "not exercised" when there is in fact no code
     * capable of observing its execution yet.</p>
     */
    private static final Set<String> RUNTIME_PROBES = Set.of(
            "HideSeen",
            "AntiRevoke",
            "ViewOnce",
            "DownloadViewOnce",
            "FreezeLastSeen"
    );

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

    public static boolean hasRuntimeProbe(String feature) {
        return RUNTIME_PROBES.contains(feature);
    }

    public static int runtimeProbeCount() {
        return RUNTIME_PROBES.size();
    }

    /** Exact action that can produce callback evidence for an instrumented feature. */
    public static String exerciseHint(String feature) {
        switch (feature) {
            case "HideSeen":
                return "with Hide Blue Ticks active, open an unread chat and let WhatsApp attempt a read receipt";
            case "AntiRevoke":
                return "while Anti-Revoke is active, process a real Delete for Everyone event";
            case "ViewOnce":
                return "with View Once bypass active, open an incoming view-once photo/video";
            case "DownloadViewOnce":
                return "with Download View Once active, open a view-once screen/options menu";
            case "FreezeLastSeen":
                return "with Freeze Last Seen active, foreground/background WhatsApp until it attempts a presence/last-seen update";
            default:
                return "";
        }
    }

    private static void add(Map<String, Entry> map, String surface, boolean required, boolean manual, String... names) {
        for (String name : names) map.putIfAbsent(name, new Entry(surface, required, manual));
    }
}
