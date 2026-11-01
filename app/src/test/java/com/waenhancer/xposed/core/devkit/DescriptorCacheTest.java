package com.waenhancer.xposed.core.devkit;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public class DescriptorCacheTest {

    static final class MapStore implements DescriptorCache.Store {
        final Map<String, String> values = new HashMap<>();
        int puts;

        @Override public synchronized String get(String key) { return values.get(key); }
        @Override public synchronized void put(String key, String value) { puts++; values.put(key, value); }
        @Override public synchronized void remove(String key) { values.remove(key); }
    }

    static final class Host {
        int count;
        String label;
        int limit() { return 1; }
        void act(String a, int b) {}
    }

    static final ReflectionDescriptors.ClassFinder FINDER = name -> {
        switch (name) {
            case "int": return int.class;
            case "void": return void.class;
            default: return Class.forName(name);
        }
    };

    static final DescriptorCache.Codec<Method> METHOD = new DescriptorCache.Codec<Method>() {
        @Override public String encode(Method value) { return ReflectionDescriptors.encodeMethod(value); }
        @Override public Method decode(String raw) throws Exception { return ReflectionDescriptors.decodeMethod(FINDER, raw); }
    };

    @Test public void resolvesOnceThenDecodesPersistedDescriptor() throws Exception {
        MapStore store = new MapStore();
        DescriptorCache cache = new DescriptorCache(store, null);
        AtomicInteger scans = new AtomicInteger();
        Method expected = Host.class.getDeclaredMethod("limit");
        DescriptorCache.Resolver<Method> resolver = () -> { scans.incrementAndGet(); return expected; };
        assertEquals(expected, cache.get("method", "loadLimit", METHOD, resolver));
        // Fresh process: new cache instance over the same store must not scan again.
        DescriptorCache restarted = new DescriptorCache(store, null);
        assertEquals(expected, restarted.get("method", "loadLimit", METHOD, resolver));
        assertEquals(1, scans.get());
    }

    @Test public void staleDescriptorIsEvictedAndResolvedAgain() throws Exception {
        MapStore store = new MapStore();
        store.values.put("loadLimit", "com.example.Gone:a");
        List<String> stale = new ArrayList<>();
        DescriptorCache cache = new DescriptorCache(store, (key, cause) -> stale.add(key));
        Method expected = Host.class.getDeclaredMethod("limit");
        assertEquals(expected, cache.get("method", "loadLimit", METHOD, () -> expected));
        assertEquals(List.of("loadLimit"), stale);
        assertEquals(ReflectionDescriptors.encodeMethod(expected), store.values.get("loadLimit"));
    }

    @Test public void changedReturnTypeIsTreatedAsStale() throws Exception {
        Method limit = Host.class.getDeclaredMethod("limit");
        String descriptor = ReflectionDescriptors.encodeMethod(limit).replace("|int", "|boolean");
        try {
            ReflectionDescriptors.decodeMethod(FINDER, descriptor);
            fail("return type change accepted");
        } catch (NoSuchMethodException expected) {
            // expected
        }
    }

    @Test public void legacyDescriptorWithoutTypeStillDecodes() throws Exception {
        Method act = Host.class.getDeclaredMethod("act", String.class, int.class);
        String legacy = Host.class.getName() + ":act:java.lang.String,int";
        assertEquals(act, ReflectionDescriptors.decodeMethod(FINDER, legacy));
        Field count = Host.class.getDeclaredField("count");
        assertEquals(count, ReflectionDescriptors.decodeField(FINDER, Host.class.getName() + ":count"));
    }

    @Test public void changedFieldTypeIsRejected() throws Exception {
        String descriptor = Host.class.getName() + ":count|java.lang.String";
        try {
            ReflectionDescriptors.decodeField(FINDER, descriptor);
            fail("field type change accepted");
        } catch (NoSuchFieldException expected) {
            // expected
        }
    }

    @Test public void failureIsNegativeCachedWithHistoricalExceptionType() throws Exception {
        MapStore store = new MapStore();
        DescriptorCache cache = new DescriptorCache(store, null);
        AtomicInteger scans = new AtomicInteger();
        DescriptorCache.Resolver<Method> failing = () -> { scans.incrementAndGet(); throw new NoSuchMethodException("absent"); };
        try { cache.get("method", "loadAbsent", METHOD, failing); fail(); } catch (DescriptorCache.LookupFailedException e) {
            assertTrue(e.getMessage().startsWith("Error getting method loadAbsent"));
        }
        try { cache.get("method", "loadAbsent", METHOD, failing); fail(); } catch (NoSuchMethodException e) {
            assertTrue(e.getMessage().contains("previously"));
        }
        assertEquals(1, scans.get());
        cache.clearFailures();
        try { cache.get("method", "loadAbsent", METHOD, failing); fail(); } catch (DescriptorCache.LookupFailedException ignored) {}
        assertEquals(2, scans.get());
        assertFalse(store.values.containsKey("loadAbsent"));
    }

    @Test public void nullResultIsAFailure() throws Exception {
        DescriptorCache cache = new DescriptorCache(new MapStore(), null);
        try { cache.get("class", "loadX", null, () -> null); fail(); } catch (DescriptorCache.LookupFailedException e) {
            assertTrue(e.getMessage().endsWith("class is null"));
        }
        assertTrue(cache.hasFailed("loadX"));
    }

    @Test public void negativeCacheIsBounded() throws Exception {
        DescriptorCache cache = new DescriptorCache(new MapStore(), null, 3);
        for (int i = 0; i < 10; i++) {
            try { cache.get("class", "k" + i, null, () -> null); } catch (Exception ignored) {}
        }
        assertEquals(3, cache.failureCountForTests());
        assertFalse(cache.hasFailed("k0"));
        assertTrue(cache.hasFailed("k9"));
    }

    @Test public void concurrentCallersShareOneResolution() throws Exception {
        MapStore store = new MapStore();
        DescriptorCache cache = new DescriptorCache(store, null);
        AtomicInteger scans = new AtomicInteger();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Method expected = Host.class.getDeclaredMethod("limit");
        DescriptorCache.Resolver<Method> slow = () -> {
            scans.incrementAndGet();
            inside.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return expected;
        };
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Method>> results = new ArrayList<>();
            results.add(pool.submit(() -> cache.get("method", "loadLimit", METHOD, slow)));
            assertTrue(inside.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 3; i++) results.add(pool.submit(() -> cache.get("method", "loadLimit", METHOD, slow)));
            Thread.sleep(50);
            release.countDown();
            for (Future<Method> f : results) assertEquals(expected, f.get(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, scans.get());
        assertEquals(1, store.puts);
    }

    @Test public void emptyArraysRoundTrip() throws Exception {
        assertEquals(0, ReflectionDescriptors.decodeMethods(FINDER, ReflectionDescriptors.encodeMethods(new Method[0])).length);
        assertEquals(0, ReflectionDescriptors.decodeClasses(FINDER, ReflectionDescriptors.encodeClasses(new Class<?>[0])).length);
        assertEquals(0, ReflectionDescriptors.decodeFields(FINDER, ReflectionDescriptors.encodeFields(new Field[0])).length);
        // The pre-fix encoding ("") is malformed and must be re-resolved, not crash forever.
        try { ReflectionDescriptors.decodeMethods(FINDER, ""); fail(); } catch (IllegalArgumentException expected) {}
    }

    @Test public void arraysAndFieldMapRoundTrip() throws Exception {
        Method[] methods = { Host.class.getDeclaredMethod("limit"), Host.class.getDeclaredMethod("act", String.class, int.class) };
        assertArrayEquals(methods, ReflectionDescriptors.decodeMethods(FINDER, ReflectionDescriptors.encodeMethods(methods)));
        HashMap<String, Field> map = new HashMap<>();
        map.put("count", Host.class.getDeclaredField("count"));
        map.put("label", Host.class.getDeclaredField("label"));
        assertEquals(map, ReflectionDescriptors.decodeFieldMap(FINDER, ReflectionDescriptors.encodeFieldMap(map)));
    }

    @Test public void fieldMapWithOneStaleEntryIsRejectedWhole() throws Exception {
        String raw = "{\"count\":\"" + Host.class.getName() + ":count\",\"gone\":\"" + Host.class.getName() + ":missing\"}";
        try { ReflectionDescriptors.decodeFieldMap(FINDER, raw); fail("partial map accepted"); } catch (NoSuchFieldException expected) {}
    }

    @Test public void numbersRoundTrip() {
        assertEquals(42, ReflectionDescriptors.decodeNumber(ReflectionDescriptors.encodeNumber(42)));
        assertEquals(7L, ReflectionDescriptors.decodeNumber(ReflectionDescriptors.encodeNumber(7L)));
        assertEquals(5, ReflectionDescriptors.decodeNumber("5"));
    }
}
