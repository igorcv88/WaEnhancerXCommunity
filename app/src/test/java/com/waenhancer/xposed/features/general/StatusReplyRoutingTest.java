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
}
