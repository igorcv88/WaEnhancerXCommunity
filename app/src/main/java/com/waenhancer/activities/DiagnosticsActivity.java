package com.waenhancer.activities;

import android.content.Intent;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.waenhancer.activities.base.BaseActivity;
import com.waenhancer.diagnostics.LocalDiagnostics;

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
        toolbar.setTitle("Local diagnostics");
        toolbar.setNavigationIcon(android.R.drawable.ic_media_previous);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        TextView notice = new TextView(this);
        notice.setText("This preview is generated locally. Review it before sharing. "
                + "Message content, phone numbers, JIDs, tokens, keys, certificates and private paths are redacted.");
        notice.setPadding(dp(20), dp(12), dp(20), dp(12));
        root.addView(notice);

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

        MaterialButton refresh = button("Refresh");
        refresh.setOnClickListener(v -> refresh());
        actions.addView(refresh, weighted());

        MaterialButton clear = button("Clear events");
        clear.setOnClickListener(v -> {
            LocalDiagnostics.clear(this);
            refresh();
        });
        actions.addView(clear, weighted());

        MaterialButton share = button("Share preview");
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
        reportView.setText(LocalDiagnostics.buildReport(
                this, PreferenceManager.getDefaultSharedPreferences(this)));
    }

    private void share() {
        String report = reportView.getText() == null ? "" : reportView.getText().toString();
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "WaEnhancer Community diagnostics");
        intent.putExtra(Intent.EXTRA_TEXT, report);
        startActivity(Intent.createChooser(intent, "Share diagnostic preview"));
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
