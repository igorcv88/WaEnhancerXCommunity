package com.waenhancer.library.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/**
 * Row ↔ message association and lookup caching for inline presentation, independent of Android
 * so recycling, delayed callbacks and invalidation can be tested deterministically.
 *
 * <p>Rules:</p>
 * <ol>
 *   <li>Every bind of a row, including a bind to a message without media, issues a new token
 *   ({@link #bind}). Work started for an older token is discarded when it completes
 *   ({@link #isCurrent}), so a late callback can never decorate a row that now shows another
 *   message.</li>
 *   <li>Every cache key contains the WhatsApp package and the active account. A lookup answer is
 *   valid only for the library state it was read under ({@link Source#stateToken()}: the
 *   library generation plus the module settings that affect presentation). Any deletion,
 *   restoration, repair, account switch or settings change therefore misses the cache.</li>
 *   <li>Thumbnails are keyed by account, item and content hash, so a repaired item with new
 *   bytes never shows the old picture, and another account never sees a cached picture.</li>
 * </ol>
 */
public final class InlineBinder<R> {
    public static final long NEGATIVE_TTL_MS = 60_000L;
    private static final int MAX_ENTRIES = 512;

    /** Identity of the media message bound to a row; scoped to package and account. */
    public static final class Ref {
        public final String packageName;
        public final String ownJid;
        public final String chatJid;
        public final String chatAltJid;
        public final String messageKey;
        public final boolean fromMe;

        public Ref(String packageName, String ownJid, String chatJid, String chatAltJid, String messageKey, boolean fromMe) {
            this.packageName = packageName;
            this.ownJid = ownJid;
            this.chatJid = chatJid;
            this.chatAltJid = chatAltJid;
            this.messageKey = messageKey;
            this.fromMe = fromMe;
        }

        public String scope() {
            return packageName + '\n' + ownJid;
        }

        public String cacheKey() {
            return scope() + '\n' + chatJid + '\n' + messageKey + '\n' + fromMe;
        }
    }

    /** The library's answer for one message: a kept, available item or none. */
    public static final class Answer {
        public final String uuid;
        public final String sha256;

        public Answer(String uuid, String sha256) {
            this.uuid = uuid;
            this.sha256 = sha256;
        }

        public boolean present() {
            return uuid != null && !uuid.isEmpty();
        }
    }

    /** Backed by the provider in WhatsApp; by fakes in tests. */
    public interface Source {
        /** Library generation + presentation settings, as seen now; {@code null} when unreachable. */
        String stateToken();

        /** Looks up one message; {@code null} on failure (not cached). */
        Answer lookup(Ref ref);
    }

    /** One bind's pending work. */
    public static final class Task<R> {
        public final R row;
        public final long token;
        public final Ref ref;

        Task(R row, long token, Ref ref) {
            this.row = row;
            this.token = token;
            this.ref = ref;
        }
    }

    private static final class Cached {
        final Answer answer;
        final String state;
        final long at;

        Cached(Answer answer, String state, long at) {
            this.answer = answer;
            this.state = state;
            this.at = at;
        }
    }

    private final WeakHashMap<R, Long> tokens = new WeakHashMap<>();
    private final Map<String, Cached> cache = new LinkedHashMap<String, Cached>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > MAX_ENTRIES;
        }
    };
    private long nextToken;
    private String activeScope;

    /**
     * Records a (re)bind of {@code row}. Call for every bind, whatever the message;
     * {@code ref} is {@code null} for a message without eligible media.
     */
    public synchronized Task<R> bind(R row, Ref ref) {
        long token = ++nextToken;
        tokens.put(row, token);
        if (ref != null && !Objects.equals(activeScope, ref.scope())) {
            // Account (or WhatsApp flavour) changed: nothing cached for another scope may be used.
            cache.clear();
            activeScope = ref.scope();
        }
        return ref == null ? null : new Task<>(row, token, ref);
    }

    public synchronized boolean isCurrent(Task<R> task) {
        if (task == null) return false;
        Long current = tokens.get(task.row);
        return current != null && current == task.token;
    }

    /** Settings or account changed outside a bind: forget every token and answer. */
    public synchronized void invalidateAll() {
        tokens.clear();
        cache.clear();
        activeScope = null;
    }

    /**
     * Resolves a task in the background. Returns {@code null} when the task is stale, the
     * library is unreachable, or no available copy exists.
     */
    public Answer resolve(Task<R> task, Source source, long now) {
        if (!isCurrent(task)) return null;
        String state = source.stateToken();
        if (state == null) return null;
        String key = task.ref.cacheKey();
        synchronized (this) {
            Cached entry = cache.get(key);
            if (entry != null && entry.state.equals(state)
                    && (entry.answer.present() || now - entry.at < NEGATIVE_TTL_MS)) {
                return entry.answer.present() ? entry.answer : null;
            }
        }
        if (!isCurrent(task)) return null;
        Answer answer = source.lookup(task.ref);
        if (answer == null) return null;
        synchronized (this) {
            cache.put(key, new Cached(answer, state, now));
        }
        return answer.present() ? answer : null;
    }

    /** Drops a cached answer (e.g. right after a keep from the same process). */
    public synchronized void forget(Ref ref) {
        cache.remove(ref.cacheKey());
    }

    /** Thumbnail cache key: scope + item + content hash. */
    public static String thumbnailKey(Ref ref, Answer answer) {
        return ref.scope() + '\n' + answer.uuid + '\n' + answer.sha256;
    }

    synchronized int cachedEntries() {
        return cache.size();
    }
}
