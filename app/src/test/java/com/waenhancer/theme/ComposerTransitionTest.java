package com.waenhancer.theme;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.waenhancer.theme.ComposerTransition.*;

public class ComposerTransitionTest {
    private static ComposerTransition settledInput() {
        ComposerTransition t = new ComposerTransition();
        t.sample(Route.INPUT, "170", 0);
        assertEquals(PRESENT | RESERVE, t.sample(Route.INPUT, "170", 16));
        return t;
    }

    @Test public void firstInputWaitsForTwoCoherentFrames() {
        ComposerTransition t = new ComposerTransition();
        assertEquals(0, t.sample(Route.INPUT, "a", 0));
        assertTrue(t.pending());
        assertEquals(0, t.sample(Route.INPUT, "b", 16));
        assertEquals(PRESENT | RESERVE, t.sample(Route.INPUT, "b", 32));
        assertFalse(t.pending());
    }

    /** Device trace 11:29:44: 155 → 429 (137 ms) → 444 (1.25 s). */
    @Test public void recorderGrowthIsPresentedEveryFrameAndReservedOnlyWhenQuiet() {
        ComposerTransition t = settledInput();
        assertTrue(t.signal(true, 1000));
        assertEquals(PRESENT, t.sample(Route.VOICE, "155", 1010));
        assertEquals(PRESENT, t.sample(Route.VOICE, "429", 1147));
        assertEquals(0, t.sample(Route.VOICE, "429", 1163));
        assertEquals(RESERVE, t.sample(Route.VOICE, "429", 1227));
        assertEquals(State.RECORD_STABLE, t.state());
        assertEquals(PRESENT, t.sample(Route.VOICE, "444", 2262));
        assertEquals(RESERVE, t.sample(Route.VOICE, "444", 2342));
        assertEquals(10, t.startToMeasuredMs());
    }

    @Test public void unresolvedFramesHoldTheLastPaneInsteadOfHidingIt() {
        ComposerTransition t = settledInput();
        t.signal(true, 100);
        t.sample(Route.VOICE, "400", 116);
        t.signal(false, 500);
        assertEquals(HOLD, t.sample(Route.UNRESOLVED, null, 510));
        assertEquals(HOLD, t.sample(Route.UNRESOLVED, null, 900));
        assertEquals(PRESENT, t.sample(Route.INPUT, "170", 920));
        assertEquals(State.NORMAL_LAYOUT_PENDING, t.state());
        assertEquals(420, t.endToNormalMs());
    }

    @Test public void bridgeIsBounded() {
        ComposerTransition t = settledInput();
        assertEquals(HOLD, t.sample(Route.UNRESOLVED, null, 100));
        assertEquals(HIDE, t.sample(Route.UNRESOLVED, null, 101 + BRIDGE_MAX_MS));
        assertEquals(0, t.sample(Route.UNRESOLVED, null, 2000));
    }

    @Test public void newRecorderEpochDoesNotInheritOldUnresolvedTimeout() {
        ComposerTransition t = settledInput();
        assertEquals(HOLD, t.sample(Route.UNRESOLVED, null, 100));
        assertTrue(t.signal(true, 795));
        assertEquals(HOLD, t.sample(Route.UNRESOLVED, null, 805));
        assertEquals(HOLD, t.sample(Route.UNRESOLVED, null, 1200));
        assertEquals(PRESENT, t.sample(Route.VOICE, "400", 1216));
    }

    @Test public void cancelSignalStartsIndependentBridgeAfterRecordStart() {
        ComposerTransition t = settledInput();
        t.signal(true, 100);
        assertEquals(HOLD, t.sample(Route.UNRESOLVED, null, 110));
        t.signal(false, 805);
        assertEquals(HOLD, t.sample(Route.UNRESOLVED, null, 815));
        assertEquals(PRESENT, t.sample(Route.INPUT, "190", 830));
        assertEquals(25, t.endToNormalMs());
    }

    @Test public void signalBeforeSwapDoesNotReverseOnStaleInputFrame() {
        ComposerTransition t = settledInput();
        long epoch = t.epoch();
        t.signal(true, 100);
        assertEquals(0, t.sample(Route.INPUT, "170", 110) & RESERVE);
        assertEquals(State.RECORD_START_SIGNAL, t.state());
        assertTrue(t.recording());
        assertEquals(epoch + 1, t.epoch());
    }

    @Test public void staleSignalExpiresIntoTheMeasuredRoute() {
        ComposerTransition t = settledInput();
        t.signal(true, 100);
        t.sample(Route.INPUT, "170", 100 + BRIDGE_MAX_MS + 1);
        assertFalse(t.recording());
    }

    @Test public void repeatedSignalsInOneEpochAreIgnored() {
        ComposerTransition t = settledInput();
        assertTrue(t.signal(true, 1));
        assertFalse(t.signal(true, 2));
        assertTrue(t.signal(false, 3));
        assertFalse(t.signal(false, 4));
    }

    @Test public void detachForgetsGeometry() {
        ComposerTransition t = settledInput();
        t.detach();
        assertEquals(State.DETACHED, t.state());
        assertEquals(0, t.sample(Route.INPUT, "170", 100));
    }
}
