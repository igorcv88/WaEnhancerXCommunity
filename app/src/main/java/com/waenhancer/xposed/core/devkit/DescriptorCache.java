package com.waenhancer.xposed.core.devkit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Pure-Java core of {@link UnobfuscatorCache}: persisted reflection descriptors with
 * stale-entry recovery, single-flight resolution under one shared monitor and a bounded
 * per-process negative cache.
 *
 * <p>Adapted from the resolution contract of WaEnhancerX {@code DexCacheManager}
 * (commit 76d85e1, GPL-3.0) and its negative lookup cache (commit 45bb199), without
 * importing its storage format or its 60-second automatic retry. Community DexKit results
 * are deterministic for one installed WhatsApp APK and loaders can be reached from hook
 * callbacks, so a failure stays negative for the process lifetime unless the cache is
 * explicitly cleared. A persisted descriptor that no longer decodes is removed and resolved
 * once again instead of failing the feature until the next WhatsApp update.</p>
 *
 * <p>No Android types are used so the contract can be unit tested on the JVM.</p>
 */
public final class DescriptorCache {

    /** Persistent string storage (SharedPreferences in production). */
    public interface Store {
        String get(String key);

        void put(String key, String value);

        void remove(String key);
    }

    public interface Resolver<T> {
        T resolve() throws Exception;
    }

    public interface Codec<T> {
        String encode(T value) throws Exception;

        T decode(String raw) throws Exception;
    }

    /** Receives non-fatal cache events; never message content, only keys and exception types. */
    public interface Listener {
        void onStaleDescriptor(String key, Throwable cause);
    }

    public static final int DEFAULT_MAX_FAILURES = 1024;

    private final Store store;
    private final Listener listener;
    /**
     * The single monitor resolvers run under. Production passes {@code Unobfuscator.class}: its
     * {@code static synchronized} loaders already hold that monitor and nest into each other, so a
     * second (per-key) monitor could be acquired in the opposite order and deadlock. One reentrant
     * monitor cannot invert.
     */
    private final Object resolutionLock;
    private final Map<String, Boolean> failures;

    public DescriptorCache(Store store, Listener listener, Object resolutionLock) {
        this(store, listener, resolutionLock, DEFAULT_MAX_FAILURES);
    }

    public DescriptorCache(Store store, Listener listener, Object resolutionLock, int maxFailures) {
        if (maxFailures <= 0) throw new IllegalArgumentException("maxFailures");
        this.store = Objects.requireNonNull(store, "store");
        this.resolutionLock = Objects.requireNonNull(resolutionLock, "resolutionLock");
        this.listener = listener;
        final int cap = maxFailures;
        this.failures = Collections.synchronizedMap(new LinkedHashMap<String, Boolean>(64, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > cap;
            }
        });
    }

    /**
     * Returns the cached value for {@code key}, decoding a persisted descriptor or running
     * {@code resolver} under the shared resolution monitor, so concurrent callers share one result.
     *
     * @throws Exception when the resolver fails, returns {@code null}, or the key already
     *                   failed in this process. The message keeps the historical
     *                   "Error getting {kind} {key}: ..." shape used by error reports.
     */
    public <T> T get(String kind, String key, Codec<T> codec, Resolver<T> resolver) throws Exception {
        Objects.requireNonNull(key, "key");
        if (failures.containsKey(key)) {
            throw negativeHit(kind, key);
        }
        // Fast path without any lock: a valid persisted descriptor only needs decoding.
        T cached = decodePersisted(key, codec);
        if (cached != null) return cached;
        synchronized (resolutionLock) {
            // Another thread may have resolved or failed while we waited for the monitor.
            if (failures.containsKey(key)) {
                throw negativeHit(kind, key);
            }
            cached = decodePersisted(key, codec);
            if (cached != null) return cached;
            T value;
            try {
                value = resolver.resolve();
            } catch (Throwable error) {
                failures.put(key, Boolean.TRUE);
                throw new LookupFailedException("Error getting " + kind + " " + key + ": " + error.getMessage(), error);
            }
            if (value == null) {
                failures.put(key, Boolean.TRUE);
                throw new LookupFailedException("Error getting " + kind + " " + key + ": " + kind + " is null");
            }
            try {
                store.put(key, codec.encode(value));
            } catch (Throwable encodeFailure) {
                // The live value is valid; only persistence failed. Do not cache a partial string.
                store.remove(key);
            }
            return value;
        }
    }

    /**
     * Negative hits keep the exception type the historical per-getter checks used
     * (NoSuchMethodException, NoSuchFieldException, ClassNotFoundException).
     */
    static Exception negativeHit(String kind, String key) {
        String message = kind + " lookup failed previously in this session: " + key;
        switch (kind) {
            case "method":
            case "methods":
            case "constructor":
                return new NoSuchMethodException(message);
            case "field":
            case "fields":
                return new NoSuchFieldException(message);
            case "class":
            case "classes":
                return new ClassNotFoundException(message);
            default:
                return new LookupFailedException(message);
        }
    }

    private <T> T decodePersisted(String key, Codec<T> codec) {
        String persisted = store.get(key);
        if (persisted == null) return null;
        try {
            T decoded = codec.decode(persisted);
            if (decoded != null) return decoded;
            throw new IllegalStateException("decoded null");
        } catch (Throwable stale) {
            // Remove only the value we failed to decode; another thread may have replaced it.
            synchronized (resolutionLock) {
                if (persisted.equals(store.get(key))) {
                    store.remove(key);
                    if (listener != null) listener.onStaleDescriptor(key, stale);
                }
            }
            return null;
        }
    }

    public boolean hasFailed(String key) {
        return failures.containsKey(key);
    }

    /** Forgets negative entries, e.g. after the user explicitly clears the obfuscation cache. */
    public void clearFailures() {
        failures.clear();
    }

    int failureCountForTests() {
        return failures.size();
    }

    /** Thrown for resolver failures and negative-cache hits; keeps checked semantics. */
    public static final class LookupFailedException extends Exception {
        public LookupFailedException(String message) {
            super(message);
        }

        public LookupFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
