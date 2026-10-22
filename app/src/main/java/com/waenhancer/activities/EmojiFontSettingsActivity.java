package com.waenhancer.activities;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.waenhancer.R;
import com.waenhancer.config.EmojiFontStore;

import java.io.IOException;

/** Companion-only document picker: the injected process never receives an arbitrary document URI. */
public class EmojiFontSettingsActivity extends AppCompatActivity {
    private TextView selected;
    private Button choose;
    private Button remove;
    private volatile boolean importing;

    private final ActivityResultLauncher<String[]> pickFont = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) importSelected(uri);
            });

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        setTitle(R.string.custom_emoji_font_title);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad, pad, pad);

        TextView explanation = new TextView(this);
        explanation.setText(R.string.custom_emoji_font_explanation);
        explanation.setTextSize(16);
        content.addView(explanation);

        selected = new TextView(this);
        selected.setPadding(0, pad, 0, pad / 2);
        content.addView(selected);

        choose = new Button(this);
        choose.setText(R.string.custom_emoji_font_choose);
        choose.setOnClickListener(v -> pickFont.launch(new String[]{"*/*"}));
        content.addView(choose);

        remove = new Button(this);
        remove.setText(R.string.custom_emoji_font_remove);
        remove.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(R.string.custom_emoji_font_remove)
                .setMessage(R.string.custom_emoji_font_remove_confirmation)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    if (!EmojiFontStore.removeFont(this)) {
                        Toast.makeText(this, R.string.custom_emoji_font_remove_failed, Toast.LENGTH_LONG).show();
                    }
                    updateState();
                }).show());
        content.addView(remove);

        setContentView(content);
        updateState();
    }

    private void updateState() {
        boolean installed = EmojiFontStore.hasFont(this);
        selected.setText(installed
                ? getString(R.string.custom_emoji_font_selected, EmojiFontStore.displayName(this))
                : getString(R.string.custom_emoji_font_not_selected));
        choose.setEnabled(!importing);
        remove.setEnabled(!importing && installed);
    }

    private void importSelected(Uri uri) {
        if (importing) return;
        importing = true;
        updateState();
        final String name = documentName(uri);
        new Thread(() -> {
            String error = null;
            try {
                EmojiFontStore.importFont(getApplicationContext(), uri, name);
            } catch (IOException | RuntimeException e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final String failure = error;
            runOnUiThread(() -> {
                importing = false;
                if (isFinishing() || isDestroyed()) return;
                updateState();
                Toast.makeText(this, failure == null
                        ? getString(R.string.custom_emoji_font_imported)
                        : getString(R.string.custom_emoji_font_import_failed, failure),
                        Toast.LENGTH_LONG).show();
            });
        }, "WAEX-emoji-font-import").start();
    }

    private String documentName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (RuntimeException ignored) {
            // The picker is allowed to omit a display name.
        }
        return "Emoji font";
    }
}
