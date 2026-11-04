package com.waenhancer.xposed.features.general;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HomeInlineSearchMatcherTest {

    @Test public void searchesAccentInsensitiveContactAndGroupNames() {
        assertTrue(HomeInlineSearchMatcher.matches("joao", "João da Silva", null, null));
        assertTrue(HomeInlineSearchMatcher.matches("  CLINICA ", "Direção Clínica", null, null));
        assertFalse(HomeInlineSearchMatcher.matches("maria", "João da Silva", null, null));
    }

    @Test public void searchesFormattedOrBarePhoneNumbers() {
        assertTrue(HomeInlineSearchMatcher.matches("85 8170", "Contact", "+55 (85) 8170-6013", null));
        assertTrue(HomeInlineSearchMatcher.matches("55858170", "Contact", null, "558581706013@s.whatsapp.net"));
        assertFalse(HomeInlineSearchMatcher.matches("558521", "Contact", "558581706013", null));
    }

    @Test public void emptyQueryRetainsAllRows() {
        assertTrue(HomeInlineSearchMatcher.matches(" ", null, null, null));
        assertFalse(HomeInlineSearchMatcher.matches("test", null, null, null));
    }

    @Test public void aTextQueryDoesNotAccidentallyMatchNumberFragments() {
        assertFalse(HomeInlineSearchMatcher.matches("john", "Peter", "558581706013", null));
    }
}
