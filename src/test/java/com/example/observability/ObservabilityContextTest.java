package com.example.observability;

import com.example.observability.context.ObservabilityContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ObservabilityContext}.
 */
class ObservabilityContextTest {

    private MeterRegistry registry;
    private Tracer tracer;
    private Span span;
    private ObservabilityContext ctx;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        tracer = mock(Tracer.class);
        span = mock(Span.class);
        when(span.event(anyString())).thenReturn(span);
        ctx = new ObservabilityContext(registry, tracer);
    }

    // ── Counter tests ────────────────────────────────────────────────────────

    @Test
    void incrementCounter_byOne_registersAndIncrementsCounter() {
        ctx.incrementCounter("kafka.records.polled", "order-processor");
        assertThat(registry.find("kafka.records.polled")
                .tag("component", "order-processor")
                .counter()).isNotNull();
        assertThat(registry.find("kafka.records.polled").counter().count()).isEqualTo(1.0);
    }

    @Test
    void incrementCounter_byDelta_addsCorrectAmount() {
        ctx.incrementCounter("kafka.records.polled", "order-processor", 150d);
        assertThat(registry.find("kafka.records.polled").counter().count()).isEqualTo(150.0);
    }

    @Test
    void incrementCounter_withExtraTags_attachesTagsToCounter() {
        ctx.incrementCounter("kafka.records.processed", "order-processor", 1d, "outcome", "success");
        assertThat(registry.find("kafka.records.processed")
                .tag("outcome", "success")
                .counter()).isNotNull();
    }

    @Test
    void incrementCounter_multipleOutcomes_trackedSeparately() {
        ctx.incrementCounter("kafka.records.processed", "op", 10d, "outcome", "success");
        ctx.incrementCounter("kafka.records.processed", "op", 3d, "outcome", "retry");
        ctx.incrementCounter("kafka.records.processed", "op", 1d, "outcome", "dlq");

        assertThat(registry.find("kafka.records.processed").tag("outcome", "success").counter().count())
                .isEqualTo(10.0);
        assertThat(registry.find("kafka.records.processed").tag("outcome", "retry").counter().count())
                .isEqualTo(3.0);
        assertThat(registry.find("kafka.records.processed").tag("outcome", "dlq").counter().count())
                .isEqualTo(1.0);
    }

    // ── Span event tests ─────────────────────────────────────────────────────

    @Test
    void recordEvent_withActiveSpan_callsSpanEvent() {
        when(tracer.currentSpan()).thenReturn(span);

        ctx.recordEvent("DLQ_ROUTING", "record 123 sent to DLQ");

        verify(span).event("DLQ_ROUTING: record 123 sent to DLQ");
    }

    @Test
    void recordEvent_nameOnly_withActiveSpan_callsSpanEvent() {
        when(tracer.currentSpan()).thenReturn(span);

        ctx.recordEvent("CACHE_MISS");

        verify(span).event("CACHE_MISS");
    }

    @Test
    void recordEvent_noActiveSpan_doesNotThrow() {
        when(tracer.currentSpan()).thenReturn(null);

        // Should not throw
        ctx.recordEvent("SOME_EVENT", "description");
        verify(span, never()).event(anyString());
    }

    @Test
    void recordEvent_noActiveSpan_nameOnly_doesNotThrow() {
        when(tracer.currentSpan()).thenReturn(null);

        ctx.recordEvent("SOME_EVENT");
        verify(span, never()).event(anyString());
    }

    // ── State transition tests ────────────────────────────────────────────────

    @Test
    void recordStateTransition_withActiveSpan_attachesSpanEventAndTags() {
        when(tracer.currentSpan()).thenReturn(span);

        ctx.recordStateTransition("payment", "PENDING", "PROCESSED");

        verify(span).event("STATE_TRANSITION: payment [PENDING -> PROCESSED]");
        verify(span).tag("state.entity_type", "payment");
        verify(span).tag("state.from", "PENDING");
        verify(span).tag("state.to", "PROCESSED");
    }

    @Test
    void recordStateTransition_noActiveSpan_doesNotThrow() {
        when(tracer.currentSpan()).thenReturn(null);

        // Should not throw
        ctx.recordStateTransition("payment", "PENDING", "PROCESSED");
        verify(span, never()).event(anyString());
    }

    // ── Correlation ID tests ──────────────────────────────────────────────────

    @Test
    void getCorrelationId_withActiveSpan_returnsTraceId() {
        io.micrometer.tracing.TraceContext context = mock(io.micrometer.tracing.TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn("abc123");

        assertThat(ctx.getCorrelationId()).isEqualTo("abc123");
    }

    @Test
    void getCorrelationId_noActiveSpan_returnsNull() {
        when(tracer.currentSpan()).thenReturn(null);
        assertThat(ctx.getCorrelationId()).isNull();
    }

    @Test
    void enrichWithCorrelationId_withActiveSpan_populatesHeaders() {
        io.micrometer.tracing.TraceContext context = mock(io.micrometer.tracing.TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn("trace-xyz");
        when(context.spanId()).thenReturn("span-abc");

        java.util.Map<String, String> headers = new java.util.HashMap<>();
        ctx.enrichWithCorrelationId(headers);

        assertThat(headers).containsEntry("X-Trace-Id", "trace-xyz");
        assertThat(headers).containsEntry("X-Span-Id", "span-abc");
    }

    @Test
    void enrichWithCorrelationId_noActiveSpan_headersUnchanged() {
        when(tracer.currentSpan()).thenReturn(null);

        java.util.Map<String, String> headers = new java.util.HashMap<>();
        ctx.enrichWithCorrelationId(headers);

        assertThat(headers).isEmpty();
    }
}
