package com.waenhancer.xposed.features.general;

import org.junit.Test;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class RevocationMarkerLookupTest {
    @Test public void currentDurableEventWinsWithoutLinkLookup() {
        var result = RevocationMarkerLookup.resolve(new RevocationMarkerLookup.Match("current",1234), List.of("old"), key -> {
            throw new AssertionError("No compatibility lookup for a known current event");
        });
        assertEquals("current", result.key()); assertEquals(1234, result.timestamp());
    }
    @Test public void oldRecordedKeyRestoresMarkerForPreservedVoiceOrTextRow() {
        Map<String, Long> events = Map.of("revoked-original", 1234L);
        var result = RevocationMarkerLookup.resolve(RevocationMarkerLookup.Match.NONE,
                List.of("unrelated-addon", "revoked-original"), key -> events.containsKey(key)
                        ? new RevocationMarkerLookup.Match(key,events.get(key)) : RevocationMarkerLookup.Match.NONE);
        assertEquals("revoked-original", result.key()); assertEquals(1234, result.timestamp());
    }
    @Test public void multipleRecordedLinksAreAmbiguousRatherThanAChosenTimestamp() {
        assertEquals(RevocationMarkerLookup.Match.NONE,
                RevocationMarkerLookup.resolve(RevocationMarkerLookup.Match.NONE, List.of("one", "two"), key -> new RevocationMarkerLookup.Match(key,1234)));
    }
    @Test public void duplicateEvidenceIsNotAmbiguous() {
        assertEquals("old", RevocationMarkerLookup.resolve(RevocationMarkerLookup.Match.NONE, List.of("old", "old"), key -> new RevocationMarkerLookup.Match(key,1)).key());
    }
    @Test public void preservedContentAndUnknownLinksCannotInventRevocation() {
        assertEquals(RevocationMarkerLookup.Match.NONE,
                RevocationMarkerLookup.resolve(RevocationMarkerLookup.Match.NONE, List.of("unknown", "current"), key -> RevocationMarkerLookup.Match.NONE));
        assertEquals(RevocationMarkerLookup.Match.NONE,
                RevocationMarkerLookup.resolve(RevocationMarkerLookup.Match.NONE, List.of(), key -> new RevocationMarkerLookup.Match(key,1)));
    }
    @Test public void cacheIncludesAccountRowAndCurrentKey() {
        var lookup = new RevocationMarkerLookup();
        var id = new RevocationMarkerLookup.Identity("account-a", 42, "current");
        lookup.complete(id, new RevocationMarkerLookup.Match("old", 1234), 1);
        assertNotNull(lookup.cached(id, 2));
        assertNull(lookup.cached(new RevocationMarkerLookup.Identity("account-b", 42, "current"), 2));
        assertNull(lookup.cached(new RevocationMarkerLookup.Identity("account-a", 43, "current"), 2));
        assertNull(lookup.cached(new RevocationMarkerLookup.Identity("account-a", 42, "recycled-key"), 2));
    }
    @Test public void missingLinkCanBeRetriedInsteadOfBeingNegativeCachedForever() {
        var lookup = new RevocationMarkerLookup();
        var id = new RevocationMarkerLookup.Identity("a", 42, "key");
        lookup.complete(id, RevocationMarkerLookup.Match.NONE, 100);
        assertEquals(RevocationMarkerLookup.Match.NONE, lookup.cached(id, 5099));
        assertNull(lookup.cached(id, 5100));
        lookup.complete(id, new RevocationMarkerLookup.Match("old", 2), 5101);
        assertEquals(2, lookup.cached(id, 100000).timestamp());
    }
    @Test public void pendingWorkIsCoalescedBoundedAndReleasedOnRejection() {
        var lookup = new RevocationMarkerLookup();
        var first = new RevocationMarkerLookup.Identity("a", 1, "key");
        assertTrue(lookup.begin(first)); assertFalse(lookup.begin(first));
        for (int i = 2; i <= 64; i++) assertTrue(lookup.begin(new RevocationMarkerLookup.Identity("a", i, "key")));
        var next = new RevocationMarkerLookup.Identity("a", 65, "key");
        assertFalse(lookup.begin(next)); lookup.cancel(first); assertTrue(lookup.begin(next));
        lookup.complete(next, RevocationMarkerLookup.Match.NONE, 1); assertTrue(lookup.begin(first));
    }
    @Test public void cacheEvictsOldRowsInsteadOfRetainingConversationHistory() {
        var lookup = new RevocationMarkerLookup();
        for (int i = 1; i <= 257; i++) lookup.complete(new RevocationMarkerLookup.Identity("a", i, "key"),
                new RevocationMarkerLookup.Match("old", 1), 1);
        assertNull(lookup.cached(new RevocationMarkerLookup.Identity("a", 1, "key"), 1));
        assertNotNull(lookup.cached(new RevocationMarkerLookup.Identity("a", 257, "key"), 1));
    }
    @Test public void oldEventWithoutDeletionDateStillHasAMarker() {
        var current = new RevocationMarkerLookup.Match("legacy",0);
        assertTrue(current.known());
        assertSame(current, RevocationMarkerLookup.resolve(current,List.of(),key -> {
            throw new AssertionError("Already-known legacy event");
        }));
        var linked = RevocationMarkerLookup.resolve(RevocationMarkerLookup.Match.NONE,List.of("old"),
                key -> new RevocationMarkerLookup.Match(key,0));
        assertTrue(linked.known()); assertEquals(0,linked.timestamp());
    }
    @Test public void zeroDateMarkerIsNotExpiredAsANegativeMiss() {
        var lookup = new RevocationMarkerLookup();
        var id = new RevocationMarkerLookup.Identity("a",42,"current");
        lookup.complete(id,new RevocationMarkerLookup.Match("old",0),1);
        assertTrue(lookup.cached(id,100000).known());
    }

    @Test public void coalescedCompletionRefreshesReplacementBindingAfterFirstWasRecycled() {
        var lookup = new RevocationMarkerLookup();
        var id = new RevocationMarkerLookup.Identity("account",42,"current");
        var replacement = new RevocationMarkerLookup.Identity("account",43,"other");
        var bindings = new java.util.ArrayList<RevocationMarkerLookup.Identity>();
        bindings.add(id);
        assertTrue(lookup.begin(id));
        bindings.add(id); // Same message gets a second view while the worker is busy.
        assertFalse(lookup.begin(id));
        bindings.set(0,replacement); // The initiating view now belongs to another message.
        boolean[] marked = new boolean[2];
        int[] refreshes = {0};
        lookup.complete(id,new RevocationMarkerLookup.Match("old",0),100,() -> {
            refreshes[0]++;
            for (int index = 0; index < bindings.size(); index++) {
                var match = lookup.cached(bindings.get(index),100);
                marked[index] = match != null && match.known();
            }
        });
        assertEquals(1,refreshes[0]);
        assertFalse(marked[0]);
        assertTrue(marked[1]); // No extra native bind is needed to discover the cached marker.
    }

    @Test public void missingMarkerDoesNotCauseAdapterRefreshLoop() {
        var lookup = new RevocationMarkerLookup();
        var id = new RevocationMarkerLookup.Identity("account",42,"current");
        assertTrue(lookup.begin(id));
        lookup.complete(id,RevocationMarkerLookup.Match.NONE,100,() -> {
            throw new AssertionError("Unknown events must not trigger another adapter bind");
        });
        assertEquals(RevocationMarkerLookup.Match.NONE,lookup.cached(id,100));
    }

}
