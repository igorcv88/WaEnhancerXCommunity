package com.waenhancer.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;

import com.waenhancer.testing.FakeSharedPreferences;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;

/**
 * The rate limit and the duplicate filter must survive the entry point being rebuilt.
 *
 * <p>{@code TaskerMessageSentReceiver} is manifest-declared, so Android constructs a new
 * instance for every broadcast and drops it afterwards. A guard owned by that instance starts
 * each request with an empty window, and an allowlisted caller can send an unbounded burst
 * while the advertised five-per-ten-seconds limit never fires. These tests exercise the guard
 * the way the receiver reaches it — through {@link TaskerGuard#shared()} — so the regression
 * cannot come back unnoticed.</p>
 */
public class TaskerGuardSharedStateTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String CALLER = "net.dinglisch.android.taskerm";

    private FakeSharedPreferences prefs;

    @Before
    public void setUp() {
        // The guard is process-wide by design, so each test starts from a known window.
        TaskerGuard.shared().reset();
        prefs = new FakeSharedPreferences();
        prefs.edit()
                .putBoolean(TaskerGuard.KEY_ENABLED, true)
                .putString(TaskerGuard.KEY_SECRET, SECRET)
                .putStringSet(TaskerGuard.KEY_ALLOWED_PACKAGES,
                        new LinkedHashSet<>(Arrays.asList(CALLER)))
                .commit();
    }

    /** What the receiver does on each delivery: look the guard up again from scratch. */
    private TaskerGuard.Decision deliver(String number, String message, long now) {
        return TaskerGuard.shared().evaluate(prefs, CALLER, SECRET, number, message, now);
    }

    @Test
    public void theGuardIsOneInstanceForTheWholeProcess() {
        assertSame(TaskerGuard.shared(), TaskerGuard.shared());
    }

    @Test
    public void aBurstAcrossRebuiltReceiversIsRateLimited() {
        long start = 5_000_000L;
        for (int sent = 0; sent < TaskerGuard.RATE_LIMIT; sent++) {
            assertEquals("Send " + sent + " should be inside the limit",
                    TaskerGuard.Decision.ALLOWED, deliver("55119999999" + sent, "body " + sent, start + sent));
        }
        assertEquals(TaskerGuard.Decision.RATE_LIMITED,
                deliver("5511900000000", "one too many", start + TaskerGuard.RATE_LIMIT));
    }

    @Test
    public void theWindowReopensOnceItHasPassed() {
        long start = 6_000_000L;
        for (int sent = 0; sent < TaskerGuard.RATE_LIMIT; sent++) {
            deliver("55119999999" + sent, "body " + sent, start + sent);
        }
        assertEquals(TaskerGuard.Decision.RATE_LIMITED, deliver("5511900000000", "blocked", start + 10L));
        assertEquals(TaskerGuard.Decision.ALLOWED,
                deliver("5511900000000", "later", start + TaskerGuard.RATE_WINDOW_MS + 1_000L));
    }

    @Test
    public void anIdenticalRepeatAcrossRebuiltReceiversIsDeduplicated() {
        long start = 7_000_000L;
        assertEquals(TaskerGuard.Decision.ALLOWED, deliver("5511988887777", "same body", start));
        assertEquals(TaskerGuard.Decision.DUPLICATE, deliver("5511988887777", "same body", start + 100L));
    }

    @Test
    public void aRepeatAfterTheDedupeWindowIsAccepted() {
        long start = 8_000_000L;
        assertEquals(TaskerGuard.Decision.ALLOWED, deliver("5511988886666", "same body", start));
        assertEquals(TaskerGuard.Decision.ALLOWED,
                deliver("5511988886666", "same body", start + TaskerGuard.DEDUPE_WINDOW_MS + 1L));
    }

    /** A refused request must not consume a slot in the window. */
    @Test
    public void aRefusedRequestDoesNotCountAgainstTheLimit() {
        long start = 9_000_000L;
        for (int attempt = 0; attempt < 20; attempt++) {
            assertNotEquals(TaskerGuard.Decision.ALLOWED,
                    TaskerGuard.shared().evaluate(prefs, CALLER, "wrong token",
                            "5511977776666", "body " + attempt, start + attempt));
        }
        assertEquals(TaskerGuard.Decision.ALLOWED, deliver("5511977776666", "genuine", start + 100L));
    }
}
