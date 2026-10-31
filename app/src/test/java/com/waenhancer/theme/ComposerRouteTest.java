package com.waenhancer.theme;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class ComposerRouteTest {
    @Test public void fiveNativeExitCyclesIgnoreRetainedStub() {
        for (int cycle = 0; cycle < 5; cycle++) {
            assertEquals(ComposerRoute.Panel.STUB, ComposerRoute.resolve(false, false, true));
            assertEquals(ComposerRoute.Panel.DRAFT, ComposerRoute.resolve(true, false, true));
            assertEquals(ComposerRoute.Panel.INPUT, ComposerRoute.resolve(false, true, true));
        }
    }
    @Test public void draftWinsOverOldInputAndStubDuringNativeSwap() {
        assertEquals(ComposerRoute.Panel.DRAFT, ComposerRoute.resolve(true, true, true));
    }
    @Test public void missingLayoutIsNotInventedInput() {
        assertEquals(ComposerRoute.Panel.UNRESOLVED, ComposerRoute.resolve(false, false, false));
    }
}
