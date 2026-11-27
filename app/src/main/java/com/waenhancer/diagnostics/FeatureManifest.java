package com.waenhancer.diagnostics;

import java.util.List;

/**
 * Simple names of every feature registered by {@code FeatureLoader.plugins}, in loader order.
 *
 * <p>The companion app cannot load the host-side feature classes, so diagnostics read this list.
 * {@code FeatureManifestTest} fails the build when it differs from the loader's class array, so
 * the compatibility report always covers exactly the registered features.</p>
 */
public final class FeatureManifest {
    public static final List<String> FEATURES = List.of(
            "DebugFeature",
            "MinorFixes",
            "ContactItemListener",
            "ConversationItemListener",
            "MenuStatusListener",
            "ShowEditMessage",
            "AntiRevoke",
            "OutOfChatPlaybackScope",
            "CustomToolbar",
            "CustomView",
            "ChatScrollButtons",
            "SeenTick",
            "BubbleColors",
            "CallPrivacy",
            "ActivityController",
            "CustomThemeV2",
            "ChatLimit",
            "ShowOnline",
            "DndMode",
            "FreezeLastSeen",
            "TypingPrivacy",
            "HideChat",
            "HideSeen",
            "HideSeenView",
            "TagMessage",
            "HideTabs",
            "SeparateGroup",
            "FloatingBottomBar",
            "ConversationScrollButtonGlass",
            "AppLiquidGlass",
            "InspectorFeature",
            "IGStatus",
            "LiteMode",
            "MediaQuality",
            "NewChat",
            "Others",
            "PinnedLimit",
            "CustomTime",
            "ShareLimit",
            "StatusDownload",
            "ViewOnce",
            "CallType",
            "MediaPreview",
            "FileSizeSpoofer",
            "AutoStatusForward",
            "Tasker",
            "DeleteStatus",
            "DownloadViewOnce",
            "MediaLibraryHook",
            "DownloadVideoNote",
            "Channels",
            "DownloadProfile",
            "GroupAdmin",
            "Stickers",
            "CopyStatus",
            "CopySelectionMessage",
            "TextStatusComposer",
            "ToastViewer",
            "SettingsInjector",
            "MenuHome",
            "CustomPrivacy",
            "AudioTranscript",
            "GoogleTranslate",
            "ContactBlockedVerify",
            "LockedChatsEnhancer",
            "CallRecording",
            "BackupRestore",
            "Spy",
            "RecoverDeleteForMe",
            "VideoNoteAttachment"
    );

    private FeatureManifest() {}
}
