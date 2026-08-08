package com.waenhancer.backup;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.waenhancer.testing.FakeSharedPreferences;

import org.junit.Test;

/** An export must be able to say what it is leaving behind, rather than dropping it silently. */
public class BackupExclusionTest {

    @Test
    public void secretsThatAreSetAreNamedInTheNotice() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        prefs.edit()
                .putString("groq_api_key", "gsk_example")
                .putString("assemblyai_key", "aai_example")
                .putBoolean("floating_bottom_bar", true)
                .commit();

        BackupCodec.ExcludedSummary excluded = BackupCodec.excludedFrom(prefs);

        assertTrue(excluded.hasSecrets());
        assertTrue(excluded.secrets.contains("groq_api_key"));
        assertTrue(excluded.secrets.contains("assemblyai_key"));
        String notice = excluded.secretsNotice();
        assertTrue(notice, notice.contains("Groq API key"));
        assertTrue(notice, notice.contains("AssemblyAI API key"));
        // The notice must never contain the secret itself.
        assertFalse(notice, notice.contains("gsk_example"));
        assertFalse(notice, notice.contains("aai_example"));
    }

    @Test
    public void noNoticeWhenNoSecretIsSet() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        prefs.edit().putBoolean("floating_bottom_bar", true).commit();

        BackupCodec.ExcludedSummary excluded = BackupCodec.excludedFrom(prefs);

        assertFalse(excluded.hasSecrets());
        assertNull(excluded.secretsNotice());
    }

    @Test
    public void anEmptySecretIsNotReportedAsSet() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        prefs.edit().putString("groq_api_key", "").commit();

        assertFalse(BackupCodec.excludedFrom(prefs).hasSecrets());
    }
}
