package com.waenhancer.xposed.features.general;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** A marker needs a durable event; preserved media alone is never evidence of revocation. */
public final class RevocationMarkerLookup {
    public record Identity(String scope, long row, String key) {}
    public record Match(String key, long timestamp) {
        public static final Match NONE = new Match(null, 0);
        public boolean known() { return key != null && !key.isEmpty(); }
    }
    private record Cached(Match match, long at) {}
    private final Map<Identity, Cached> cache = new LinkedHashMap<>(64, .75f, true);
    private final Set<Identity> pending = new HashSet<>();
    private static final int MAX_CACHE = 256, MAX_PENDING = 64;
    private static final long MISS_TTL_MS = 5000;

    public synchronized Match cached(Identity identity, long now) {
        Cached entry = cache.get(identity);
        if (entry == null) return null;
        if (!entry.match.known() && now - entry.at >= MISS_TTL_MS) {
            cache.remove(identity);
            return null;
        }
        return entry.match;
    }
    public synchronized boolean begin(Identity identity) {
        return pending.size() < MAX_PENDING && pending.add(identity);
    }
    public synchronized void complete(Identity identity, Match match, long now) {
        pending.remove(identity);
        cache.put(identity, new Cached(match, now));
        while (cache.size() > MAX_CACHE) cache.remove(cache.keySet().iterator().next());
    }
    public synchronized void cancel(Identity identity) { pending.remove(identity); }

    public static Match resolve(Match current, List<String> relatedKeys,
            Function<String, Match> knownMarker) {
        if (current.known()) return current;
        Match found = Match.NONE;
        for (String candidate : relatedKeys) {
            if (candidate == null || candidate.isEmpty()) continue;
            Match marker = knownMarker.apply(candidate);
            if (!marker.known() || !candidate.equals(marker.key())) continue;
            if (found.known() && !candidate.equals(found.key())) return Match.NONE;
            found = marker;
        }
        return found;
    }
}
