package com.waenhancer.xposed.features.general;

import org.junit.Test;
import static org.junit.Assert.*;

public class StatusReplyRoutingTest {
    @Test public void replyToVisibleAuthorUsesStatusRoute() {
        assertTrue(StatusReplyRouting.matches(true, "123@s.whatsapp.net", null,
                "123@s.whatsapp.net", "789@lid"));
    }
    @Test public void lidMatchWorksWhenPhoneMappingIsUnavailable() {
        assertTrue(StatusReplyRouting.matches(true, null, "789@lid", null, "789@lid"));
    }
    @Test public void unrelatedRecipientKeepsChatRoute() {
        assertFalse(StatusReplyRouting.matches(true, "456@s.whatsapp.net", "456@lid",
                "123@s.whatsapp.net", "789@lid"));
    }
    @Test public void staleOrBackgroundStatusKeepsChatRoute() {
        assertFalse(StatusReplyRouting.matches(false, "123@s.whatsapp.net", "789@lid",
                "123@s.whatsapp.net", "789@lid"));
    }
    @Test public void missingIdentifiersNeverAuthorizeStatus() {
        assertFalse(StatusReplyRouting.matches(true, null, null, null, null));
        assertFalse(StatusReplyRouting.matches(true, "", "", "", ""));
    }
    @Test public void publishingOwnStatusDoesNotReleaseViewedAuthors() {
        assertFalse(StatusReplyRouting.matches(true, "status@broadcast", "status@broadcast",
                "123@s.whatsapp.net", "789@lid"));
    }
    private record Item(String id, boolean incomingStatus) { }

    @Test public void delayedReplyReleasesQuotedAEvenWhenViewerHasAdvancedToB() {
        var a = new Item("A", true);
        var b = new Item("B", true);
        var selected = StatusReplyRouting.selectQuoted("A", a, Item::id, Item::incomingStatus);
        assertEquals(java.util.List.of(a), selected);
        assertFalse(selected.contains(b));
        assertTrue(StatusReplyRouting.selectQuoted("A", b, Item::id, Item::incomingStatus).isEmpty());
    }
    @Test public void unresolvedQuoteNeverFallsBackToVisibleItem() {
        assertTrue(StatusReplyRouting.selectQuoted("A", null, Item::id, Item::incomingStatus).isEmpty());
        assertTrue(StatusReplyRouting.selectQuoted(null, new Item("B", true),
                Item::id, Item::incomingStatus).isEmpty());
        assertTrue(StatusReplyRouting.selectQuoted("", new Item("", true),
                Item::id, Item::incomingStatus).isEmpty());
    }
    @Test public void ownStatusOrChatQuoteCannotAuthorizeStatusRelease() {
        assertTrue(StatusReplyRouting.selectQuoted("A", new Item("A", false),
                Item::id, Item::incomingStatus).isEmpty());
    }
    @Test public void queuedSelectionIsImmutableAndIndependentOfViewer() {
        var a = new Item("A", true);
        var viewer = new java.util.ArrayList<>(java.util.List.of(a));
        var selected = StatusReplyRouting.selectQuoted("A", viewer.get(0), Item::id, Item::incomingStatus);
        viewer.clear();
        viewer.add(new Item("B", true));
        assertEquals(java.util.List.of(a), selected);
        try { selected.add(viewer.get(0)); fail("selection must be immutable"); }
        catch (UnsupportedOperationException expected) { }
    }
}
