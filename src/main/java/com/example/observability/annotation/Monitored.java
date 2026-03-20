package com.example.observability.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method for automatic observability instrumentation.
 *
 * <p>When applied to a Spring-managed bean method, {@code MonitoredAspect} intercepts
 * the invocation and automatically:
 * <ul>
 *   <li>Records latency as a Micrometer {@code Timer}</li>
 *   <li>Tracks in-flight invocations via a {@code LongTaskTimer} (when {@link #trackActive} is true)</li>
 *   <li>Increments an error counter on exceptions</li>
 *   <li>Optionally creates a child tracing span</li>
 *   <li>Optionally records OpenTelemetry baggage fields as metric tags</li>
 * </ul>
 *
 * <p><b>Cardinality contract:</b> keep metric tags low-cardinality.
 * Use {@link #spanTags} for high-cardinality values.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Monitored {

    /**
     * Base metric name.
     * Used as prefix for all emitted metrics (e.g. {@code payment.processing.latency}).
     * Must be stable, lowercase, dot-separated.
     */
    String metric() default "";

    /**
     * Logical component or subsystem name.
     * Added as a {@code component} tag to every metric.
     */
    String component() default "";

    /**
     * Static key=value tags to attach to every metric.
     * Format: {@code "key=value"}.
     * <p>Example: {@code tags = {"operation=charge", "region=eu"}}
     */
    String[] tags() default {};

    /**
     * Dynamic (SpEL-evaluated) low-cardinality tags for both metrics and spans.
     * Format: {@code "key=#spelExpression"}.
     * <p><b>MUST be low-cardinality.</b> Do not use IDs, UUIDs, or other unbounded values here.
     * <p>Example: {@code dynamicTags = {"channel=#channel", "partner_tier=#tier"}}
     */
    String[] dynamicTags() default {};

    /**
     * Dynamic (SpEL-evaluated) high-cardinality tags attached to tracing spans <em>only</em>.
     * These are never added to metrics.
     * <p>Example: {@code spanTags = {"user_id=#userId", "request_id=#requestId"}}
     */
    String[] spanTags() default {};

    /**
     * SLO threshold in milliseconds.
     * When set, a {@code slo_breach} tag is added with value {@code true/false}
     * and the timer is configured with a histogram bucket at this threshold.
     */
    long sloMs() default 0;

    /**
     * Whether to record OpenTelemetry baggage fields as metric tags.
     * <p>Use sparingly — baggage values must be bounded (low-cardinality).
     * Configure which fields to record in {@code application.yml} under
     * {@code observability.baggage.fields}.
     */
    boolean recordBaggage() default false;

    /**
     * Explicit baggage field names to record as tags for this method.
     * Overrides the global {@code observability.baggage.fields} configuration when non-empty.
     */
    String[] baggageFields() default {};

    /**
     * Whether to publish latency percentiles (p50, p90, p99).
     * Defaults to global configuration value.
     */
    boolean percentiles() default false;

    /**
     * When {@code true}, only errors are recorded (success latency is not emitted).
     * Useful for very high-volume methods where only failure rates matter.
     */
    boolean recordOnlyErrors() default false;

    /**
     * Whether to track in-flight (active) invocations via a {@code LongTaskTimer}.
     * Emits a {@code <metric>.latency.active} metric.
     */
    boolean trackActive() default false;

    /**
     * Whether to create a child tracing span around the method execution.
     * Adds tracing overhead — use on meaningful boundaries only.
     */
    boolean createSpan() default false;

    /**
     * Custom span name. Defaults to {@link #metric} when empty.
     * Only relevant when {@link #createSpan} is {@code true}.
     */
    String spanName() default "";
}
