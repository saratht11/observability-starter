package com.example.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Encapsulates all Micrometer metric recording for {@code @Monitored}-annotated methods.
 *
 * <p>Emits three metric families per annotated method:
 * <ul>
 *   <li>{@code <metric>.latency} — a {@link Timer} for execution latency</li>
 *   <li>{@code <metric>.latency.active} — a {@link LongTaskTimer} for active (in-flight) invocations
 *       (only when {@code trackActive=true})</li>
 *   <li>{@code <metric>.error.total} — a {@link Counter} incremented on exceptions</li>
 * </ul>
 */
@Component
public class LatencyRecorder {

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Timer> timerCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongTaskTimer> longTaskTimerCache = new ConcurrentHashMap<>();

    public LatencyRecorder(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Returns a cached {@link Timer} for the given metric name, tags, and configuration.
     * The timer is created lazily on first use and reused on subsequent calls.
     *
     * @param metricName  base metric name
     * @param tags        resolved metric tags
     * @param sloMs       SLO threshold in milliseconds; {@code 0} means no SLO bucket
     * @param percentiles whether to publish p50/p90/p99 percentiles
     * @return the configured {@link Timer}
     */
    public Timer buildTimer(String metricName, List<Tag> tags, long sloMs, boolean percentiles) {
        String cacheKey = buildTimerKey(metricName, tags, sloMs, percentiles);
        return timerCache.computeIfAbsent(cacheKey, k -> createTimer(metricName, tags, sloMs, percentiles));
    }

    private Timer createTimer(String metricName, List<Tag> tags, long sloMs, boolean percentiles) {
        Timer.Builder builder = Timer.builder(metricName + ".latency")
                .description("Latency of " + metricName)
                .tags(Tags.of(tags));

        if (percentiles) {
            builder.publishPercentileHistogram(true);   // le buckets for histogram_quantile()
            builder.publishPercentiles(0.50, 0.90, 0.99); // client-side for /actuator/metrics
        }

        if (sloMs > 0) {
            builder.serviceLevelObjectives(Duration.ofMillis(sloMs));
        }

        return builder.register(registry);
    }

    private String buildTimerKey(String metricName, List<Tag> tags, long sloMs, boolean percentiles) {
        return metricName + "|" + serializeTags(tags) + "|slo=" + sloMs + "|pct=" + percentiles;
    }

    private String serializeTags(List<Tag> tags) {
        return tags.stream()
                .map(t -> t.getKey() + "=" + t.getValue())
                .sorted()
                .collect(Collectors.joining(","));
    }

    /**
     * Returns a cached {@link LongTaskTimer} for tracking active (in-flight) invocations.
     * The timer is created lazily on first use and reused on subsequent calls.
     *
     * @param metricName base metric name
     * @param tags       resolved metric tags
     * @return the configured {@link LongTaskTimer}
     */
    public LongTaskTimer buildLongTaskTimer(String metricName, List<Tag> tags) {
        String cacheKey = metricName + "|" + serializeTags(tags);
        return longTaskTimerCache.computeIfAbsent(cacheKey, k ->
                LongTaskTimer.builder(metricName + ".latency.active")
                        .description("Active (in-flight) invocations of " + metricName)
                        .tags(Tags.of(tags))
                        .register(registry));
    }

    /**
     * Records a successful execution.
     *
     * @param timer          the timer to record into
     * @param elapsedNanos   elapsed nanoseconds
     * @param metricTags     metric tags (used to add {@code outcome=success})
     * @param sloMs          SLO threshold in ms; adds {@code slo_breach} tag if &gt; 0
     */
    public void recordSuccess(Timer timer, long elapsedNanos, List<Tag> metricTags, long sloMs) {
        timer.record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Records a failed execution and increments the error counter.
     *
     * @param metricName   base metric name
     * @param metricTags   resolved metric tags
     * @param timer        the timer to record into (may be {@code null} if {@code recordOnlyErrors=true})
     * @param elapsedNanos elapsed nanoseconds
     */
    public void recordError(String metricName, List<Tag> metricTags, Timer timer, long elapsedNanos) {
        if (timer != null) {
            timer.record(elapsedNanos, TimeUnit.NANOSECONDS);
        }
        incrementErrorCounter(metricName, metricTags);
    }

    /**
     * Increments the error counter for the given metric.
     *
     * @param metricName base metric name
     * @param tags       resolved metric tags
     */
    public void incrementErrorCounter(String metricName, List<Tag> tags) {
        Counter.builder(metricName + ".error.total")
                .description("Error count for " + metricName)
                .tags(Tags.of(tags))
                .register(registry)
                .increment();
    }
}
