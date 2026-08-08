package com.waenhancer.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.waenhancer.testing.FakeSharedPreferences;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;

/** Any application on the device could previously make WhatsApp send a message. It cannot now. */
public class TaskerGuardTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String CALLER = "net.dinglisch.android.taskerm";

    private FakeSharedPreferences prefs;
    private TaskerGuard guard;

    @Before
    public void setUp() {
        prefs = new FakeSharedPreferences();
        guard = new TaskerGuard();
        prefs.edit()
                .putBoolean(TaskerGuard.KEY_ENABLED, true)
                .putString(TaskerGuard.KEY_SECRET, SECRET)
                .putStringSet(TaskerGuard.KEY_ALLOWED_PACKAGES,
                        new LinkedHashSet<>(Arrays.asList(CALLER)))
                .commit();
    }

    @Test
    public void anAuthorisedRequestIsAllowed() {
        assertEquals(TaskerGuard.Decision.ALLOWED,
                guard.evaluate(prefs, CALLER, SECRET, "5511999999999", "hello", 1_000L));
    }

    @Test
    public void integrationOffRefusesEverything() {
        prefs.edit().putBoolean(TaskerGuard.KEY_ENABLED, false).commit();
        assertEquals(TaskerGuard.Decision.DISABLED,
                guard.evaluate(prefs, CALLER, SECRET, "5511999999999", "hello", 1_000L));
    }

    @Test
    public void aMissingOrWrongTokenIsRefused() {
        assertEquals(TaskerGuard.Decision.BAD_TOKEN,
                guard.evaluate(prefs, CALLER, null, "5511999999999", "hello", 1_000L));
        assertEquals(TaskerGuard.Decision.BAD_TOKEN,
                guard.evaluate(prefs, CALLER, "wrong", "5511999999999", "hello", 1_000L));
        // A token that is a prefix of the real one must not pass either.
        assertEquals(TaskerGuard.Decision.BAD_TOKEN,
                guard.evaluate(prefs, CALLER, SECRET.substring(0, 10), "5511999999999",
                        "hello", 1_000L));
    }

    @Test
    public void anUnlistedPackageIsRefusedEvenWithTheRightToken() {
        assertEquals(TaskerGuard.Decision.PACKAGE_NOT_ALLOWED,
                guard.evaluate(prefs, "com.attacker.app", SECRET, "5511999999999",
                        "hello", 1_000L));
    }

    @Test
    public void anEmptyAllowlistRefusesEveryone() {
        prefs.edit().putStringSet(TaskerGuard.KEY_ALLOWED_PACKAGES, new LinkedHashSet<>()).commit();
        assertEquals(TaskerGuard.Decision.PACKAGE_NOT_ALLOWED,
                guard.evaluate(prefs, CALLER, SECRET, "5511999999999", "hello", 1_000L));
    }

    @Test
    public void anIdenticalRepeatIsDroppedInsideTheWindow() {
        guard.evaluate(prefs, CALLER, SECRET, "5511999999999", "hello", 1_000L);
        assertEquals(TaskerGuard.Decision.DUPLICATE,
                guard.evaluate(prefs, CALLER, SECRET, "5511999999999", "hello", 1_500L));
    }

    @Test
    public void theSameMessageIsAcceptedAgainAfterTheWindow() {
        guard.evaluate(prefs, CALLER, SECRET, "5511999999999", "hello", 1_000L);
        assertEquals(TaskerGuard.Decision.ALLOWED,
                guard.evaluate(prefs, CALLER, SECRET, "5511999999999", "hello",
                        1_000L + TaskerGuard.DEDUPE_WINDOW_MS + 1));
    }

    @Test
    public void aFloodIsRateLimited() {
        long now = 1_000L;
        for (int i = 0; i < TaskerGuard.RATE_LIMIT; i++) {
            assertEquals("send " + i, TaskerGuard.Decision.ALLOWED,
                    guard.evaluate(prefs, CALLER, SECRET, "551199999999" + i, "msg" + i, now + i));
        }
        assertEquals(TaskerGuard.Decision.RATE_LIMITED,
                guard.evaluate(prefs, CALLER, SECRET, "5511000000000", "overflow", now + 10));
    }

    @Test
    public void theRateLimitRecoversAfterTheWindow() {
        long now = 1_000L;
        for (int i = 0; i < TaskerGuard.RATE_LIMIT; i++) {
            guard.evaluate(prefs, CALLER, SECRET, "551199999999" + i, "msg" + i, now + i);
        }
        assertEquals(TaskerGuard.Decision.ALLOWED,
                guard.evaluate(prefs, CALLER, SECRET, "5511000000000", "later",
                        now + TaskerGuard.RATE_WINDOW_MS + 1));
    }

    @Test
    public void malformedRequestsAreRefused() {
        assertEquals(TaskerGuard.Decision.MALFORMED,
                guard.evaluate(prefs, CALLER, SECRET, null, "hello", 1_000L));
        assertEquals(TaskerGuard.Decision.MALFORMED,
                guard.evaluate(prefs, CALLER, SECRET, "  ", "hello", 1_000L));
        assertEquals(TaskerGuard.Decision.MALFORMED,
                guard.evaluate(prefs, CALLER, SECRET, "5511999999999", null, 1_000L));
    }

    @Test
    public void legacyModeSkipsAuthenticationButIsOffByDefault() {
        assertFalse(prefs.getBoolean(TaskerGuard.KEY_LEGACY_MODE, false));

        prefs.edit().putBoolean(TaskerGuard.KEY_LEGACY_MODE, true).commit();
        assertEquals(TaskerGuard.Decision.ALLOWED,
                guard.evaluate(prefs, "com.anything", null, "5511999999999", "hello", 1_000L));
    }

    @Test
    public void theMessageBodyIsNotBroadcastUnlessTheUserOptsIn() {
        assertFalse(TaskerGuard.mayIncludeBody(prefs));
        prefs.edit().putBoolean(TaskerGuard.KEY_INCLUDE_BODY, true).commit();
        assertTrue(TaskerGuard.mayIncludeBody(prefs));
    }

    @Test
    public void generatedSecretsAreLongAndDistinct() {
        String first = TaskerGuard.newSecret();
        String second = TaskerGuard.newSecret();
        assertEquals(48, first.length());
        assertNotEquals(first, second);
    }

    @Test
    public void tokenComparisonRejectsLengthMismatch() {
        assertFalse(TaskerGuard.constantTimeEquals(SECRET, SECRET + "x"));
        assertFalse(TaskerGuard.constantTimeEquals(SECRET, null));
        assertTrue(TaskerGuard.constantTimeEquals(SECRET, SECRET));
    }
}
