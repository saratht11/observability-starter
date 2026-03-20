package com.example.observability;

import com.example.observability.annotation.PaymentMonitored;
import com.example.observability.aspect.PaymentMonitoredAspect;
import com.example.observability.baggage.BaggageReader;
import com.example.observability.dedup.DeduplicationCache;
import com.example.observability.metrics.LatencyRecorder;
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
 * Unit tests for {@link PaymentMonitoredAspect}.
 */
class PaymentMonitoredAspectTest {

    private MeterRegistry registry;
    private DeduplicationCache deduplicationCache;
    private SamplePaymentBean proxy;

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

        LatencyRecorder recorder = new LatencyRecorder(registry);
        PaymentMonitoredAspect aspect = new PaymentMonitoredAspect(
                recorder, baggageReader, tracer, deduplicationCache);

        AspectJProxyFactory factory = new AspectJProxyFactory(new SamplePaymentBean());
        factory.addAspect(aspect);
        proxy = factory.getProxy();
    }

    @Test
    void successPath_recordsLatencyTimer() {
        proxy.chargePayment("pay-001", "PENDING");
        assertThat(registry.find("payment.charge.latency").timer()).isNotNull();
        assertThat(registry.find("payment.charge.latency").timer().count()).isEqualTo(1);
    }

    @Test
    void stateTag_isAddedToMetrics() {
        proxy.chargePayment("pay-001", "PENDING");
        assertThat(registry.find("payment.charge.latency")
                .tag("state", "PENDING")
                .timer()).isNotNull();
    }

    @Test
    void componentTag_isAddedToMetrics() {
        proxy.chargePayment("pay-001", "PENDING");
        assertThat(registry.find("payment.charge.latency")
                .tag("component", "payments")
                .timer()).isNotNull();
    }

    @Test
    void deduplication_secondCallWithSameId_skipsMetric() {
        proxy.chargePaymentWithDedup("pay-dup", "PENDING");
        proxy.chargePaymentWithDedup("pay-dup", "PROCESSING"); // same ID — duplicate

        // Only the first invocation should be counted
        assertThat(registry.find("payment.dedup.charge.latency").timer().count()).isEqualTo(1);
    }

    @Test
    void deduplication_differentIds_bothCounted() {
        proxy.chargePaymentWithDedup("pay-001", "PENDING");
        proxy.chargePaymentWithDedup("pay-002", "PENDING");

        assertThat(registry.find("payment.dedup.charge.latency").timer().count()).isEqualTo(2);
    }

    @Test
    void errorPath_recordsErrorCounter() {
        assertThatThrownBy(() -> proxy.failingCharge("pay-err", "PENDING"))
                .isInstanceOf(RuntimeException.class);
        assertThat(registry.find("payment.failing.charge.error.total").counter()).isNotNull();
        assertThat(registry.find("payment.failing.charge.error.total").counter().count()).isEqualTo(1.0);
    }

    // ── Sample bean ──────────────────────────────────────────────────────────

    static class SamplePaymentBean {

        @PaymentMonitored(
                metric = "payment.charge",
                component = "payments",
                stateExpression = "#state"
        )
        public void chargePayment(String paymentId, String state) {
            // success
        }

        @PaymentMonitored(
                metric = "payment.dedup.charge",
                component = "payments",
                stateExpression = "#state",
                uniqueIdExpression = "#paymentId"
        )
        public void chargePaymentWithDedup(String paymentId, String state) {
            // success
        }

        @PaymentMonitored(
                metric = "payment.failing.charge",
                component = "payments",
                stateExpression = "#state"
        )
        public void failingCharge(String paymentId, String state) {
            throw new RuntimeException("Charge failed");
        }
    }
}
