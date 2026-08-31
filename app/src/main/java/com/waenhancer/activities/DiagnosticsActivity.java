package com.waenhancer.activities;

import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.CheckBox;

import androidx.annotation.Nullable;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.waenhancer.R;
import com.waenhancer.activities.base.BaseActivity;
import com.waenhancer.diagnostics.FeatureCatalog;
import com.waenhancer.diagnostics.ValidationSession;
import com.waenhancer.config.PreferenceStores;

/** Preview-first local diagnostic exporter. */
public class DiagnosticsActivity extends BaseActivity {

    private TextView reportView;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContent());
        refresh();
    }

    private android.view.View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle(R.string.diagnostics_title);
        toolbar.setNavigationIcon(android.R.drawable.ic_media_previous);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        TextView notice = new TextView(this);
        notice.setText(R.string.diagnostics_notice);
        notice.setPadding(dp(20), dp(12), dp(20), dp(12));
        root.addView(notice);

        LinearLayout checklist = new LinearLayout(this);
        checklist.setOrientation(LinearLayout.VERTICAL);
        checklist.setPadding(dp(20), dp(4), dp(20), dp(8));
        TextView checklistTitle = new TextView(this);
        checklistTitle.setText("Required behavioral confirmations (human-observed)");
        checklistTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        checklist.addView(checklistTitle);
        for (java.util.Map.Entry<String, FeatureCatalog.Entry> item : FeatureCatalog.entries().entrySet()) {
            if (!item.getValue().manual || !item.getValue().required) continue;
            CheckBox check = new CheckBox(this);
            check.setText(item.getValue().surface + " · " + item.getKey());
            check.setChecked(ValidationSession.manual(
                    PreferenceStores.privateStore(this), item.getKey()));
            check.setOnCheckedChangeListener((button, checked) -> {
                ValidationSession.setManual(PreferenceStores.privateStore(this), item.getKey(), checked);
                refresh();
            });
            checklist.addView(check);
        }
        root.addView(checklist);

        ScrollView scroll = new ScrollView(this);
        reportView = new TextView(this);
        reportView.setTextIsSelectable(true);
        reportView.setMovementMethod(new ScrollingMovementMethod());
        reportView.setTypeface(android.graphics.Typeface.MONOSPACE);
        reportView.setTextSize(12);
        reportView.setPadding(dp(20), dp(8), dp(20), dp(16));
        scroll.addView(reportView, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(dp(12), dp(8), dp(12), dp(12));

        MaterialButton session = button(ValidationSession.active(
                PreferenceStores.privateStore(this)) ? "Reset session" : "Start session");
        session.setOnClickListener(v -> session());
        actions.addView(session, weighted());

        MaterialButton copy = button("Copy report");
        copy.setOnClickListener(v -> copy());
        actions.addView(copy, weighted());

        MaterialButton share = button(getString(R.string.diagnostics_share));
        share.setOnClickListener(v -> share());
        actions.addView(share, weighted());
        root.addView(actions);
        return root;
    }

    private MaterialButton button(String label) {
        MaterialButton button = new MaterialButton(this);
        button.setText(label);
        button.setTextSize(11);
        return button;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    private void refresh() {
        reportView.setText(ValidationSession.buildReport(
                this, PreferenceStores.privateStore(this)));
    }

    private void session() {
        android.content.SharedPreferences prefs = PreferenceStores.privateStore(this);
        if (ValidationSession.active(prefs)) {
            ValidationSession.reset(prefs);
            recreate();
            return;
        }
        String[] labels = {"WhatsApp", "WhatsApp Business"};
        String[] packages = {"com.whatsapp", "com.whatsapp.w4b"};
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Select validation target")
                .setItems(labels, (dialog, which) -> {
                    ValidationSession.start(this, prefs, packages[which]);
                    recreate();
                }).show();
    }

    private void copy() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("WaEnhancer diagnostics", reportView.getText()));
        android.widget.Toast.makeText(this, "Diagnostic report copied", android.widget.Toast.LENGTH_SHORT).show();
    }

    private void share() {
        String report = reportView.getText() == null ? "" : reportView.getText().toString();
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.diagnostics_share_subject));
        intent.putExtra(Intent.EXTRA_TEXT, report);
        startActivity(Intent.createChooser(intent, getString(R.string.diagnostics_share_chooser)));
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
