package com.example.observability.events;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Spring {@link EventListener} that reacts to {@link StateChangeEvent}s and automatically:
 * <ol>
 *   <li>Emits a Micrometer {@code state.transitions.total} {@link Counter} tagged with
 *       {@code component}, {@code entity_type}, {@code from_state}, and {@code to_state}.</li>
 *   <li>Records a structured span event on the currently active OTel span (if one exists),
 *       enabling trace-level visibility into state transitions.</li>
 *   <li>Logs the transition at {@code INFO} level for log aggregation pipelines.</li>
 * </ol>
 *
 * <p>To integrate, publish a {@link StateChangeEvent} from any Spring-managed bean:
 * <pre>{@code
 * eventPublisher.publishEvent(StateChangeEvent.builder()
 *     .source(this)
 *     .component("payments")
 *     .entityType("payment")
 *     .entityId(payment.getId())
 *     .fromState(previousState)
 *     .toState(payment.getState())
 *     .build());
 * }</pre>
 */
@Component
public class StateEventMetricsListener {

    private static final Logger log = LoggerFactory.getLogger(StateEventMetricsListener.class);

    /** Metric name for all state-transition counters. */
    public static final String METRIC_NAME = "state.transitions.total";

    private final MeterRegistry registry;
    private final Tracer tracer;

    public StateEventMetricsListener(MeterRegistry registry, Tracer tracer) {
        this.registry = registry;
        this.tracer = tracer;
    }

    /**
     * Handles a {@link StateChangeEvent} by emitting a counter and a span event.
     *
     * @param event the state-change event to process
     */
    @EventListener
    public void onStateChange(StateChangeEvent event) {
        String component  = blankToUnknown(event.getComponent());
        String entityType = blankToUnknown(event.getEntityType());
        String fromState  = blankToUnknown(event.getFromState());
        String toState    = blankToUnknown(event.getToState());

        // ── 1. Emit a low-cardinality Micrometer counter ──────────────────────
        Counter.builder(METRIC_NAME)
                .description("Total number of domain state transitions")
                .tag("component",   component)
                .tag("entity_type", entityType)
                .tag("from_state",  fromState)
                .tag("to_state",    toState)
                .register(registry)
                .increment();

        // ── 2. Attach a span event to the active OTel span ─────────────────────
        Span current = tracer.currentSpan();
        if (current != null) {
            String spanEventName = "STATE_TRANSITION: " + entityType
                    + " [" + fromState + " -> " + toState + "]";
            current.event(spanEventName);

            // High-cardinality entity ID goes to span only (never a metric tag)
            if (event.getEntityId() != null && !event.getEntityId().isBlank()) {
                current.tag("state.entity_id", event.getEntityId());
            }
        }

        // ── 3. Structured log for aggregation pipelines ────────────────────────
        log.info("[StateEventMetricsListener] component={} entityType={} entityId={} {}->{}",
                component, entityType, event.getEntityId(), fromState, toState);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String blankToUnknown(String value) {
        return (value == null || value.isBlank()) ? "unknown" : value;
    }
}
