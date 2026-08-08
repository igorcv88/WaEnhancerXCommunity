package com.waenhancer.diagnostics;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LocalDiagnosticsTest {

    @Test
    public void redactsJidsPhoneNumbersAndPrivatePaths() {
        String raw = "jid=5511999999999@s.whatsapp.net, number=+55 11 99999-9999, "
                + "path=/data/user/0/com.whatsapp/files/private.db";
        String safe = LocalDiagnostics.sanitize(raw);

        assertFalse(safe.contains("5511999999999"));
        assertFalse(safe.contains("99999-9999"));
        assertFalse(safe.contains("/data/user/0"));
        assertTrue(safe.contains("<redacted>"));
    }

    @Test
    public void truncatesUnexpectedlyLargeEvents() {
        String safe = LocalDiagnostics.sanitize("x".repeat(5000));
        assertTrue(safe.length() <= 2001);
    }
}
