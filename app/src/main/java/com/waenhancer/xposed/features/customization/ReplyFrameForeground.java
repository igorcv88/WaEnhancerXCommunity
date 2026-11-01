package com.waenhancer.xposed.features.customization;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.NinePatchDrawable;
import android.view.View;
import com.waenhancer.theme.OwnedProperty;
import java.util.ArrayList;
import java.util.Set;
import java.util.WeakHashMap;

/** Only the two device-identified composer frames; never message-level NinePatch drawables. */
final class ReplyFrameForeground {
    private final WeakHashMap<View, OwnedProperty<Drawable>> values = new WeakHashMap<>();
    boolean owns(View view) { return values.containsKey(view); }
    void nativeAssignment(View view) { values.remove(view); }
    void clear(View view, String role) {
        if (!role.equals("voice_note_draft_quoted_message_frame") && !role.equals("composer_quoted_message_frame")) return;
        Drawable current = view.getForeground();
        OwnedProperty<Drawable> entry = values.get(view);
        if (entry != null && entry.owns(current)) return;
        if (!(current instanceof NinePatchDrawable)) return;
        ColorDrawable clear = new ColorDrawable(Color.TRANSPARENT);
        if (entry == null) { entry = new OwnedProperty<>(); values.put(view, entry); }
        entry.install(current, clear);
        view.setForeground(clear);
    }
    void retain(Set<View> active) {
        for (View view : new ArrayList<>(values.keySet())) {
            if (active != null && active.contains(view)) continue;
            OwnedProperty<Drawable> entry = values.remove(view);
            if (view != null && entry.owns(view.getForeground())) view.setForeground(entry.original());
        }
    }
}
