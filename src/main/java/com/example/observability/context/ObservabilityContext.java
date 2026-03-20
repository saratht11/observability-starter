package com.example.observability.context;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Programmatic observability facade that wraps Micrometer {@link MeterRegistry} and
 * Micrometer Tracing {@link Tracer}.
 *
 * <p>Use this service when AOP-based annotations ({@code @Monitored}, {@code @MonitoredCounter})
 * are not suitable — for example inside Kafka batch listeners where a single method invocation
 * processes many records and you need to increment counters by a specific delta, or when you
 * want to attach structured events to the active OTel span at precise points in time.
 *
 * <p><b>Cardinality contract:</b> always supply a non-blank {@code component} tag.
 * Keep all other tag values low-cardinality (no user IDs, UUIDs, or other unbounded values).
 *
 * <h2>Counter example — Kafka batch</h2>
 * <pre>{@code
 * observabilityContext.incrementCounter("kafka.records.polled",
 *     "order-processor", records.size(), "topic", "orders");
 *
 * observabilityContext.incrementCounter("kafka.records.processed",
 *     "order-processor", successCount, "outcome", "success");
 * }</pre>
 *
 * <h2>Span event example — DLQ routing</h2>
 * <pre>{@code
 * observabilityContext.recordEvent("DLQ_ROUTING",
 *     "Record " + record.key() + " sent to DLQ after max retries");
 * }</pre>
 *
 * <h2>State transition example</h2>
 * <pre>{@code
 * observabilityContext.recordStateTransition("payment", "PENDING", "PROCESSED");
 * }</pre>
 *
 * <h2>Correlation ID example</h2>
 * <pre>{@code
 * String traceId = observabilityContext.getCorrelationId();
 * observabilityContext.enrichWithCorrelationId(outboundHttpHeaders);
 * }</pre>
 */
@Service
public class ObservabilityContext {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityContext.class);

    private final MeterRegistry registry;
    private final Tracer tracer;

    public ObservabilityContext(MeterRegistry registry, Tracer tracer) {
        this.registry = registry;
        this.tracer = tracer;
    }

    // ── Counter helpers ───────────────────────────────────────────────────────

    /**
     * Increments a counter by {@code delta}.
     *
     * @param metric    counter metric name (e.g. {@code kafka.records.polled})
     * @param component the owning component / subsystem (required for cardinality compliance)
     * @param delta     amount to add; must be &gt; 0
     * @param extraTags additional key=value tag pairs ({@code "key", "value", "key2", "value2"})
     */
    public void incrementCounter(String metric, String component, double delta, String... extraTags) {
        Tags tags = buildTags(component, extraTags);
        Counter.builder(metric)
                .description("Programmatic counter for " + metric)
                .tags(tags)
                .register(registry)
                .increment(delta);
    }

    /**
     * Increments a counter by {@code 1}.
     *
     * @param metric    counter metric name
     * @param component the owning component / subsystem
     * @param extraTags additional key=value tag pairs
     */
    public void incrementCounter(String metric, String component, String... extraTags) {
        incrementCounter(metric, component, 1d, extraTags);
    }

    // ── Span event helpers ────────────────────────────────────────────────────

    /**
     * Records a named event on the currently active OTel span, if one exists.
     *
     * <p>Span events are timestamped structured log entries that appear inside a trace,
     * making it easy to pinpoint <em>when</em> something interesting happened within a
     * longer operation (e.g. when a record was routed to the DLQ).
     *
     * @param name        short event name (e.g. {@code "DLQ_ROUTING"})
     * @param description human-readable description attached to the span annotation
     */
    public void recordEvent(String name, String description) {
        Span current = tracer.currentSpan();
        if (current == null) {
            log.debug("[ObservabilityContext] recordEvent('{}') skipped — no active span", name);
            return;
        }
        // Micrometer Tracing maps Span#event to an OTel span event / annotation
        current.event(name + ": " + description);
    }

    /**
     * Records a named event on the currently active OTel span, if one exists.
     *
     * @param name short event name (e.g. {@code "CACHE_MISS"})
     */
    public void recordEvent(String name) {
        Span current = tracer.currentSpan();
        if (current == null) {
            log.debug("[ObservabilityContext] recordEvent('{}') skipped — no active span", name);
            return;
        }
        current.event(name);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private Tags buildTags(String component, String... extraTags) {
        Tags tags = Tags.empty();

        if (component != null && !component.isBlank()) {
            tags = tags.and("component", component);
        }

        if (extraTags.length % 2 != 0) {
            log.warn("[ObservabilityContext] extraTags must be key-value pairs; ignoring last element");
        }
        for (int i = 0; i + 1 < extraTags.length; i += 2) {
            String key = extraTags[i];
            String value = extraTags[i + 1];
            if (key != null && !key.isBlank()) {
                tags = tags.and(key, value != null ? value : "unknown");
            }
        }

        return tags;
    }

    // ── State transition helpers ──────────────────────────────────────────────

    /**
     * Records a domain state transition by:
     * <ol>
     *   <li>Logging a structured {@code INFO} message for log aggregation pipelines.</li>
     *   <li>Attaching a span event to the currently active OTel span so the transition
     *       is visible within the distributed trace.</li>
     * </ol>
     *
     * <p>To also emit a Micrometer counter, publish a
     * {@link com.example.observability.events.StateChangeEvent} via
     * {@link org.springframework.context.ApplicationEventPublisher}.
     *
     * @param entityType low-cardinality entity type (e.g. {@code "payment"})
     * @param fromState  previous state (e.g. {@code "PENDING"})
     * @param toState    new state (e.g. {@code "PROCESSED"})
     */
    public void recordStateTransition(String entityType, String fromState, String toState) {
        log.info("[ObservabilityContext] State transition: {} {} -> {}", entityType, fromState, toState);

        Span current = tracer.currentSpan();
        if (current != null) {
            current.event("STATE_TRANSITION: " + entityType + " [" + fromState + " -> " + toState + "]");
            current.tag("state.entity_type", entityType != null ? entityType : "unknown");
            current.tag("state.from", fromState != null ? fromState : "unknown");
            current.tag("state.to", toState != null ? toState : "unknown");
        }
    }

    // ── Cross-service correlation helpers ─────────────────────────────────────

    /**
     * Returns the trace ID of the currently active OTel span, or {@code null} if there
     * is no active span.  Use this as a cross-service correlation ID in outbound requests
     * or log messages.
     *
     * @return the current trace ID string, or {@code null}
     */
    public String getCorrelationId() {
        Span current = tracer.currentSpan();
        if (current == null || current.context() == null) {
            return null;
        }
        return current.context().traceId();
    }

    /**
     * Enriches a mutable headers map with OTel trace and span IDs for cross-service
     * correlation.  Call this before dispatching outbound HTTP or messaging requests.
     *
     * <p>Adds:
     * <ul>
     *   <li>{@code X-Trace-Id} — current OTel trace ID</li>
     *   <li>{@code X-Span-Id}  — current OTel span ID</li>
     * </ul>
     *
     * @param headers mutable map to enrich (e.g. HTTP request headers)
     */
    public void enrichWithCorrelationId(java.util.Map<String, String> headers) {
        Span current = tracer.currentSpan();
        if (current == null || current.context() == null) {
            log.debug("[ObservabilityContext] enrichWithCorrelationId skipped — no active span");
            return;
        }
        headers.put("X-Trace-Id", current.context().traceId());
        headers.put("X-Span-Id", current.context().spanId());
    }
}
