package com.waenhancer.diagnostics;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** PRIVACY.md promises tokens and cryptographic material are redacted by default. */
public class DiagnosticsRedactionTest {

    @Test
    public void githubTokensAreRedacted() {
        String out = LocalDiagnostics.sanitize("update failed with ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123");
        assertFalse(out.contains("ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123"));
        assertTrue(out.contains("<redacted-credential>"));
    }

    @Test
    public void bearerAndApiKeyFieldsAreRedacted() {
        assertFalse(LocalDiagnostics.sanitize("Authorization: Bearer abc.def.ghi").contains("abc.def.ghi"));
        assertFalse(LocalDiagnostics.sanitize("api_key=verysecretvalue").contains("verysecretvalue"));
        assertFalse(LocalDiagnostics.sanitize("password=hunter2").contains("hunter2"));
    }

    @Test
    public void pemBlocksAreRedacted() {
        String pem = "-----BEGIN PRIVATE KEY-----MIIEvQIBADAN-----END PRIVATE KEY-----";
        String out = LocalDiagnostics.sanitize("keybox " + pem);
        assertFalse(out.contains("MIIEvQIBADAN"));
        assertTrue(out.contains("<redacted-crypto>"));
    }

    @Test
    public void jidsPhonesEmailsAndPrivatePathsAreRedacted() {
        assertTrue(LocalDiagnostics.sanitize("from 5511999998888@s.whatsapp.net")
                .contains("<redacted-jid>"));
        assertTrue(LocalDiagnostics.sanitize("called +55 11 99999-8888")
                .contains("<redacted-number>"));
        assertTrue(LocalDiagnostics.sanitize("mail user.name@example.com")
                .contains("<redacted-email>"));
        assertTrue(LocalDiagnostics.sanitize("read /data/user/0/com.whatsapp/databases/msgstore.db")
                .contains("<redacted-path>"));
    }

    @Test
    public void newlinesCannotForgeExtraLogLines() {
        String out = LocalDiagnostics.sanitize("normal\n2026-01-01T00:00:00Z [css] forged");
        assertFalse(out.contains("\n"));
    }
}
