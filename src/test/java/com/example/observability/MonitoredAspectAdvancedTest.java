package com.example.observability;

import com.example.observability.annotation.Monitored;
import com.example.observability.aspect.MonitoredAspect;
import com.example.observability.baggage.BaggageReader;
import com.example.observability.dedup.DeduplicationCache;
import com.example.observability.exception.MonitoredTimeoutException;
import com.example.observability.metrics.LatencyRecorder;
import com.example.observability.metrics.MonitoredTagResolver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MonitoredAspect} covering deduplication (uniqueId) and timeout features.
 */
class MonitoredAspectAdvancedTest {

    private MeterRegistry registry;
    private DeduplicationCache deduplicationCache;
    private SampleBean proxy;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        deduplicationCache = new DeduplicationCache();

        BaggageReader baggageReader = mock(BaggageReader.class);
        when(baggageReader.read(anyString())).thenReturn(null);
        when(baggageReader.readAll()).thenReturn(Map.of());

        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        when(tracer.nextSpan()).thenReturn(span);
        when(span.name(anyString())).thenReturn(span);
        when(span.start()).thenReturn(span);
        when(span.tag(anyString(), anyString())).thenReturn(span);
        when(span.error(org.mockito.ArgumentMatchers.any())).thenReturn(span);

        MonitoredTagResolver tagResolver = new MonitoredTagResolver();
        LatencyRecorder recorder = new LatencyRecorder(registry);
        MonitoredAspect aspect = new MonitoredAspect(tagResolver, recorder, baggageReader, tracer, deduplicationCache);

        AspectJProxyFactory factory = new AspectJProxyFactory(new SampleBean());
        factory.addAspect(aspect);
        proxy = factory.getProxy();
    }

    // ── Deduplication tests ───────────────────────────────────────────────────

    @Test
    void uniqueId_firstInvocation_countsNormally() {
        proxy.dedupWork("txn-001");
        assertThat(registry.find("dedup.work.latency").timer().count()).isEqualTo(1);
    }

    @Test
    void uniqueId_secondInvocationSameId_skipsMetric() {
        proxy.dedupWork("txn-001");
        proxy.dedupWork("txn-001"); // retry — same ID

        assertThat(registry.find("dedup.work.latency").timer().count()).isEqualTo(1);
    }

    @Test
    void uniqueId_differentIds_bothCounted() {
        proxy.dedupWork("txn-001");
        proxy.dedupWork("txn-002");

        assertThat(registry.find("dedup.work.latency").timer().count()).isEqualTo(2);
    }

    @Test
    void noUniqueId_everyInvocationCounted() {
        proxy.normalWork("x");
        proxy.normalWork("x");
        proxy.normalWork("x");

        assertThat(registry.find("normal.work.latency").timer().count()).isEqualTo(3);
    }

    // ── Timeout tests ─────────────────────────────────────────────────────────

    @Test
    void timeout_fastMethod_completesNormally() {
        // Fast method should complete before the 5-second timeout
        proxy.fastMethod("arg");
        assertThat(registry.find("fast.work.latency").timer().count()).isEqualTo(1);
    }

    @Test
    void timeout_slowMethod_throwsTimeoutException() {
        assertThatThrownBy(() -> proxy.slowMethod("arg"))
                .isInstanceOf(MonitoredTimeoutException.class)
                .hasMessageContaining("slowMethod")
                .hasMessageContaining("100ms");
    }

    // ── Sample bean ───────────────────────────────────────────────────────────

    static class SampleBean {

        @Monitored(
                metric = "dedup.work",
                component = "test",
                uniqueId = "#txnId"
        )
        public void dedupWork(String txnId) {
            // success
        }

        @Monitored(metric = "normal.work", component = "test")
        public void normalWork(String arg) {
            // success
        }

        @Monitored(metric = "fast.work", component = "test", timeoutMs = 5000)
        public void fastMethod(String arg) {
            // completes quickly — no timeout
        }

        @Monitored(metric = "slow.work", component = "test", timeoutMs = 100)
        public void slowMethod(String arg) throws InterruptedException {
            Thread.sleep(2000); // sleeps 2s but timeout is 100ms
        }
    }
}
