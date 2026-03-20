package com.example.observability;

import com.example.observability.annotation.MonitoredCounter;
import com.example.observability.aspect.MonitoredCounterAspect;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link MonitoredCounterAspect}.
 */
class MonitoredCounterAspectTest {

    private MeterRegistry registry;
    private SampleCounterBean proxy;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        MonitoredCounterAspect aspect = new MonitoredCounterAspect(registry);

        AspectJProxyFactory factory = new AspectJProxyFactory(new SampleCounterBean());
        factory.addAspect(aspect);
        proxy = factory.getProxy();
    }

    @Test
    void singleInvocation_incrementsCounterByOne() {
        proxy.cacheMiss("user-1");
        assertThat(registry.find("cache.access.total").counter()).isNotNull();
        assertThat(registry.find("cache.access.total").counter().count()).isEqualTo(1.0);
    }

    @Test
    void multipleInvocations_accumulateCount() {
        proxy.cacheMiss("u1");
        proxy.cacheMiss("u2");
        proxy.cacheMiss("u3");
        assertThat(registry.find("cache.access.total").counter().count()).isEqualTo(3.0);
    }

    @Test
    void componentTag_isPresentOnCounter() {
        proxy.cacheMiss("u1");
        assertThat(registry.find("cache.access.total")
                .tag("component", "user-cache")
                .counter()).isNotNull();
    }

    @Test
    void staticTag_isPresentOnCounter() {
        proxy.cacheMiss("u1");
        assertThat(registry.find("cache.access.total")
                .tag("outcome", "miss")
                .counter()).isNotNull();
    }

    @Test
    void cacheHit_hasHitOutcomeTag() {
        proxy.cacheHit("u1");
        assertThat(registry.find("cache.access.total")
                .tag("outcome", "hit")
                .counter()).isNotNull();
    }

    @Test
    void hitAndMiss_areTrackedSeparately() {
        proxy.cacheHit("u1");
        proxy.cacheHit("u2");
        proxy.cacheMiss("u3");

        assertThat(registry.find("cache.access.total").tag("outcome", "hit").counter().count())
                .isEqualTo(2.0);
        assertThat(registry.find("cache.access.total").tag("outcome", "miss").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void fallbackMetricName_usesClassAndMethodName() {
        proxy.noExplicitMetric();
        // Falls back to "samplecounterbean.noexplicitmetric"
        assertThat(registry.find("samplecounterbean.noexplicitmetric").counter()).isNotNull();
    }

    @Test
    void exception_stillIncrementsCounter() {
        assertThatThrownBy(() -> proxy.failingMethod())
                .isInstanceOf(RuntimeException.class);
        // @After advice fires even when an exception is thrown
        assertThat(registry.find("always.counted.total").counter().count()).isEqualTo(1.0);
    }

    // ── Sample bean ──────────────────────────────────────────────────────────

    static class SampleCounterBean {

        @MonitoredCounter(
                metric = "cache.access.total",
                component = "user-cache",
                tags = {"outcome=miss"}
        )
        public void cacheMiss(String userId) {
            // simulated DB fallback
        }

        @MonitoredCounter(
                metric = "cache.access.total",
                component = "user-cache",
                tags = {"outcome=hit"}
        )
        public void cacheHit(String userId) {
            // simulated cache read
        }

        @MonitoredCounter(component = "test")
        public void noExplicitMetric() {
            // metric name falls back to class.method
        }

        @MonitoredCounter(
                metric = "always.counted.total",
                component = "test"
        )
        public void failingMethod() {
            throw new RuntimeException("intentional failure");
        }
    }
}
