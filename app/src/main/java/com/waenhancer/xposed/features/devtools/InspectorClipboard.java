package com.waenhancer.xposed.features.devtools;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.widget.Toast;

import com.waenhancer.R;

/**
 * Single shared implementation of "copy a value to the clipboard and confirm with a Toast".
 *
 * <p>A silent clipboard is indistinguishable from a broken button, so every copy action in the
 * inspector — whether triggered from {@link InspectorOverlay}'s panel or elsewhere — routes
 * through this class rather than duplicating the {@link ClipboardManager}/{@link Toast}
 * boilerplate.</p>
 */
public final class InspectorClipboard {

    private InspectorClipboard() {
    }

    public static void copy(Context context, String label, String value) {
        if (value == null || value.isEmpty()) {
            Toast.makeText(context, R.string.inspector_nothing_to_copy, Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
        }
        Toast.makeText(context, context.getString(R.string.inspector_copied, label), Toast.LENGTH_SHORT).show();
    }
}
