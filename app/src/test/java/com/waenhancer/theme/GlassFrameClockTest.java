package com.waenhancer.theme;

import org.junit.Test;
import java.util.ArrayList;
import java.util.function.LongConsumer;
import static org.junit.Assert.*;

public class GlassFrameClockTest {
    @Test public void windowsShareOneCallbackAndOneTokenPerFrame() {
        var clock = new GlassFrameClock();
        var callbacks = new ArrayList<LongConsumer>();
        assertEquals(Long.MIN_VALUE, clock.currentFrame(callbacks::add));
        assertEquals(Long.MIN_VALUE, clock.currentFrame(callbacks::add));
        assertEquals(1, callbacks.size());
        callbacks.remove(0).accept(123456789L);
        assertEquals(123456789L, clock.currentFrame(callbacks::add));
        assertEquals(123456789L, clock.currentFrame(callbacks::add));
        assertEquals(1, callbacks.size());
        callbacks.remove(0).accept(123456999L);
        assertEquals(123456999L, clock.currentFrame(callbacks::add));
    }
    @Test public void callbackDoesNotScheduleAnIdleLoop() {
        var clock = new GlassFrameClock();
        var callbacks = new ArrayList<LongConsumer>();
        clock.currentFrame(callbacks::add);
        callbacks.remove(0).accept(100L);
        assertTrue(callbacks.isEmpty());
    }
    @Test public void schedulingFailureCanRetry() {
        var clock = new GlassFrameClock();
        try { clock.currentFrame(callback -> { throw new IllegalStateException(); }); fail(); }
        catch (IllegalStateException expected) { }
        var callbacks = new ArrayList<LongConsumer>();
        clock.currentFrame(callbacks::add);
        assertEquals(1, callbacks.size());
    }
}
