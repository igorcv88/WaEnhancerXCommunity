package com.waenhancer.diagnostics;

import org.junit.Test;

import java.util.List;

import static com.waenhancer.diagnostics.ValidationModel.*;
import static org.junit.Assert.*;

public class ValidationModelTest {
    @Test
    public void missingOrLegacySnapshotIsUnknown() {
        assertEquals(Compatibility.UNKNOWN, aggregate(false, true, false, List.of()));
        assertEquals(Compatibility.UNKNOWN, aggregate(false, false, false, List.of()));
    }

    @Test
    public void coreAndOptionalResultsAreIndependent() {
        assertEquals(Compatibility.INCOMPATIBLE, aggregate(true, false, false, List.of()));
        assertEquals(Compatibility.DEGRADED, aggregate(true, true, true, List.of()));
        assertEquals(Compatibility.RUNTIME_COMPATIBLE, aggregate(true, true, false, List.of()));
    }

    @Test
    public void smokeCannotPromoteOverviewAndEmptyCannotPass() {
        assertFalse(smokeConfirmed(List.of()));
        var e = observedRequiredCase();
        assertTrue(smokeConfirmed(List.of(e)));
        assertEquals(Compatibility.RUNTIME_COMPATIBLE, aggregate(true, true, false, List.of(e)));
    }

    @Test
    public void disabledUninstrumentedOrUnexercisedRequiredCasesCannotPass() {
        var e = observedRequiredCase();
        e.enabled = false;
        assertEquals(Status.DISABLED, e.state());
        assertFalse(smokeConfirmed(List.of(e)));
        e.enabled = true;
        e.instrumented = false;
        assertEquals(Status.NOT_INSTRUMENTED, e.state());
        assertFalse(smokeConfirmed(List.of(e)));
        e.instrumented = true;
        e.triggered = false;
        assertEquals(Status.NOT_EXERCISED, e.state());
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
        assertEquals(Compatibility.DEGRADED, aggregate(true, true, false, List.of(e)));
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

    private static FeatureEvidence observedRequiredCase() {
        var e = new FeatureEvidence();
        e.required = true;
        e.instrumented = true;
        e.loaded = true;
        e.triggered = true;
        e.manualConfirmed = true;
        return e;
    }
}
