package com.waenhancer.theme;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Pure-Java, per-process budget for the opt-in view probe. Critical recorder transitions must
 * remain observable after a noisy message list has generated unrelated timer/capture events.
 * This class schedules no work; callers provide monotonic timestamps from the UI thread.
 */
public final class GlassProbeBudget {
    public static final int MAX_EVENTS_PER_MINUTE = 450;
    public static final int TRANSITION_RESERVE = 150;
    private static final int MAX_PER_EVENT_PER_MINUTE = 55;
    private static final int MAX_NOISY_PER_EVENT_PER_MINUTE = 8;
    private static final long CAPTURE_SAMPLE_INTERVAL_MS = 1_500L;
    private static final long REPEAT_SAMPLE_INTERVAL_MS = 2_000L;

    private final Map<String, Integer> eventCounts = new HashMap<>();
    private final Map<String, Long> lastEmittedAt = new HashMap<>();
    private final Map<String, String> lastEmittedDetails = new HashMap<>();
    private long windowStart = -1L;
    private int count, backgroundCount;

    /** True only when a new minute begins (the caller emits its one INFO activation marker). */
    public boolean beginWindow(long now) {
        if (windowStart >= 0L && now >= windowStart && now - windowStart < 60_000L) {
            return false;
        }
        windowStart = now;
        count = 0;
        backgroundCount = 0;
        eventCounts.clear();
        lastEmittedAt.clear();
        lastEmittedDetails.clear();
        return true;
    }

    /** Allow a bounded diagnostic line; callers must invoke beginWindow() first. */
    public boolean allow(String type, int ownerIdentity, String details, long now) {
        int repetitions = eventCounts.containsKey(type) ? eventCounts.get(type) : 0;
        boolean transition = isTransitionEvent(type);
        int typeLimit = isNoisyEvent(type) ? MAX_NOISY_PER_EVENT_PER_MINUTE
                : MAX_PER_EVENT_PER_MINUTE;
        if (count >= MAX_EVENTS_PER_MINUTE || repetitions >= typeLimit
                || (!transition && backgroundCount >= MAX_EVENTS_PER_MINUTE - TRANSITION_RESERVE)) {
            return false;
        }
        // Same-view captures are sampled across the test rather than consuming the budget on
        // each frame. A changed route/geometry snapshot is still emitted without delay.
        String identity = type + "@" + ownerIdentity;
        Long previousAt = lastEmittedAt.get(identity);
        if (previousAt != null) {
            long elapsed = now - previousAt;
            if (isCaptureEvent(type) && elapsed < CAPTURE_SAMPLE_INTERVAL_MS) return false;
            if (elapsed < REPEAT_SAMPLE_INTERVAL_MS
                    && Objects.equals(details, lastEmittedDetails.get(identity))) return false;
        }
        eventCounts.put(type, repetitions + 1);
        count++;
        if (!transition) backgroundCount++;
        lastEmittedAt.put(identity, now);
        lastEmittedDetails.put(identity, details);
        return true;
    }

    private static boolean isNoisyEvent(String type) {
        switch (type) {
            case "NATIVE_TIMER_CANDIDATE":
            case "RECORDER_SIGNAL_OUTSIDE_FOOTER":
            case "RECORDER_SIGNAL_UNROUTED":
            case "BIND_ATTEMPT":
            case "DISCOVERY_START":
            case "DISCOVERY_END":
                return true;
            default:
                return false;
        }
    }

    private static boolean isCaptureEvent(String type) {
        return type.equals("CAPTURE_ATTEMPT") || type.equals("CAPTURE_RESULT")
                || type.equals("DRAWABLE_CAPTURE_ATTEMPT")
                || type.equals("DRAWABLE_CAPTURE_RESULT") || type.equals("PANE_DRAW");
    }

    private static boolean isTransitionEvent(String type) {
        switch (type) {
            case "NATIVE_VISIBILITY_HOOK":
            case "NATIVE_INFLATE_HOOK":
            case "RECORDER_SIGNAL":
            case "RECORDER_WAKE":
            case "FOOTER_SAMPLE":
            case "ROUTE_VOICE":
            case "ROUTE_UNRESOLVED":
            case "VOICE_GEOMETRY":
            case "INPUT_GEOMETRY":
            case "VOICE_RESTORE_BEFORE":
            case "VOICE_RESTORE_AFTER":
            case "VOICE_BACKING_GATE":
            case "INPUT_BACKING_GATE":
            case "PANE_COMMIT_BEFORE":
            case "PANE_COMMIT_AFTER":
            case "VOICE_BG_CLEARED":
            case "VOICE_BG_RESTORED":
            case "INPUT_BG_CLEARED":
            case "INPUT_BG_RESTORED":
                return true;
            default:
                return false;
        }
    }
}
