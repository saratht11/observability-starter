package com.example.observability.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method to have its invocations counted as a Micrometer {@code Counter}.
 *
 * <p>When applied to a Spring-managed bean method, {@code MonitoredCounterAspect} intercepts
 * the invocation and automatically increments the named counter by one on each call.
 *
 * <p>Typical use cases:
 * <ul>
 *   <li>Cache hit / miss tracking (place on two separate methods with different static tags)</li>
 *   <li>Counting specific business events such as DLQ routing or webhook deliveries</li>
 *   <li>Method-level invocation counts independent of latency</li>
 * </ul>
 *
 * <p><b>Cardinality contract:</b> keep all tag values low-cardinality.
 * Never use user IDs, UUIDs, or other unbounded values as tag values.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MonitoredCounter {

    /**
     * Counter metric name.
     * Must be stable, lowercase, dot-separated (e.g. {@code cache.access.total}).
     * Falls back to {@code <ClassName>.<methodName>} when empty.
     */
    String metric() default "";

    /**
     * Logical component or subsystem name.
     * Added as a {@code component} tag to the counter.
     */
    String component() default "";

    /**
     * Static key=value tags to attach to the counter.
     * Format: {@code "key=value"}.
     * <p>Example: {@code tags = {"outcome=hit", "region=eu"}}
     */
    String[] tags() default {};
}
