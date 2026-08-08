package com.waenhancer.activities;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.waenhancer.BuildConfig;
import com.waenhancer.R;
import com.waenhancer.activities.base.BaseActivity;
import com.waenhancer.adapter.FilterItemsAdapter;
import com.waenhancer.model.FilterItem;
import com.waenhancer.views.dialog.SimpleColorPickerDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class FilterItemsActivity extends BaseActivity implements FilterItemsAdapter.OnFilterActionListener {

    private final List<FilterItem> filtersList = new ArrayList<>();
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
        fab.setOnClickListener(v -> showFilterEditDialog(null, false, -1));
    }

    private void loadFilters() {
        filtersList.clear();
        String rawFilters = PreferenceManager.getDefaultSharedPreferences(this)
                .getString("filter_items", "");
        if (rawFilters == null || rawFilters.trim().isEmpty()) return;

        if (rawFilters.trim().startsWith("[")) {
            try {
                JSONArray array = new JSONArray(rawFilters);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject object = array.getJSONObject(i);
                    String id = object.optString("id", "").trim();
                    if (id.isEmpty()) continue;
                    filtersList.add(new FilterItem(
                            id,
                            object.optString("behavior", FilterItem.BEHAVIOR_GONE),
                            object.optInt("color", 0xFFFF0000),
                            object.optInt("opacity", 100),
                            (float) object.optDouble("scale", 1.0)));
                }
            } catch (Exception exception) {
                android.util.Log.e("WAEX", "Failed to parse JSON filter_items", exception);
            }
            return;
        }

        for (String item : rawFilters.split("\n")) {
            String cleaned = item.trim();
            if (!cleaned.isEmpty()) {
                filtersList.add(new FilterItem(
                        cleaned, FilterItem.BEHAVIOR_GONE, 0xFFFF0000, 100, 1.0f));
            }
        }
    }

    private void saveFilters() {
        JSONArray array = new JSONArray();
        for (FilterItem item : filtersList) {
            try {
                JSONObject object = new JSONObject();
                object.put("id", item.id);
                object.put("behavior", item.behavior);
                object.put("color", item.color);
                object.put("opacity", item.opacity);
                object.put("scale", item.scale);
                array.put(object);
            } catch (Exception ignored) {
            }
        }

        PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putString("filter_items", array.toString())
                .putBoolean("need_restart", true)
                .apply();

        try {
            String authority = BuildConfig.APPLICATION_ID + ".hookprovider";
            getContentResolver().notifyChange(
                    Uri.parse("content://" + authority + "/preferences"), null);

            for (String packageName : new String[]{"com.whatsapp", "com.whatsapp.w4b"}) {
                Intent changed = new Intent(BuildConfig.APPLICATION_ID + ".PREFS_CHANGED");
                changed.setPackage(packageName);
                sendBroadcast(changed);

                ArrayList<String> titles = new ArrayList<>();
                titles.add(getString(R.string.filter_items_by_id));
                Intent restart = new Intent(BuildConfig.APPLICATION_ID + ".MANUAL_RESTART");
                restart.setPackage(packageName);
                restart.putStringArrayListExtra("changed_titles", titles);
                sendBroadcast(restart);
            }
        } catch (Exception ignored) {
        }
    }

    private void updateEmptyState() {
        emptyStateText.setVisibility(filtersList.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void showFilterEditDialog(FilterItem item, boolean isEdit, int position) {
        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_filter_edit, null);
        dialog.setContentView(dialogView);

        TextView titleView = dialogView.findViewById(R.id.dialog_title);
        TextInputEditText idInput = dialogView.findViewById(R.id.dialog_filter_id);
        TextInputLayout idInputLayout = dialogView.findViewById(R.id.dialog_filter_id_layout);
        AutoCompleteTextView behaviorDropdown = dialogView.findViewById(R.id.dialog_filter_behavior);
        View layoutColor = dialogView.findViewById(R.id.layout_change_color);
        View layoutOpacity = dialogView.findViewById(R.id.layout_opacity);
        TextInputEditText opacityInput = dialogView.findViewById(R.id.dialog_opacity_input);
        View layoutResize = dialogView.findViewById(R.id.layout_resize);
        TextView resizeScaleText = dialogView.findViewById(R.id.resize_scale_text);
        com.google.android.material.slider.Slider resizeSlider =
                dialogView.findViewById(R.id.dialog_resize_slider);

        int initialBehavior = isEdit && item != null ? getIndexFromBehavior(item.behavior) : 0;
        titleView.setText(isEdit ? "Edit Filter" : "Add Filter");
        if (isEdit && item != null) idInput.setText(item.id);
        behaviorDropdown.setText(getBehaviorNameFromIndex(initialBehavior), false);
        toggleBehaviorLayouts(initialBehavior, layoutColor, layoutOpacity, layoutResize);

        String[] behaviors = {"Gone (Remove)", "Change Color", "Opacity", "Resize"};
        behaviorDropdown.setAdapter(new ArrayAdapter<>(
                this, android.R.layout.simple_dropdown_item_1line, behaviors));
        behaviorDropdown.setOnItemClickListener((parent, view, selected, id) ->
                toggleBehaviorLayouts(selected, layoutColor, layoutOpacity, layoutResize));

        final int[] selectedColor = {
                isEdit && item != null ? item.color : 0xFFFF0000
        };
        View colorPreview = dialogView.findViewById(R.id.color_preview_view);
        GradientDrawable previewDrawable = new GradientDrawable();
        previewDrawable.setShape(GradientDrawable.OVAL);
        previewDrawable.setColor(selectedColor[0]);
        colorPreview.setBackground(previewDrawable);
        dialogView.findViewById(R.id.btn_choose_color).setOnClickListener(v ->
                new SimpleColorPickerDialog(this, selectedColor[0], color -> {
                    selectedColor[0] = color;
                    previewDrawable.setColor(color);
                }).show());

        opacityInput.setText(isEdit && item != null
                && FilterItem.BEHAVIOR_OPACITY.equals(item.behavior)
                ? String.valueOf(item.opacity) : "100");

        float initialScale = isEdit && item != null ? item.scale : 1.0f;
        initialScale = Math.max(0.1f, Math.min(3.0f, initialScale));
        resizeSlider.setValue(initialScale);
        resizeScaleText.setText("Scale: " + String.format(Locale.US, "%.1fx", initialScale));
        resizeSlider.addOnChangeListener((slider, value, fromUser) ->
                resizeScaleText.setText("Scale: " + String.format(Locale.US, "%.1fx", value)));

        dialogView.findViewById(R.id.btn_cancel).setOnClickListener(v -> dialog.dismiss());
        dialogView.findViewById(R.id.btn_save).setOnClickListener(v -> {
            String id = idInput.getText() == null ? "" : idInput.getText().toString().trim();
            if (id.isEmpty()) {
                idInputLayout.setError("Filter ID cannot be empty");
                return;
            }
            idInputLayout.setError(null);

            int behaviorPosition = getIndexFromBehaviorName(
                    behaviorDropdown.getText().toString());
            String behavior = getBehaviorFromIndex(behaviorPosition);

            int opacity = 100;
            if (FilterItem.BEHAVIOR_OPACITY.equals(behavior)) {
                try {
                    opacity = Integer.parseInt(opacityInput.getText() == null
                            ? "" : opacityInput.getText().toString().trim());
                    if (opacity < 0 || opacity > 100) {
                        opacityInput.setError("Opacity must be between 0 and 100");
                        return;
                    }
                } catch (NumberFormatException exception) {
                    opacityInput.setError("Invalid opacity percent");
                    return;
                }
            }

            FilterItem result = new FilterItem(
                    id, behavior, selectedColor[0], opacity, resizeSlider.getValue());
            if (isEdit && position >= 0 && position < filtersList.size()) {
                filtersList.set(position, result);
                adapter.notifyItemChanged(position);
            } else {
                filtersList.add(result);
                adapter.notifyItemInserted(filtersList.size() - 1);
            }

            updateEmptyState();
            saveFilters();
            dialog.dismiss();
        });
        dialog.show();
    }

    private void toggleBehaviorLayouts(int position, View color, View opacity, View resize) {
        color.setVisibility(position == 1 ? View.VISIBLE : View.GONE);
        opacity.setVisibility(position == 2 ? View.VISIBLE : View.GONE);
        resize.setVisibility(position == 3 ? View.VISIBLE : View.GONE);
    }

    private String getBehaviorFromIndex(int index) {
        return switch (index) {
            case 1 -> FilterItem.BEHAVIOR_COLOR;
            case 2 -> FilterItem.BEHAVIOR_OPACITY;
            case 3 -> FilterItem.BEHAVIOR_RESIZE;
            default -> FilterItem.BEHAVIOR_GONE;
        };
    }

    private int getIndexFromBehavior(String behavior) {
        if (behavior == null) return 0;
        return switch (behavior) {
            case FilterItem.BEHAVIOR_COLOR -> 1;
            case FilterItem.BEHAVIOR_OPACITY -> 2;
            case FilterItem.BEHAVIOR_RESIZE -> 3;
            default -> 0;
        };
    }

    private String getBehaviorNameFromIndex(int index) {
        return switch (index) {
            case 1 -> "Change Color";
            case 2 -> "Opacity";
            case 3 -> "Resize";
            default -> "Gone (Remove)";
        };
    }

    private int getIndexFromBehaviorName(String behaviorName) {
        if (behaviorName == null) return 0;
        return switch (behaviorName) {
            case "Change Color", "Change Color (Pro)" -> 1;
            case "Opacity", "Opacity (Pro)" -> 2;
            case "Resize", "Resize (Pro)" -> 3;
            default -> 0;
        };
    }

    @Override
    public void onDelete(int position) {
        if (position < 0 || position >= filtersList.size()) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_filter)
                .setMessage(R.string.delete_filter_confirm)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    filtersList.remove(position);
                    adapter.notifyItemRemoved(position);
                    adapter.notifyItemRangeChanged(position, filtersList.size() - position);
                    updateEmptyState();
                    saveFilters();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void onEdit(int position) {
        if (position >= 0 && position < filtersList.size()) {
            showFilterEditDialog(filtersList.get(position), true, position);
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
