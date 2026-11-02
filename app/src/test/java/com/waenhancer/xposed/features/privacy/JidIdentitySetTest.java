package com.waenhancer.xposed.features.privacy;

import static org.junit.Assert.*;

import org.junit.Test;

public class JidIdentitySetTest {
    @Test public void unresolvedIdentitiesNeverMatch() {
        JidIdentitySet locked = new JidIdentitySet();
        locked.add(null, "123@lid", null); // LID-only locked chat
        assertFalse("contact without any identity", locked.matchesAny(null, null, null));
        assertFalse(locked.matchesAny("", null, ""));
        assertFalse(locked.matchesAny("555@s.whatsapp.net", "999@lid", "555"));
    }

    @Test public void matchesAcrossLidAndPhoneNamespaces() {
        JidIdentitySet locked = new JidIdentitySet();
        locked.add("555@s.whatsapp.net", null, "555");
        assertTrue(locked.matchesAny("555@s.whatsapp.net", null, null));
        assertTrue(locked.matchesAny(null, "777@lid", "555"));
        locked.add(null, "777@lid", null);
        assertTrue(locked.matchesAny(null, "777@lid", null));
    }

    @Test public void emptySet() {
        JidIdentitySet locked = new JidIdentitySet();
        locked.add((String) null);
        assertTrue(locked.isEmpty());
    }
}
