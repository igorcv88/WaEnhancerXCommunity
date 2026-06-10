package com.waenhancer.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.waenhancer.BuildConfig;
import com.waenhancer.R;
import com.waenhancer.activities.base.BaseActivity;
import com.waenhancer.adapter.FilterItemsAdapter;
import com.waenhancer.ui.helpers.BottomSheetHelper;

import java.util.ArrayList;
import java.util.List;

public class FilterItemsActivity extends BaseActivity implements FilterItemsAdapter.OnFilterDeleteListener {

    private final List<String> filtersList = new ArrayList<>();
    private FilterItemsAdapter adapter;
    private TextView emptyStateText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_filter_items);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.filter_items_by_id);
        }

        emptyStateText = findViewById(R.id.empty_state_text);
        loadFilters();

        RecyclerView recyclerView = findViewById(R.id.filter_items_recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new FilterItemsAdapter(filtersList, this);
        recyclerView.setAdapter(adapter);

        updateEmptyState();

        FloatingActionButton fab = findViewById(R.id.fab_add_filter);
        fab.setOnClickListener(v -> showAddFilterDialog());
    }

    private void loadFilters() {
        filtersList.clear();
        String rawFilters = PreferenceManager.getDefaultSharedPreferences(this)
                .getString("filter_items", "");
        if (rawFilters != null && !rawFilters.trim().isEmpty()) {
            String[] items = rawFilters.split("\n");
            for (String item : items) {
                String cleaned = item.trim();
                if (!cleaned.isEmpty() && !filtersList.contains(cleaned)) {
                    filtersList.add(cleaned);
                }
            }
        }
    }

    private void saveFilters() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < filtersList.size(); i++) {
            sb.append(filtersList.get(i));
            if (i < filtersList.size() - 1) {
                sb.append("\n");
            }
        }
        String filterString = sb.toString();
        PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putString("filter_items", filterString)
                .putBoolean("need_restart", true)
                .apply();

        // Notify the content provider and packages
        try {
            String authority = BuildConfig.APPLICATION_ID + ".hookprovider";
            getContentResolver().notifyChange(
                    Uri.parse("content://" + authority + "/preferences"),
                    null
            );

            Intent intent = new Intent(BuildConfig.APPLICATION_ID + ".PREFS_CHANGED");
            intent.setPackage("com.whatsapp");
            sendBroadcast(intent);

            Intent intent2 = new Intent(BuildConfig.APPLICATION_ID + ".PREFS_CHANGED");
            intent2.setPackage("com.whatsapp.w4b");
            sendBroadcast(intent2);
        } catch (Exception ignored) {}
    }

    private void updateEmptyState() {
        if (filtersList.isEmpty()) {
            emptyStateText.setVisibility(View.VISIBLE);
        } else {
            emptyStateText.setVisibility(View.GONE);
        }
    }

    private void showAddFilterDialog() {
        BottomSheetHelper.showInput(
                this,
                getString(R.string.add_filter),
                getString(R.string.add_filter_hint),
                getString(android.R.string.ok),
                input -> {
                    String cleanInput = input.trim();
                    if (cleanInput.isEmpty()) {
                        Toast.makeText(this, R.string.empty_filter_id_warning, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (filtersList.contains(cleanInput)) {
                        return; // already exists
                    }
                    filtersList.add(cleanInput);
                    adapter.notifyItemInserted(filtersList.size() - 1);
                    updateEmptyState();
                    saveFilters();
                }
        );
    }

    @Override
    public void onDelete(int position) {
        if (position >= 0 && position < filtersList.size()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.delete_filter)
                    .setMessage(R.string.delete_filter_confirm)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        filtersList.remove(position);
                        adapter.notifyItemRemoved(position);
                        // Notify items range changed to update their click position indexes
                        adapter.notifyItemRangeChanged(position, filtersList.size() - position);
                        updateEmptyState();
                        saveFilters();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
