package com.waenhancer.xposed.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public class FeatureCallbacksTest {
    @Test public void installingDuringDispatchUsesNextSnapshot() {
        FeatureCallbacks<Integer> callbacks = new FeatureCallbacks<>();
        callbacks.add(1);
        List<Integer> delivered = new ArrayList<>();
        callbacks.dispatch(value -> { delivered.add(value); callbacks.add(2); }, error -> fail());
        assertEquals(List.of(1), delivered);
        delivered.clear();
        callbacks.dispatch(delivered::add, error -> fail());
        assertEquals(List.of(1, 2), delivered);
    }
    @Test public void failureDoesNotSkipOtherFeatures() {
        FeatureCallbacks<Integer> callbacks = new FeatureCallbacks<>();
        callbacks.add(1); callbacks.add(2);
        List<Integer> delivered = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        callbacks.dispatch(value -> {
            if (value == 1) throw new IllegalStateException("failed adapter");
            delivered.add(value);
        }, failures::add);
        assertEquals(List.of(2), delivered);
        assertEquals(1, failures.size());
    }
    @Test public void concurrentLazyRegistrationDoesNotBreakDispatch() throws Exception {
        FeatureCallbacks<Integer> callbacks = new FeatureCallbacks<>();
        callbacks.add(1);
        CountDownLatch installing = new CountDownLatch(1), installed = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                if (!installing.await(2, TimeUnit.SECONDS)) return;
                callbacks.add(2);
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            finally { installed.countDown(); }
        });
        worker.start();
        List<Integer> delivered = new ArrayList<>();
        callbacks.dispatch(value -> {
            delivered.add(value); installing.countDown();
            try { assertTrue(installed.await(2, TimeUnit.SECONDS)); }
            catch (InterruptedException error) { throw new AssertionError(error); }
        }, error -> { throw new AssertionError(error); });
        worker.join(2000);
        assertFalse(worker.isAlive());
        assertEquals(List.of(1), delivered);
        callbacks.dispatch(delivered::add, error -> fail());
        assertEquals(List.of(1, 1, 2), delivered);
    }
}
