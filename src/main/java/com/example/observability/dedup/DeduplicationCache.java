package com.example.observability.dedup;

import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, bounded, TTL-based deduplication cache used to prevent duplicate
 * metric counts on retries.
 *
 * <p>A unique ID (derived from SpEL expressions on {@code @Monitored}, {@code @MonitoredCounter},
 * or {@code @PaymentMonitored}) is registered on first encounter.  Subsequent calls
 * with the same key within the {@link #TTL_MS TTL window} are considered duplicates and
 * return {@code false} from {@link #tryRegister}.
 *
 * <p>The cache is bounded by {@link #MAX_ENTRIES} to avoid unbounded memory growth.
 * When the limit is reached, expired entries are evicted before accepting a new key.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // In an AOP aspect:
 * String key = metricName + ":" + resolvedUniqueId;
 * if (!deduplicationCache.tryRegister(key)) {
 *     return pjp.proceed(); // skip metric recording for this retry
 * }
 * }</pre>
 */
@Component
public class DeduplicationCache {

    /** Maximum number of entries held before forced eviction. */
    static final int MAX_ENTRIES = 10_000;

    /** Time-to-live for each entry in milliseconds (default: 60 s). */
    static final long TTL_MS = 60_000L;

    /** key → timestamp of first registration. */
    private final Map<String, Long> seen = new ConcurrentHashMap<>();

    /**
     * Attempts to register {@code key} as a new unique occurrence.
     *
     * @param key composite cache key (e.g. {@code "my.metric:txn-123"})
     * @return {@code true} if this is a <em>new</em> occurrence and counting should proceed;
     *         {@code false} if the key was already registered within the TTL (i.e. duplicate)
     */
    public boolean tryRegister(String key) {
        if (key == null || key.isBlank()) {
            return true; // no dedup configured — always count
        }

        long now = System.currentTimeMillis();
        Long firstSeen = seen.get(key);

        if (firstSeen != null && now - firstSeen < TTL_MS) {
            return false; // duplicate within TTL window
        }

        // New or expired — evict if needed, then register
        if (seen.size() >= MAX_ENTRIES) {
            evictExpired(now);
        }
        seen.put(key, now);
        return true;
    }

    /**
     * Explicitly removes a key from the cache (e.g. after a confirmed success
     * so that a future retry is allowed to count again).
     *
     * @param key the cache key to invalidate
     */
    public void invalidate(String key) {
        if (key != null) {
            seen.remove(key);
        }
    }

    /** Returns the current number of live entries (for testing / monitoring). */
    public int size() {
        return seen.size();
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void evictExpired(long now) {
        Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            if (now - entry.getValue() >= TTL_MS) {
                it.remove();
            }
        }
    }
}
