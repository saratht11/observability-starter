package com.example.observability.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Composed observability annotation tailored for payment operations.
 *
 * <p>Extends the concepts of {@link Monitored} with two payment-specific capabilities:
 * <ol>
 *   <li><b>State tagging</b> — a SpEL {@link #stateExpression} is evaluated and attached as
 *       a low-cardinality {@code state} metric tag and span tag, enabling dashboards such as
 *       "latency by payment state" without extra boilerplate.</li>
 *   <li><b>ID-based deduplication</b> — a SpEL {@link #uniqueIdExpression} is evaluated to
 *       produce a deduplication key.  Retried invocations that share the same key within the
 *       TTL window are counted only once, preventing retry storms from inflating metrics.</li>
 * </ol>
 *
 * <p>All standard {@link Monitored} behaviours (latency timer, error counter, SLO tracking,
 * active invocation tracking, span creation, baggage recording) are also supported.
 *
 * <h3>Example</h3>
 * <pre>{@code
 * @PaymentMonitored(
 *     metric            = "payment.charge",
 *     component         = "payments",
 *     stateExpression   = "#payment.getState()",
 *     uniqueIdExpression= "#payment.getId()",
 *     sloMs             = 800,
 *     trackActive       = true,
 *     createSpan        = true
 * )
 * public Receipt chargePayment(Payment payment) { ... }
 * }</pre>
 *
 * <p><b>Cardinality contract:</b> {@link #stateExpression} <em>must</em> resolve to a
 * low-cardinality value (a finite set of well-known states such as {@code PENDING},
 * {@code PROCESSED}, {@code FAILED}).  Do not use IDs or unbounded values here.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PaymentMonitored {

    // ── Standard @Monitored attributes ────────────────────────────────────────

    /**
     * Base metric name (e.g. {@code payment.charge}).
     * Falls back to {@code <ClassName>.<methodName>} when empty.
     */
    String metric() default "";

    /**
     * Logical component name. Defaults to {@code "payments"} for this annotation.
     */
    String component() default "payments";

    /**
     * Static key=value tags attached to every metric and span.
     * Format: {@code "key=value"}.
     */
    String[] tags() default {};

    /**
     * Dynamic (SpEL-evaluated) low-cardinality tags for metrics and spans.
     * Format: {@code "key=#spelExpression"}.
     */
    String[] dynamicTags() default {};

    /**
     * Dynamic (SpEL-evaluated) high-cardinality tags for spans only.
     * Never added to metrics.
     */
    String[] spanTags() default {};

    /**
     * SLO threshold in milliseconds.  Adds an SLO histogram bucket and a
     * {@code slo_breach} tag when non-zero.
     */
    long sloMs() default 0;

    /**
     * Whether to publish p50/p90/p99 percentiles for the latency timer.
     */
    boolean percentiles() default false;

    /**
     * When {@code true}, only errors are recorded (success latency is not emitted).
     */
    boolean recordOnlyErrors() default false;

    /**
     * Whether to track in-flight invocations via a {@code LongTaskTimer}.
     */
    boolean trackActive() default false;

    /**
     * Whether to create a child OTel tracing span. Defaults to {@code true} for
     * payment operations.
     */
    boolean createSpan() default true;

    /**
     * Custom span name.  Defaults to {@link #metric} when empty.
     */
    String spanName() default "";

    /**
     * Timeout in milliseconds.  When {@code > 0}, the calling thread is interrupted
     * if the method does not complete in time and a
     * {@link com.example.observability.exception.MonitoredTimeoutException} is propagated.
     */
    long timeoutMs() default 0;

    /**
     * Whether to record OTel baggage fields as metric tags.
     */
    boolean recordBaggage() default false;

    /**
     * Explicit baggage field names to record as tags for this method.
     */
    String[] baggageFields() default {};

    // ── Payment-specific attributes ───────────────────────────────────────────

    /**
     * SpEL expression that resolves to the <em>current state</em> of the payment/domain
     * object being processed.  The resolved value is added as a low-cardinality
     * {@code state} tag to both metrics and spans.
     *
     * <p><b>Must resolve to a low-cardinality value</b> — a finite set of well-known
     * state strings (e.g. {@code PENDING}, {@code PROCESSED}, {@code FAILED}).
     *
     * <p>Example: {@code stateExpression = "#payment.getState()"}
     */
    String stateExpression() default "";

    /**
     * SpEL expression that resolves to a unique identifier for this payment operation.
     * Used to implement deduplication: retried invocations that share the same resolved
     * ID within the TTL window are counted only once.
     *
     * <p>Example: {@code uniqueIdExpression = "#payment.getId()"}
     *
     * <p>The resolved value is high-cardinality and is <em>never</em> added to metrics —
     * it is used only as a deduplication cache key and optionally as a span tag.
     */
    String uniqueIdExpression() default "";
}
