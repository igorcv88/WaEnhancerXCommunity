package com.waenhancer.xposed.features.devtools;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class InspectorSessionFormatTest {

    @Test
    public void acceptsFreshSession() {
        long now = 1_800_000_000_000L;
        InspectorSession session = InspectorFeature.parseSession("abc123|" + (now - 1_000L), now);
        assertNotNull(session);
        assertTrue(session.matches("abc123"));
    }

    @Test
    public void rejectsStaleRestoredSession() {
        long now = 1_800_000_000_000L;
        long stale = now - InspectorSession.IDLE_TIMEOUT_MILLIS - 1L;
        assertNull(InspectorFeature.parseSession("abc123|" + stale, now));
    }

    @Test
    public void rejectsFutureAndMalformedSessions() {
        long now = 1_800_000_000_000L;
        assertNull(InspectorFeature.parseSession("abc123|" + (now + 60_000L), now));
        assertNull(InspectorFeature.parseSession("abc123", now));
        assertNull(InspectorFeature.parseSession("|" + now, now));
        assertNull(InspectorFeature.parseSession("abc123|not-a-time", now));
    }
}
