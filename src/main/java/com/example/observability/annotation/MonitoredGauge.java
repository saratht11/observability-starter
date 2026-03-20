package com.example.observability.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method whose return value should be registered as a Micrometer {@code Gauge}.
 *
 * <p>The annotated method must belong to a Spring-managed bean and must return a numeric
 * type ({@code int}, {@code long}, {@code double}, or their boxed equivalents).
 * At application startup, {@code GaugeRegistrar} scans all Spring beans, locates methods
 * carrying this annotation, and registers a Micrometer Gauge that periodically calls the
 * method to observe the current value.
 *
 * <p>Typical use cases:
 * <ul>
 *   <li>In-memory queue / buffer sizes</li>
 *   <li>Cache entry counts</li>
 *   <li>Thread-pool queue depths</li>
 *   <li>Any other instantaneous state that can go up <em>and</em> down</li>
 * </ul>
 *
 * <p><b>Cardinality contract:</b> keep all tag values low-cardinality.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MonitoredGauge {

    /**
     * Gauge metric name.
     * Must be stable, lowercase, dot-separated (e.g. {@code orders.queue.size}).
     * Falls back to {@code <ClassName>.<methodName>} when empty.
     */
    String metric() default "";

    /**
     * Logical component or subsystem name.
     * Added as a {@code component} tag to the gauge.
     */
    String component() default "";

    /**
     * Static key=value tags to attach to the gauge.
     * Format: {@code "key=value"}.
     * <p>Example: {@code tags = {"region=us-east"}}
     */
    String[] tags() default {};
}
