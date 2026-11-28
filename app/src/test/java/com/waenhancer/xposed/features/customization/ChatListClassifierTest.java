package com.waenhancer.xposed.features.customization;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ChatListClassifierTest {
    static final class Jid {
        final String server;
        Jid(String server) { this.server = server; }
        public String getServer() { return server; }
    }

    /** Chat-info object of the hosts' list item (Cloneable, not a JID). */
    static final class ChatInfo implements Cloneable {
        long A00 = 1;
    }

    /** Shape of the list item on both hosts: A00 = chat info, A01 = JID, plus getJid(). */
    static final class Item {
        final ChatInfo A00;
        final Jid A01;
        Item(ChatInfo info, Jid jid) { A00 = info; A01 = jid; }
        public Jid getJid() { return A01; }
    }

    /** Same shape without the accessor: the JID-typed value must still be found. */
    static final class LegacyItem {
        final ChatInfo A00;
        final Jid A01;
        LegacyItem(ChatInfo info, Jid jid) { A00 = info; A01 = jid; }
    }

    static final class Header {
        final String title = "Archived";
    }

    @Test
    public void groupWithNonNullChatInfoGoesOnlyToGroups() {
        Item group = new Item(new ChatInfo(), new Jid("g.us"));
        assertTrue(ChatListClassifier.keep(group, true));
        assertFalse(ChatListClassifier.keep(group, false));
    }

    @Test
    public void contactWithNonNullChatInfoGoesOnlyToChats() {
        for (String server : new String[]{"s.whatsapp.net", "lid"}) {
            Item contact = new Item(new ChatInfo(), new Jid(server));
            assertFalse(ChatListClassifier.keep(contact, true));
            assertTrue(ChatListClassifier.keep(contact, false));
        }
    }

    @Test
    public void jidIsFoundByValueWhenAccessorIsMissing() {
        LegacyItem group = new LegacyItem(new ChatInfo(), new Jid("broadcast"));
        assertTrue(ChatListClassifier.keep(group, true));
        assertFalse(ChatListClassifier.keep(group, false));
    }

    @Test
    public void rowsWithoutChatStayInBothTabs() {
        assertTrue(ChatListClassifier.keep(new Header(), true));
        assertTrue(ChatListClassifier.keep(new Header(), false));
        assertTrue(ChatListClassifier.keep(null, true));
        assertTrue(ChatListClassifier.keep(new Item(new ChatInfo(), null), false));
    }
}
