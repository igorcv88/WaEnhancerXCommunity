package com.waenhancer.diagnostics;

import java.util.Collection;

/** Pure compatibility/session state aggregation, kept Android-free for unit testing. */
public final class ValidationModel {
    private ValidationModel() {}

    public enum Compatibility { INCOMPATIBLE, RUNTIME_COMPATIBLE, DEGRADED, VALIDATED }
    public enum FeatureState { DISABLED, NOT_LOADED, RESOLVER_FAILED, INSTALLED, TRIGGERED, ERROR, NOT_EXERCISED }

    public static boolean occurredDuringSession(long eventAt, long sessionStartedAt) {
        return eventAt > 0L && eventAt >= sessionStartedAt;
    }

    public static final class FeatureEvidence {
        public boolean enabled = true;
        public boolean loaded;
        public boolean resolverPassed;
        public boolean resolverFailed;
        public boolean installed;
        public boolean opportunity;
        public boolean triggered;
        public boolean error;
        public boolean required;
        public boolean manualRequired;
        public boolean manualConfirmed;

        public FeatureState state() {
            if (!enabled) return FeatureState.DISABLED;
            if (error) return FeatureState.ERROR;

            // A callback can only execute if the class was loaded and a hook actually reached the
            // host method. It is therefore stronger runtime evidence than the optional installation
            // bookkeeping flags and must not be hidden behind a missing resolverPassed marker.
            if (triggered) return FeatureState.TRIGGERED;

            if (!loaded) return opportunity ? FeatureState.NOT_LOADED : FeatureState.NOT_EXERCISED;
            if (resolverFailed) return FeatureState.RESOLVER_FAILED;
            if (installed && opportunity) return FeatureState.INSTALLED; // installed, but apparently dead
            return FeatureState.NOT_EXERCISED;
        }
    }

    public static Compatibility aggregate(boolean corePassed, boolean optionalFailed,
            boolean knownValidatedBuild, boolean sessionActive, Collection<FeatureEvidence> features) {
        if (!corePassed) return Compatibility.INCOMPATIBLE;
        boolean errors = optionalFailed;
        boolean complete = sessionActive;
        for (FeatureEvidence feature : features) {
            if (!feature.enabled) continue;
            errors |= feature.error || feature.resolverFailed;
            if (feature.required) {
                // A real callback trigger proves the feature was loaded, its resolver reached a
                // hookable member and the installed hook executed. Requiring separate bookkeeping
                // flags made validation impossible for the normal (non-lazy) loader even when the
                // feature was visibly working on-device.
                complete &= feature.triggered;
                if (feature.manualRequired) complete &= feature.manualConfirmed;
            }
        }
        if (!errors && (knownValidatedBuild || complete)) return Compatibility.VALIDATED;
        return errors ? Compatibility.DEGRADED : Compatibility.RUNTIME_COMPATIBLE;
    }
}
