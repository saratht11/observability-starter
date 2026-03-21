package com.example.observability;

import com.example.observability.annotation.Monitored;
import com.example.observability.aspect.MonitoredAspect;
import com.example.observability.baggage.BaggageReader;
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
 * Unit tests for the {@link MonitoredAspect} end-to-end metric recording.
 */
class MonitoredAspectTest {

    private MeterRegistry registry;
    private MonitoredAspect aspect;
    private SampleBean proxy;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();

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
        aspect = new MonitoredAspect(tagResolver, recorder, baggageReader, tracer);

        AspectJProxyFactory factory = new AspectJProxyFactory(new SampleBean());
        factory.addAspect(aspect);
        proxy = factory.getProxy();
    }

    @Test
    void successPath_recordsLatencyTimer() {
        proxy.doWork("web");
        assertThat(registry.find("payment.processing.latency").timer()).isNotNull();
        assertThat(registry.find("payment.processing.latency").timer().count()).isEqualTo(1);
    }

    @Test
    void errorPath_recordsErrorCounter() {
        assertThatThrownBy(() -> proxy.doFail())
                .isInstanceOf(RuntimeException.class);
        assertThat(registry.find("payment.failure.error.total").counter()).isNotNull();
        assertThat(registry.find("payment.failure.error.total").counter().count()).isEqualTo(1);
    }

    @Test
    void errorPath_alsoRecordsLatency() {
        assertThatThrownBy(() -> proxy.doFail())
                .isInstanceOf(RuntimeException.class);
        assertThat(registry.find("payment.failure.latency").timer()).isNotNull();
        assertThat(registry.find("payment.failure.latency").timer().count()).isEqualTo(1);
    }

    @Test
    void componentTag_isIncludedInMetrics() {
        proxy.doWork("mobile");
        assertThat(registry.find("payment.processing.latency")
                .tag("component", "payments")
                .timer()).isNotNull();
    }

    @Test
    void dynamicTag_isResolvedViaSpel() {
        proxy.doWork("mobile");
        assertThat(registry.find("payment.processing.latency")
                .tag("channel", "mobile")
                .timer()).isNotNull();
    }

    @Test
    void recordOnlyErrors_doesNotRecordSuccessLatency() {
        proxy.doWorkErrorsOnly();
        assertThat(registry.find("payment.errorsonly.latency").timer()).isNull();
        assertThat(registry.find("payment.errorsonly.error.total").counter()).isNull();
    }

    @Test
    void successPath_hasOutcomeSuccessTag() {
        proxy.doWork("web");
        assertThat(registry.find("payment.processing.latency")
                .tag("outcome", "success")
                .timer()).isNotNull();
    }

    @Test
    void successPath_hasSloBreach_false_whenNoSloConfigured() {
        proxy.doWork("web");
        assertThat(registry.find("payment.processing.latency")
                .tag("slo_breach", "false")
                .timer()).isNotNull();
    }

    @Test
    void errorPath_hasOutcomeErrorTag() {
        assertThatThrownBy(() -> proxy.doFail())
                .isInstanceOf(RuntimeException.class);
        assertThat(registry.find("payment.failure.latency")
                .tag("outcome", "error")
                .timer()).isNotNull();
    }

    @Test
    void errorPath_errorCounter_hasOutcomeErrorTag() {
        assertThatThrownBy(() -> proxy.doFail())
                .isInstanceOf(RuntimeException.class);
        assertThat(registry.find("payment.failure.error.total")
                .tag("outcome", "error")
                .counter()).isNotNull();
    }

    @Test
    void sloBreachTag_isTrueWhenSloExceeded() throws InterruptedException {
        proxySlo.doSlowWork(); // sleeps 20ms with sloMs=5 → guaranteed breach
        assertThat(registry.find("payment.slo.latency")
                .tag("outcome", "success")
                .tag("slo_breach", "true")
                .timer()).isNotNull();
    }

    @Test
    void sloBreachTag_isFalseWhenWithinSlo() {
        proxySlo.doFastWork(); // sloMs=0 → slo_breach always false
        assertThat(registry.find("payment.slo.latency")
                .tag("outcome", "success")
                .tag("slo_breach", "false")
                .timer()).isNotNull();
    }

    // ── Sample bean ──────────────────────────────────────────────────────────

    static class SampleBean {

        @Monitored(
                metric = "payment.processing",
                component = "payments",
                dynamicTags = {"channel=#channel"}
        )
        public void doWork(String channel) {
            // success
        }

        @Monitored(
                metric = "payment.failure",
                component = "payments"
        )
        public void doFail() {
            throw new RuntimeException("test failure");
        }

        @Monitored(
                metric = "payment.errorsonly",
                component = "payments",
                recordOnlyErrors = true
        )
        public void doWorkErrorsOnly() {
            // success — no latency recorded when recordOnlyErrors=true
        }
    }

    // ── SLO sample bean ───────────────────────────────────────────────────────

    private SloBean proxySlo;

    @BeforeEach
    void setUpSlo() {
        AspectJProxyFactory factory = new AspectJProxyFactory(new SloBean());
        factory.addAspect(aspect);
        proxySlo = factory.getProxy();
    }

    static class SloBean {

        @Monitored(
                metric = "payment.slo",
                component = "payments",
                sloMs = 5
        )
        public void doSlowWork() throws InterruptedException {
            Thread.sleep(20); // 20ms > sloMs=5 → slo_breach=true
        }

        @Monitored(
                metric = "payment.slo",
                component = "payments"
                // sloMs defaults to 0 → slo_breach always false
        )
        public void doFastWork() {
            // completes instantly
        }
    }
}
