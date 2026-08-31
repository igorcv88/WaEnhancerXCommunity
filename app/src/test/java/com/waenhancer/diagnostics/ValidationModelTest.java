package com.waenhancer.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class ValidationModelTest {
    @Test public void installedIsNotWorkingUntilCallbackTriggers() {
        ValidationModel.FeatureEvidence feature = new ValidationModel.FeatureEvidence();
        feature.loaded = true; feature.resolverPassed = true; feature.installed = true;
        assertEquals(ValidationModel.FeatureState.NOT_EXERCISED, feature.state());
        feature.opportunity = true;
        assertEquals(ValidationModel.FeatureState.INSTALLED, feature.state());
        feature.triggered = true;
        assertEquals(ValidationModel.FeatureState.TRIGGERED, feature.state());
    }

    @Test public void lazyFeatureIsNotFailedBeforeOpportunity() {
        ValidationModel.FeatureEvidence feature = new ValidationModel.FeatureEvidence();
        assertEquals(ValidationModel.FeatureState.NOT_EXERCISED, feature.state());
    }

    @Test public void coreFailureAlwaysMeansIncompatible() {
        assertEquals(ValidationModel.Compatibility.INCOMPATIBLE,
                ValidationModel.aggregate(false, false, true, true, List.of()));
    }

    @Test public void runtimeProbeNeverAutomaticallyValidates() {
        assertEquals(ValidationModel.Compatibility.RUNTIME_COMPATIBLE,
                ValidationModel.aggregate(true, false, false, false, List.of()));
    }

    @Test public void optionalFailureIsDegraded() {
        assertEquals(ValidationModel.Compatibility.DEGRADED,
                ValidationModel.aggregate(true, true, false, true, List.of()));
    }

    @Test public void requiredEvidenceAndManualConfirmationCanValidateSession() {
        ValidationModel.FeatureEvidence feature = new ValidationModel.FeatureEvidence();
        feature.required = true; feature.manualRequired = true; feature.manualConfirmed = true;
        feature.loaded = true; feature.resolverPassed = true; feature.installed = true;
        feature.opportunity = true; feature.triggered = true;
        assertEquals(ValidationModel.Compatibility.VALIDATED,
                ValidationModel.aggregate(true, false, false, true, List.of(feature)));
    }

    @Test public void disabledFeatureDoesNotBlockValidation() {
        ValidationModel.FeatureEvidence feature = new ValidationModel.FeatureEvidence();
        feature.required = true; feature.enabled = false;
        assertEquals(ValidationModel.Compatibility.VALIDATED,
                ValidationModel.aggregate(true, false, false, true, List.of(feature)));
    }

    @Test public void preSessionEventsCannotCountAsSessionEvidence() {
        assertFalse(ValidationModel.occurredDuringSession(999L, 1_000L));
        assertFalse(ValidationModel.occurredDuringSession(0L, 1_000L));
        assertTrue(ValidationModel.occurredDuringSession(1_000L, 1_000L));
        assertTrue(ValidationModel.occurredDuringSession(1_001L, 1_000L));
    }
}
