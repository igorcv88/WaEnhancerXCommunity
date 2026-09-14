package com.waenhancer.preference;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import rikka.material.preference.MaterialSwitchPreference;

/**
 * Material switch whose stored checked state survives temporary dependency disabling.
 *
 * <p>Some display controls are disabled while the feature they expose is active. The shared
 * preference-screen helper normally clears a checked MaterialSwitchPreference when disabling it,
 * which is appropriate for mutually exclusive features but not for a "show this control" option:
 * clearing it makes the control disappear as soon as the underlying feature is enabled. This
 * preference keeps its checked value for that case while remaining visually disabled.</p>
 */
public class RetainedDisabledSwitchPreference extends MaterialSwitchPreference {

    public RetainedDisabledSwitchPreference(@NonNull Context context) {
        super(context);
    }

    public RetainedDisabledSwitchPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void setChecked(boolean checked) {
        if (!isEnabled() && isChecked() && !checked) {
            return;
        }
        super.setChecked(checked);
    }
}
