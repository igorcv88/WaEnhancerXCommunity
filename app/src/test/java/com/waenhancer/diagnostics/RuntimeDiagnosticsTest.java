package com.waenhancer.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class RuntimeDiagnosticsTest {
    @Before
    public void fresh() {
        RuntimeDiagnostics.resetForTest();
    }

    @After
    public void cleanup() {
        RuntimeDiagnostics.leave();
        RuntimeDiagnostics.resetForTest();
    }

    private static JSONObject root() throws Exception {
        return new JSONObject().put("features", new JSONObject());
    }

    @Test
    public void eventsBeforeTheSessionBindsAreReplayedNotDropped() throws Exception {
        // The race: a worker reports an install failure while the session is still binding.
        RuntimeDiagnostics.feature("MediaPreview", "loaded");
        RuntimeDiagnostics.feature("MediaPreview", "error");
        JSONObject root = root();
        RuntimeDiagnostics.drainPending(root);
        JSONObject feature = root.getJSONObject("features").getJSONObject("MediaPreview");
        assertTrue(feature.has("loaded"));
        assertTrue(feature.has("error"));
    }

    @Test
    public void concurrentEarlyEventsAreAllKept() throws Exception {
        int threads = 4;
        int perThread = 50;
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            String name = "Feature" + t;
            Thread worker = new Thread(() -> {
                try {
                    go.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
                for (int i = 0; i < perThread; i++) RuntimeDiagnostics.acted(name, "path-" + (i % 10));
            });
            workers.add(worker);
            worker.start();
        }
        go.countDown();
        for (Thread worker : workers) worker.join(5000);
        JSONObject root = root();
        RuntimeDiagnostics.drainPending(root);
        for (int t = 0; t < threads; t++) {
            assertEquals(10, root.getJSONObject("features").getJSONObject("Feature" + t)
                    .getJSONObject("acted").length());
        }
        assertFalse(root.has("droppedEvents"));
    }

    @Test
    public void bufferIsBoundedAndCountsDrops() throws Exception {
        for (int i = 0; i < RuntimeDiagnostics.MAX_PENDING + 7; i++) RuntimeDiagnostics.feature("A", "reached");
        JSONObject root = root();
        RuntimeDiagnostics.drainPending(root);
        assertEquals(7, root.getInt("droppedEvents"));
    }

    @Test
    public void noSessionSwitchesRecordingOff() throws Exception {
        RuntimeDiagnostics.switchOffForTest();
        RuntimeDiagnostics.feature("A", "error");
        JSONObject root = root();
        RuntimeDiagnostics.drainPending(root);
        assertEquals(0, root.getJSONObject("features").length());
    }

    @Test
    public void firstOccurrenceIsKeptButReachIsLatest() throws Exception {
        JSONObject root = root();
        RuntimeDiagnostics.apply(root, new RuntimeDiagnostics.Event("A", "installed", null, 100));
        RuntimeDiagnostics.apply(root, new RuntimeDiagnostics.Event("A", "installed", null, 200));
        RuntimeDiagnostics.apply(root, new RuntimeDiagnostics.Event("A", "reached", null, 100));
        // No throttle: a callback 1 ms later still moves the reach time past an armed exercise.
        RuntimeDiagnostics.apply(root, new RuntimeDiagnostics.Event("A", "reached", null, 101));
        JSONObject f = root.getJSONObject("features").getJSONObject("A");
        assertEquals(100, f.getLong("installed"));
        assertEquals(101, f.getLong("reached"));
    }

    @Test
    public void actedIsRecordedPerPath() throws Exception {
        JSONObject root = root();
        RuntimeDiagnostics.apply(root, new RuntimeDiagnostics.Event("HideSeen", "acted", "group-read-suppressed", 10));
        RuntimeDiagnostics.apply(root, new RuntimeDiagnostics.Event("HideSeen", "acted", "direct-read-suppressed", 20));
        JSONObject acted = root.getJSONObject("features").getJSONObject("HideSeen").getJSONObject("acted");
        assertEquals(10, acted.getLong("group-read-suppressed"));
        assertEquals(20, acted.getLong("direct-read-suppressed"));
    }

    @Test
    public void invalidPathsAndNamesAreIgnored() throws Exception {
        RuntimeDiagnostics.acted("A", "Has Spaces");
        RuntimeDiagnostics.acted("A", null);
        RuntimeDiagnostics.feature("bad name", "error");
        RuntimeDiagnostics.feature("A", "unknownEvent");
        JSONObject root = root();
        RuntimeDiagnostics.drainPending(root);
        assertEquals(0, root.getJSONObject("features").length());
    }

    @Test
    public void resolverFailuresAreAttributedToTheLoadingFeatureOnly() throws Exception {
        RuntimeDiagnostics.resolverFailed("method:outside-any-feature");
        RuntimeDiagnostics.enter("MediaQuality");
        RuntimeDiagnostics.resolverFailed("method:loadVideoTranscoderStartMethod");
        RuntimeDiagnostics.leave();
        RuntimeDiagnostics.resolverFailed("method:after-leave");
        JSONObject root = root();
        RuntimeDiagnostics.drainPending(root);
        JSONObject features = root.getJSONObject("features");
        assertEquals(1, features.length());
        JSONObject f = features.getJSONObject("MediaQuality");
        assertEquals("method:loadVideoTranscoderStartMethod", f.getJSONArray("resolverKeys").getString(0));
        assertTrue(f.has("resolverFailed"));
        assertNull(features.optJSONObject("outside"));
    }
}
