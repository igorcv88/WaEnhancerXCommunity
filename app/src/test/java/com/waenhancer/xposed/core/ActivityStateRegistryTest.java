package com.waenhancer.xposed.core;

import android.app.Activity;
import java.lang.reflect.Field;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.waenhancer.xposed.core.WppCore.ActivityChangeState.ChangeType.*;

public class ActivityStateRegistryTest {
    private static final class Conversation extends Activity { }

    /** Only object identity/class are used; do not run Android's stub Activity constructor on the JVM. */
    private static Activity activity() throws Exception {
        // Resolve at runtime: AGP's Android boot classpath does not expose JDK internal types.
        Class<?> allocator = Class.forName("sun.misc.Unsafe");
        Field field = allocator.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Activity) allocator.getMethod("allocateInstance", Class.class)
                .invoke(field.get(null), Conversation.class);
    }

    @Test public void lateOldDestroyCannotReplaceNewResumedConversation() throws Exception {
        Activity old = activity(), current = activity();
        try {
            ActivityStateRegistry.updateState(old, RESUMED);
            ActivityStateRegistry.updateState(current, RESUMED);
            ActivityStateRegistry.markDestroyed(old);
            assertEquals(DESTROYED, ActivityStateRegistry.getState(old));
            assertSame(current, ActivityStateRegistry.getActivityBySimpleName("Conversation"));
            assertEquals(RESUMED, ActivityStateRegistry.getStateBySimpleName("Conversation"));
            ActivityStateRegistry.remove(old);
            assertNull(ActivityStateRegistry.getState(old));
            assertSame(current, ActivityStateRegistry.getActivityBySimpleName("Conversation"));
            assertEquals(RESUMED, ActivityStateRegistry.getStateBySimpleName("Conversation"));
        } finally {
            ActivityStateRegistry.remove(old); ActivityStateRegistry.remove(current);
        }
    }
    @Test public void destroyingCurrentInstanceRemovesOnlyItsOwnMapping() throws Exception {
        Activity current = activity();
        try {
            ActivityStateRegistry.updateState(current, STARTED);
            ActivityStateRegistry.markDestroyed(current);
            ActivityStateRegistry.remove(current);
            assertNull(ActivityStateRegistry.getActivityBySimpleName("Conversation"));
            assertNull(ActivityStateRegistry.getState(current));
        } finally { ActivityStateRegistry.remove(current); }
    }
    @Test public void genericDestroyedUpdateAlsoCannotClobberNewInstance() throws Exception {
        Activity old = activity(), current = activity();
        try {
            ActivityStateRegistry.updateState(old, RESUMED);
            ActivityStateRegistry.updateState(current, RESUMED);
            ActivityStateRegistry.updateState(old, DESTROYED);
            ActivityStateRegistry.remove(old);
            assertSame(current, ActivityStateRegistry.getActivityBySimpleName("Conversation"));
        } finally {
            ActivityStateRegistry.remove(old); ActivityStateRegistry.remove(current);
        }
    }
    @Test public void destructionCallbackDispatchesOldIdentityAndPreservesCurrentActivity() throws Exception {
        Activity old = activity(), current = activity();
        Activity previous = WppCore.mCurrentActivity;
        AtomicInteger callbacks = new AtomicInteger();
        AtomicBoolean correctDuringDispatch = new AtomicBoolean();
        WeakReference<Activity> oldIdentity = new WeakReference<>(old), newIdentity = new WeakReference<>(current);
        WppCore.addListenerActivity((activity, type) -> {
            if (activity == oldIdentity.get() && type == DESTROYED) {
                callbacks.incrementAndGet();
                correctDuringDispatch.set(ActivityStateRegistry.getState(activity) == DESTROYED
                        && ActivityStateRegistry.getActivityBySimpleName("Conversation") == newIdentity.get());
            }
        });
        try {
            ActivityStateRegistry.updateState(old, RESUMED);
            ActivityStateRegistry.updateState(current, RESUMED);
            WppCore.mCurrentActivity = current;
            new WaCallback().onActivityDestroyed(old);
            assertEquals(1, callbacks.get());
            assertTrue(correctDuringDispatch.get());
            assertSame(current, WppCore.mCurrentActivity);
            assertSame(current, ActivityStateRegistry.getActivityBySimpleName("Conversation"));
            assertNull(ActivityStateRegistry.getState(old));
        } finally {
            ActivityStateRegistry.remove(old); ActivityStateRegistry.remove(current);
            WppCore.mCurrentActivity = previous;
            oldIdentity.clear(); newIdentity.clear();
        }
    }
}
