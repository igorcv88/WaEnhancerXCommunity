package com.waenhancer.theme;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NativeBubbleDrawLedgerTest {
    @Test public void oneFactoryInDrawIsStillOwned() {
        NativeBubbleDrawLedger<Object> ledger = new NativeBubbleDrawLedger<>();
        Object bubble = new Object();
        ledger.mark(bubble);
        assertTrue(ledger.saw(bubble));
    }

    @Test public void replacedFactoryIsNotRevalidatedFromStaleFrame() {
        NativeBubbleDrawLedger<Object> ledger = new NativeBubbleDrawLedger<>();
        Object previous = new Object(), replacement = new Object();
        ledger.mark(replacement);
        assertFalse(ledger.saw(previous));
        assertTrue(ledger.saw(replacement));
    }

    @Test public void multipleFactoriesDoNotInvalidateEachOther() {
        NativeBubbleDrawLedger<Object> ledger = new NativeBubbleDrawLedger<>();
        Object quote = new Object(), bubble = new Object();
        ledger.mark(quote);
        ledger.mark(bubble);
        ledger.mark(quote);
        assertTrue(ledger.saw(bubble));
        assertTrue(ledger.saw(quote));
        assertFalse(ledger.saw(new Object()));
    }

    @Test public void equalButDifferentObjectIsNotTheSameFactory() {
        NativeBubbleDrawLedger<String> ledger = new NativeBubbleDrawLedger<>();
        String previous = new String("factory"), next = new String("factory");
        ledger.mark(next);
        assertFalse(ledger.saw(previous));
    }

    @Test public void emptyDrawDoesNotPreserveOldOwnership() {
        assertFalse(new NativeBubbleDrawLedger<>().saw(new Object()));
    }
}
