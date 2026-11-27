package com.waenhancer.xposed.core.devkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class DescriptorCacheFailureListenerTest {
    @After
    public void clear() {
        DescriptorCache.setFailureListener(null);
    }

    @Test
    public void freshFailureAndNegativeHitAreBothReported() {
        List<String> failures = new ArrayList<>();
        DescriptorCache.setFailureListener(failures::add);
        DescriptorCache cache = new DescriptorCache(new DescriptorCacheTest.MapStore(), null, DescriptorCacheTest.LOCK);
        DescriptorCache.Resolver<java.lang.reflect.Method> missing = () -> { throw new NoSuchMethodException("gone"); };
        assertThrows(Exception.class, () -> cache.get("method", "loadVideoTranscoderStartMethod", DescriptorCacheTest.METHOD, missing));
        // A second feature asking for the same descriptor in this process hits the negative cache.
        assertThrows(NoSuchMethodException.class, () -> cache.get("method", "loadVideoTranscoderStartMethod", DescriptorCacheTest.METHOD, missing));
        assertEquals(List.of("method:loadVideoTranscoderStartMethod", "method:loadVideoTranscoderStartMethod"), failures);
    }

    @Test
    public void nullResultIsAFailure() {
        List<String> failures = new ArrayList<>();
        DescriptorCache.setFailureListener(failures::add);
        DescriptorCache cache = new DescriptorCache(new DescriptorCacheTest.MapStore(), null, DescriptorCacheTest.LOCK);
        assertThrows(Exception.class, () -> cache.get("method", "loadNull", DescriptorCacheTest.METHOD, () -> null));
        assertEquals(List.of("method:loadNull"), failures);
    }

    @Test
    public void throwingListenerDoesNotChangeTheOutcome() {
        DescriptorCache.setFailureListener(key -> { throw new IllegalStateException("listener"); });
        DescriptorCache cache = new DescriptorCache(new DescriptorCacheTest.MapStore(), null, DescriptorCacheTest.LOCK);
        assertThrows(DescriptorCache.LookupFailedException.class, () ->
                cache.get("method", "loadX", DescriptorCacheTest.METHOD, () -> { throw new NoSuchMethodException(); }));
    }
}
