package com.waenhancer.ui.fragments;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceManager;

import com.waenhancer.R;
import com.waenhancer.activities.BottomBarCustomizationActivity;
import com.waenhancer.activities.FilterItemsActivity;
import com.waenhancer.ui.fragments.base.BasePreferenceFragment;

public class CustomizationFragment extends BasePreferenceFragment {
    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);
        setPreferencesFromResource(R.xml.fragment_customization, rootKey);

        Preference filterItemsPref = findPreference("filter_items");
        if (filterItemsPref != null) {
            filterItemsPref.setOnPreferenceClickListener(preference -> {
                startActivity(new Intent(requireContext(), FilterItemsActivity.class));
                return true;
            });
        }

        Preference bottomBarPref = findPreference("floating_bottom_bar_customizer");
        if (bottomBarPref != null) {
            bottomBarPref.setOnPreferenceClickListener(preference -> {
                startActivity(new Intent(requireContext(), BottomBarCustomizationActivity.class));
                return true;
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        setDisplayHomeAsUpEnabled(false);
        updateFilterItemsSummary();
    }

    private void updateFilterItemsSummary() {
        Preference filterItemsPref = findPreference("filter_items");
        if (filterItemsPref == null) return;

        String rawFilters = PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getString("filter_items", "");
        if (rawFilters == null || rawFilters.trim().isEmpty()) {
            filterItemsPref.setSummary(R.string.filters_summary_empty);
            return;
        }

        int count = 0;
        if (rawFilters.trim().startsWith("[")) {
            try {
                count = new org.json.JSONArray(rawFilters).length();
            } catch (Exception ignored) {
            }
        } else {
            for (String item : rawFilters.split("\n")) {
                if (!item.trim().isEmpty()) count++;
            }
        }
        filterItemsPref.setSummary(count == 0
                ? getString(R.string.filters_summary_empty)
                : getString(R.string.filters_summary_count, count));
    }
}
