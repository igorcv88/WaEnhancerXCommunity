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
            if (!loaded) return opportunity ? FeatureState.NOT_LOADED : FeatureState.NOT_EXERCISED;
            if (!resolverPassed) return FeatureState.RESOLVER_FAILED;
            if (triggered) return FeatureState.TRIGGERED;
            if (installed && opportunity) return FeatureState.INSTALLED; // installed, but apparently dead
            if (installed) return FeatureState.NOT_EXERCISED;
            return FeatureState.NOT_LOADED;
        }
    }

    public static Compatibility aggregate(boolean corePassed, boolean optionalFailed,
            boolean knownValidatedBuild, boolean sessionActive, Collection<FeatureEvidence> features) {
        if (!corePassed) return Compatibility.INCOMPATIBLE;
        boolean errors = optionalFailed;
        boolean complete = sessionActive;
        for (FeatureEvidence feature : features) {
            if (!feature.enabled) continue;
            errors |= feature.error || (feature.required && feature.loaded && !feature.resolverPassed);
            if (feature.required) {
                // A real callback trigger already proves that an opportunity occurred. Requiring a
                // separate coarse surface marker here made a session impossible to validate when the
                // feature was correctly instrumented but the generic activity observer did not know
                // about that WhatsApp surface.
                complete &= feature.loaded && feature.installed && feature.triggered;
                if (feature.manualRequired) complete &= feature.manualConfirmed;
            }
        }
        if (!errors && (knownValidatedBuild || complete)) return Compatibility.VALIDATED;
        return errors ? Compatibility.DEGRADED : Compatibility.RUNTIME_COMPATIBLE;
    }
}
