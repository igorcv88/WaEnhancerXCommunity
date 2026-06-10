package com.waenhancer.ui.fragments;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceManager;

import com.waenhancer.R;
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
                Intent intent = new Intent(requireContext(), FilterItemsActivity.class);
                startActivity(intent);
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
        if (filterItemsPref != null) {
            String rawFilters = PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .getString("filter_items", "");
            if (rawFilters == null || rawFilters.trim().isEmpty()) {
                filterItemsPref.setSummary(R.string.filters_summary_empty);
            } else {
                String[] items = rawFilters.split("\n");
                int count = 0;
                for (String item : items) {
                    if (!item.trim().isEmpty()) {
                        count++;
                    }
                }
                if (count == 0) {
                    filterItemsPref.setSummary(R.string.filters_summary_empty);
                } else {
                    filterItemsPref.setSummary(getString(R.string.filters_summary_count, count));
                }
            }
        }
    }
}
