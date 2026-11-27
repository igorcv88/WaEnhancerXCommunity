package com.waenhancer.diagnostics;

import org.junit.Test;

import java.util.List;

import static com.waenhancer.diagnostics.ValidationModel.*;
import static org.junit.Assert.*;

public class ValidationModelTest {
    @Test
    public void missingOrLegacySnapshotIsUnknown() {
        assertEquals(Compatibility.UNKNOWN, aggregate(false, true, false, Loader.COMPLETE, List.of()));
        assertEquals(Compatibility.UNKNOWN, aggregate(false, false, false, Loader.COMPLETE, List.of()));
    }

    @Test
    public void coreAndOptionalResultsAreIndependent() {
        assertEquals(Compatibility.INCOMPATIBLE, aggregate(true, false, false, Loader.COMPLETE, List.of()));
        assertEquals(Compatibility.DEGRADED, aggregate(true, true, true, Loader.COMPLETE, List.of()));
        assertEquals(Compatibility.RUNTIME_COMPATIBLE, aggregate(true, true, false, Loader.COMPLETE, List.of()));
    }

    @Test
    public void snapshotWithoutLoaderCompletionCannotBeCompatible() {
        // An early snapshot can predate the features that fail: it is unknown, not compatible.
        assertEquals(Compatibility.UNKNOWN, aggregate(true, true, false, Loader.ABSENT, List.of()));
        assertEquals(Compatibility.DEGRADED, aggregate(true, true, false, Loader.TIMED_OUT, List.of()));
    }

    @Test
    public void anyRegisteredFeatureFailureDegrades() {
        var e = installed();
        e.error = true;
        assertEquals(Compatibility.DEGRADED, aggregate(true, true, false, Loader.COMPLETE, List.of(e)));
        e = installed();
        e.degraded = true;
        assertEquals(Status.DEGRADED_INSTALL, e.state());
        assertEquals(Compatibility.DEGRADED, aggregate(true, true, false, Loader.COMPLETE, List.of(e)));
        e = installed();
        e.installed = false;
        assertEquals(Status.INSTALL_INCOMPLETE, e.state());
        assertEquals(Compatibility.DEGRADED, aggregate(true, true, false, Loader.COMPLETE, List.of(e)));
    }

    @Test
    public void failureOfDisabledFeatureDoesNotDegrade() {
        var e = installed();
        e.error = true;
        e.enablement = Enablement.DISABLED;
        assertEquals(Status.DISABLED, e.state());
        assertEquals(Compatibility.RUNTIME_COMPATIBLE, aggregate(true, true, false, Loader.COMPLETE, List.of(e)));
    }

    @Test
    public void unmodelledEnablementIsNeverShownAsNotExercised() {
        var e = installed();
        e.instrumented = true;
        e.enablement = Enablement.UNKNOWN;
        assertEquals(Status.ENABLEMENT_UNKNOWN, e.state());
        e.enablement = Enablement.ENABLED;
        assertEquals(Status.NOT_EXERCISED, e.state());
    }

    @Test
    public void reachingTheHookIsNotActing() {
        var e = observedRequiredCase();
        e.acted = false;
        e.reached = true;
        assertEquals(Status.REACHED_NOT_ACTED, e.state());
        assertFalse(smokeConfirmed(List.of(e)));
    }

    @Test
    public void smokeCannotPromoteOverviewAndEmptyCannotPass() {
        assertFalse(smokeConfirmed(List.of()));
        var e = observedRequiredCase();
        assertTrue(smokeConfirmed(List.of(e)));
        assertEquals(Compatibility.RUNTIME_COMPATIBLE, aggregate(true, true, false, Loader.COMPLETE, List.of(e)));
    }

    @Test
    public void disabledUninstrumentedOrUnexercisedRequiredCasesCannotPass() {
        var e = observedRequiredCase();
        e.enablement = Enablement.DISABLED;
        assertEquals(Status.DISABLED, e.state());
        assertFalse(smokeConfirmed(List.of(e)));
        e.enablement = Enablement.UNKNOWN;
        assertFalse(smokeConfirmed(List.of(e)));
        e.enablement = Enablement.ENABLED;
        e.instrumented = false;
        assertEquals(Status.NOT_INSTRUMENTED, e.state());
        assertFalse(smokeConfirmed(List.of(e)));
        e.instrumented = true;
        e.acted = false;
        e.reached = false;
        assertEquals(Status.NOT_EXERCISED, e.state());
        assertFalse(smokeConfirmed(List.of(e)));
        e.acted = true;
        e.manualConfirmed = false;
        assertFalse(smokeConfirmed(List.of(e)));
    }

    @Test
    public void oldTriggerCannotHideLaterErrors() {
        var e = observedRequiredCase();
        e.error = true;
        assertEquals(Status.INSTALL_FAILED, e.state());
        assertFalse(smokeConfirmed(List.of(e)));
        e.resolverFailed = true;
        assertEquals(Status.RESOLVER_FAILED, e.state());
        assertEquals(Compatibility.DEGRADED, aggregate(true, true, false, Loader.COMPLETE, List.of(e)));
    }

    @Test
    public void resolverMissRecoveredByFallbackIsNotAFailure() {
        var e = installed();
        e.resolverFailed = true;
        assertNotEquals(Status.RESOLVER_FAILED, e.state());
        assertEquals(Compatibility.RUNTIME_COMPATIBLE, aggregate(true, true, false, Loader.COMPLETE, List.of(e)));
    }

    @Test
    public void featureNotLoadedInThisProcessIsNotTested() {
        var e = new FeatureEvidence();
        e.enablement = Enablement.ENABLED;
        assertEquals(Status.NOT_TESTED, e.state());
    }

    @Test
    public void processRestartNewSessionAndConfigurationChangeInvalidateEvidence() {
        assertTrue(sameRun("session", "process", "cfg", "session", "process", "cfg"));
        assertFalse(sameRun("session", "process", "cfg", "previous", "process", "cfg"));
        assertFalse(sameRun("session", "process", "cfg", "session", "previous", "cfg"));
        assertFalse(sameRun("session", "process", "cfg", "session", "process", "previous"));
        assertFalse(sameRun("", "", "", "", "", ""));
        assertFalse(occurredDuringSession(999, 1_000, 1_010));
        assertFalse(occurredDuringSession(1_011, 1_000, 1_010));
        assertTrue(occurredDuringSession(1_005, 1_000, 1_010));
    }

    @Test
    public void officialRecordRequiresExactIdentityAndExplicitScope() {
        String exact = "a".repeat(64);
        assertTrue(matchesOfficialRecord(exact, exact, "two receipt cases", "physical endpoints"));
        assertFalse(matchesOfficialRecord(exact, "b".repeat(64), "two receipt cases", "physical endpoints"));
        assertFalse(matchesOfficialRecord(exact, exact, "", "physical endpoints"));
        assertFalse(matchesOfficialRecord(exact, exact, "two receipt cases", ""));
        assertFalse(matchesOfficialRecord("2.26.46.82", "2.26.46.82", "scope", "legacy"));
    }

    private static FeatureEvidence installed() {
        var e = new FeatureEvidence();
        e.enablement = Enablement.ENABLED;
        e.loaded = true;
        e.installed = true;
        return e;
    }

    private static FeatureEvidence observedRequiredCase() {
        var e = installed();
        e.required = true;
        e.instrumented = true;
        e.reached = true;
        e.acted = true;
        e.manualConfirmed = true;
        return e;
    }
}
