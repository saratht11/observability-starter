package com.example.observability.aspect;

import com.example.observability.annotation.Monitored;
import com.example.observability.baggage.BaggageReader;
import com.example.observability.dedup.DeduplicationCache;
import com.example.observability.metrics.LatencyRecorder;
import com.example.observability.metrics.MonitoredTagResolver;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import com.example.observability.exception.MonitoredTimeoutException;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Spring AOP aspect that intercepts methods annotated with {@link Monitored} and
 * automatically records observability signals.
 *
 * <p>For each intercepted invocation:
 * <ol>
 *   <li>Resolves metric and span tags via {@link MonitoredTagResolver}</li>
 *   <li>Optionally reads OpenTelemetry baggage fields</li>
 *   <li>Optionally starts a child tracing span</li>
 *   <li>Optionally starts a {@link LongTaskTimer} to track active invocations</li>
 *   <li>Executes the target method</li>
 *   <li>Records latency (success path) or error counter + latency (failure path)</li>
 *   <li>Stops the active timer and span</li>
 * </ol>
 */
@Aspect
@Component
public class MonitoredAspect {

    private static final Logger log = LoggerFactory.getLogger(MonitoredAspect.class);

    private final MonitoredTagResolver tagResolver;
    private final LatencyRecorder latencyRecorder;
    private final BaggageReader baggageReader;
    private final Tracer tracer;
    private final DeduplicationCache deduplicationCache;

    private final ExpressionParser spelParser = new SpelExpressionParser();
    private final ParameterNameDiscoverer nameDiscoverer = new DefaultParameterNameDiscoverer();

    /**
     * Single-thread scheduled executor used to implement per-invocation timeouts.
     * Interrupts the calling thread when the configured {@code timeoutMs} is exceeded.
     */
    private final ScheduledExecutorService timeoutScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "monitored-timeout-scheduler");
                t.setDaemon(true);
                return t;
            });

    @Value("${observability.baggage.fields:}")
    private List<String> globalBaggageFields;

    @Value("${observability.metrics.percentiles:false}")
    private boolean globalPercentiles;

    public MonitoredAspect(MonitoredTagResolver tagResolver,
                           LatencyRecorder latencyRecorder,
                           BaggageReader baggageReader,
                           Tracer tracer,
                           DeduplicationCache deduplicationCache) {
        this.tagResolver = tagResolver;
        this.latencyRecorder = latencyRecorder;
        this.baggageReader = baggageReader;
        this.tracer = tracer;
        this.deduplicationCache = deduplicationCache;
    }

    @Around("@annotation(monitored)")
    public Object intercept(ProceedingJoinPoint pjp, Monitored monitored) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        Object[] args = pjp.getArgs();

        String metricName = resolveMetricName(monitored, method);
        List<Tag> metricTags = tagResolver.resolveMetricTags(monitored, method, args);

        // Optionally append baggage fields to metric tags
        if (monitored.recordBaggage()) {
            List<String> fields = resolveBaggageFields(monitored);
            for (String field : fields) {
                String value = baggageReader.read(field);
                if (value != null && !value.isBlank()) {
                    metricTags = new ArrayList<>(metricTags);
                    metricTags.add(Tag.of(field, value));
                }
            }
        }

        // Deduplication: skip metric recording when the same uniqueId is seen within TTL
        boolean deduplicated = false;
        if (!monitored.uniqueId().isBlank()) {
            String resolvedId = evaluateSpel(monitored.uniqueId(), method, args);
            if (resolvedId != null && !resolvedId.isBlank()) {
                String cacheKey = metricName + ":" + resolvedId;
                if (!deduplicationCache.tryRegister(cacheKey)) {
                    log.debug("[MonitoredAspect] Duplicate invocation detected for key '{}', skipping metric recording",
                            cacheKey);
                    deduplicated = true;
                }
            }
        }

        // Build timer (always, unless recordOnlyErrors or deduplicated)
        boolean recordOnlyErrors = monitored.recordOnlyErrors();
        boolean usePercentiles = monitored.percentiles() || globalPercentiles;
        Timer timer = (recordOnlyErrors || deduplicated)
                ? null
                : latencyRecorder.buildTimer(metricName, metricTags, monitored.sloMs(), usePercentiles);

        // Build long task timer for active invocations tracking
        LongTaskTimer.Sample activeSample = null;
        if (monitored.trackActive()) {
            LongTaskTimer ltt = latencyRecorder.buildLongTaskTimer(metricName, metricTags);
            activeSample = ltt.start();
        }

        // Optionally start a child span
        Span span = null;
        if (monitored.createSpan()) {
            String spanName = monitored.spanName().isBlank() ? metricName : monitored.spanName();
            span = tracer.nextSpan().name(spanName).start();
            enrichSpan(span, monitored, method, args);
            // Attach cross-service correlation ID as a span tag
            attachCorrelationTags(span);
        }

        // Timeout setup: schedule a thread interrupt if timeoutMs > 0
        ScheduledFuture<?> timeoutTask = null;
        Thread callingThread = Thread.currentThread();
        if (monitored.timeoutMs() > 0) {
            long timeoutMs = monitored.timeoutMs();
            timeoutTask = timeoutScheduler.schedule(
                    () -> callingThread.interrupt(),
                    timeoutMs,
                    TimeUnit.MILLISECONDS);
        }

        long startNanos = System.nanoTime();
        try {
            Object result = pjp.proceed();
            long elapsedNanos = System.nanoTime() - startNanos;

            if (timer != null) {
                latencyRecorder.recordSuccess(timer, elapsedNanos, metricTags, monitored.sloMs());
            }

            if (span != null) {
                span.tag("outcome", "success");
            }
            return result;

        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            long elapsedNanos = System.nanoTime() - startNanos;
            boolean isTimeout = monitored.timeoutMs() > 0 && elapsedNanos >= monitored.timeoutMs() * 1_000_000L;
            handleError(metricName, metricTags, timer, elapsedNanos, recordOnlyErrors, usePercentiles,
                    monitored.sloMs(), deduplicated, span, ex);
            if (isTimeout) {
                throw new MonitoredTimeoutException("Method " + method.getName()
                        + " exceeded timeout of " + monitored.timeoutMs() + "ms", monitored.timeoutMs());
            }
            throw ex;

        } catch (Throwable ex) {
            long elapsedNanos = System.nanoTime() - startNanos;
            // Check if interrupted due to our timeout task
            if (Thread.currentThread().isInterrupted() && monitored.timeoutMs() > 0) {
                Thread.currentThread().interrupt();
                handleError(metricName, metricTags, timer, elapsedNanos, recordOnlyErrors,
                        usePercentiles, monitored.sloMs(), deduplicated, span, ex);
                throw new MonitoredTimeoutException("Method " + method.getName()
                        + " exceeded timeout of " + monitored.timeoutMs() + "ms", monitored.timeoutMs());
            }
            handleError(metricName, metricTags, timer, elapsedNanos, recordOnlyErrors, usePercentiles,
                    monitored.sloMs(), deduplicated, span, ex);
            throw ex;

        } finally {
            if (timeoutTask != null) {
                timeoutTask.cancel(false);
                // Clear any interrupt that was set by the timeout task but method already returned
                Thread.interrupted();
            }
            if (activeSample != null) {
                activeSample.stop();
            }
            if (span != null) {
                span.end();
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String resolveMetricName(Monitored monitored, Method method) {
        if (!monitored.metric().isBlank()) {
            return monitored.metric();
        }
        // Fall back to class.method naming if no metric name specified
        return method.getDeclaringClass().getSimpleName().toLowerCase()
                + "." + method.getName().toLowerCase();
    }

    private List<String> resolveBaggageFields(Monitored monitored) {
        if (monitored.baggageFields().length > 0) {
            return List.of(monitored.baggageFields());
        }
        return globalBaggageFields != null ? globalBaggageFields : List.of();
    }

    private void enrichSpan(Span span, Monitored monitored, Method method, Object[] args) {
        List<Tag> spanTags = tagResolver.resolveSpanTags(monitored, method, args);
        for (Tag tag : spanTags) {
            span.tag(tag.getKey(), tag.getValue());
        }
    }

    /**
     * Attaches the current trace and span IDs as tags to the given span for
     * cross-service correlation.  These high-cardinality values belong in the span only.
     */
    private void attachCorrelationTags(Span span) {
        try {
            Span current = tracer.currentSpan();
            if (current != null && current.context() != null) {
                span.tag("correlation.trace_id", current.context().traceId());
                span.tag("correlation.parent_span_id", current.context().spanId());
            }
        } catch (Exception e) {
            // Non-critical — best-effort correlation
            log.debug("[MonitoredAspect] Could not attach correlation tags: {}", e.getMessage());
        }
    }

    private void handleError(String metricName, List<Tag> metricTags, Timer timer,
                              long elapsedNanos, boolean recordOnlyErrors, boolean usePercentiles,
                              long sloMs, boolean deduplicated, Span span, Throwable ex) {
        if (!deduplicated) {
            Timer errorTimer = timer;
            if (recordOnlyErrors) {
                errorTimer = latencyRecorder.buildTimer(metricName, metricTags, sloMs, usePercentiles);
            }
            latencyRecorder.recordError(metricName, metricTags, errorTimer, elapsedNanos);
        }
        if (span != null) {
            span.tag("outcome", "error");
            span.tag("error.class", ex.getClass().getSimpleName());
            span.error(ex);
        }
    }

    /**
     * Evaluates a SpEL expression in the context of the intercepted method's arguments.
     *
     * @param expression SpEL expression string (e.g. {@code "#payment.getId()"})
     * @param method     the intercepted method
     * @param args       the method arguments
     * @return string representation of the evaluated value, or {@code null} on failure
     */
    private String evaluateSpel(String expression, Method method, Object[] args) {
        try {
            StandardEvaluationContext ctx = new MethodBasedEvaluationContext(
                    null, method, args, nameDiscoverer);
            Object value = spelParser.parseExpression(expression).getValue(ctx);
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            log.debug("[MonitoredAspect] SpEL evaluation failed for '{}': {}", expression, e.getMessage());
            return null;
        }
    }
}
