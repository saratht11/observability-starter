package com.example.observability;

import com.example.observability.dedup.DeduplicationCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DeduplicationCache}.
 */
class DeduplicationCacheTest {

    private DeduplicationCache cache;

    @BeforeEach
    void setUp() {
        cache = new DeduplicationCache();
    }

    @Test
    void newKey_isRegisteredSuccessfully() {
        assertThat(cache.tryRegister("metric:txn-001")).isTrue();
    }

    @Test
    void sameKey_withinTtl_isDuplicate() {
        cache.tryRegister("metric:txn-001");
        assertThat(cache.tryRegister("metric:txn-001")).isFalse();
    }

    @Test
    void differentKeys_areNotDuplicates() {
        assertThat(cache.tryRegister("metric:txn-001")).isTrue();
        assertThat(cache.tryRegister("metric:txn-002")).isTrue();
    }

    @Test
    void nullKey_isNeverDuplicate() {
        assertThat(cache.tryRegister(null)).isTrue();
        assertThat(cache.tryRegister(null)).isTrue();
    }

    @Test
    void blankKey_isNeverDuplicate() {
        assertThat(cache.tryRegister("")).isTrue();
        assertThat(cache.tryRegister("  ")).isTrue();
    }

    @Test
    void invalidate_allowsReregistration() {
        cache.tryRegister("metric:txn-001");
        cache.invalidate("metric:txn-001");
        assertThat(cache.tryRegister("metric:txn-001")).isTrue();
    }

    @Test
    void size_reflectsRegisteredEntries() {
        assertThat(cache.size()).isEqualTo(0);
        cache.tryRegister("metric:txn-001");
        cache.tryRegister("metric:txn-002");
        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    void invalidate_nullKey_doesNotThrow() {
        // Should not throw
        cache.invalidate(null);
    }
}
