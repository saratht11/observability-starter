package com.example.observability;

import com.example.observability.events.StateChangeEvent;
import com.example.observability.events.StateEventMetricsListener;
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
 * Unit tests for {@link StateEventMetricsListener}.
 */
class StateEventMetricsListenerTest {

    private MeterRegistry registry;
    private Tracer tracer;
    private Span span;
    private StateEventMetricsListener listener;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        tracer = mock(Tracer.class);
        span = mock(Span.class);
        when(span.event(anyString())).thenReturn(span);
        when(span.tag(anyString(), anyString())).thenReturn(span);
        listener = new StateEventMetricsListener(registry, tracer);
    }

    @Test
    void onStateChange_emitsCounter() {
        StateChangeEvent event = StateChangeEvent.builder()
                .source(this)
                .component("payments")
                .entityType("payment")
                .entityId("pay-001")
                .fromState("PENDING")
                .toState("PROCESSED")
                .build();

        listener.onStateChange(event);

        assertThat(registry.find(StateEventMetricsListener.METRIC_NAME).counter()).isNotNull();
        assertThat(registry.find(StateEventMetricsListener.METRIC_NAME).counter().count()).isEqualTo(1.0);
    }

    @Test
    void onStateChange_counterHasCorrectTags() {
        StateChangeEvent event = StateChangeEvent.builder()
                .source(this)
                .component("payments")
                .entityType("payment")
                .entityId("pay-001")
                .fromState("PENDING")
                .toState("PROCESSED")
                .build();

        listener.onStateChange(event);

        assertThat(registry.find(StateEventMetricsListener.METRIC_NAME)
                .tag("component", "payments")
                .tag("entity_type", "payment")
                .tag("from_state", "PENDING")
                .tag("to_state", "PROCESSED")
                .counter()).isNotNull();
    }

    @Test
    void onStateChange_multipleTransitions_separateCounters() {
        StateChangeEvent pending2processed = StateChangeEvent.builder()
                .source(this).component("payments").entityType("payment")
                .entityId("pay-001").fromState("PENDING").toState("PROCESSED").build();
        StateChangeEvent pending2failed = StateChangeEvent.builder()
                .source(this).component("payments").entityType("payment")
                .entityId("pay-002").fromState("PENDING").toState("FAILED").build();

        listener.onStateChange(pending2processed);
        listener.onStateChange(pending2failed);
        listener.onStateChange(pending2processed); // second PENDING->PROCESSED

        assertThat(registry.find(StateEventMetricsListener.METRIC_NAME)
                .tag("to_state", "PROCESSED").counter().count()).isEqualTo(2.0);
        assertThat(registry.find(StateEventMetricsListener.METRIC_NAME)
                .tag("to_state", "FAILED").counter().count()).isEqualTo(1.0);
    }

    @Test
    void onStateChange_withActiveSpan_recordsSpanEvent() {
        when(tracer.currentSpan()).thenReturn(span);

        StateChangeEvent event = StateChangeEvent.builder()
                .source(this).component("payments").entityType("payment")
                .entityId("pay-001").fromState("PENDING").toState("PROCESSED").build();

        listener.onStateChange(event);

        verify(span).event("STATE_TRANSITION: payment [PENDING -> PROCESSED]");
        verify(span).tag("state.entity_id", "pay-001");
    }

    @Test
    void onStateChange_noActiveSpan_doesNotThrow() {
        when(tracer.currentSpan()).thenReturn(null);

        StateChangeEvent event = StateChangeEvent.builder()
                .source(this).component("payments").entityType("payment")
                .entityId("pay-001").fromState("PENDING").toState("PROCESSED").build();

        // Should not throw
        listener.onStateChange(event);

        verify(span, never()).event(anyString());
    }

    @Test
    void onStateChange_blankFields_usesUnknown() {
        StateChangeEvent event = StateChangeEvent.builder()
                .source(this).component("").entityType("").entityId("").fromState("").toState("").build();

        listener.onStateChange(event);

        assertThat(registry.find(StateEventMetricsListener.METRIC_NAME)
                .tag("component", "unknown")
                .tag("entity_type", "unknown")
                .counter()).isNotNull();
    }
}
