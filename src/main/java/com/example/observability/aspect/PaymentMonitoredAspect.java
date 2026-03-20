package com.example.observability.aspect;

import com.example.observability.annotation.PaymentMonitored;
import com.example.observability.baggage.BaggageReader;
import com.example.observability.dedup.DeduplicationCache;
import com.example.observability.metrics.LatencyRecorder;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.observability.exception.MonitoredTimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Spring AOP aspect that intercepts methods annotated with {@link PaymentMonitored} and
 * provides full observability instrumentation tailored for payment operations.
 *
 * <p>On each intercepted invocation this aspect:
 * <ol>
 *   <li>Evaluates the {@link PaymentMonitored#stateExpression()} via SpEL and appends it
 *       as a low-cardinality {@code state} metric tag and span tag.</li>
 *   <li>Evaluates the {@link PaymentMonitored#uniqueIdExpression()} via SpEL and uses the
 *       result as a deduplication key — retried invocations with the same ID within the TTL
 *       window are counted only once.</li>
 *   <li>Records latency, error counters, optional LongTaskTimer, and an OTel child span
 *       (with cross-service correlation ID tags) identical to {@link MonitoredAspect}.</li>
 *   <li>Enforces a method-level timeout when {@link PaymentMonitored#timeoutMs()} is set.</li>
 * </ol>
 */
@Aspect
@Component
public class PaymentMonitoredAspect {

    private static final Logger log = LoggerFactory.getLogger(PaymentMonitoredAspect.class);

    private final LatencyRecorder latencyRecorder;
    private final BaggageReader baggageReader;
    private final Tracer tracer;
    private final DeduplicationCache deduplicationCache;

    private final ExpressionParser spelParser = new SpelExpressionParser();
    private final ParameterNameDiscoverer nameDiscoverer = new DefaultParameterNameDiscoverer();

    private final ScheduledExecutorService timeoutScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "payment-monitored-timeout");
                t.setDaemon(true);
                return t;
            });

    @Value("${observability.metrics.percentiles:false}")
    private boolean globalPercentiles;

    public PaymentMonitoredAspect(LatencyRecorder latencyRecorder,
                                   BaggageReader baggageReader,
                                   Tracer tracer,
                                   DeduplicationCache deduplicationCache) {
        this.latencyRecorder = latencyRecorder;
        this.baggageReader = baggageReader;
        this.tracer = tracer;
        this.deduplicationCache = deduplicationCache;
    }

    @Around("@annotation(paymentMonitored)")
    public Object intercept(ProceedingJoinPoint pjp, PaymentMonitored paymentMonitored) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        Object[] args = pjp.getArgs();

        String metricName = resolveMetricName(paymentMonitored, method);
        List<Tag> metricTags = buildMetricTags(paymentMonitored, method, args);

        // ── State tag (SpEL) ──────────────────────────────────────────────────
        String resolvedState = null;
        if (!paymentMonitored.stateExpression().isBlank()) {
            resolvedState = evaluateSpel(paymentMonitored.stateExpression(), method, args);
            if (resolvedState != null && !resolvedState.isBlank()) {
                metricTags = new ArrayList<>(metricTags);
                metricTags.add(Tag.of("state", resolvedState));
            }
        }

        // ── Deduplication (SpEL uniqueIdExpression) ───────────────────────────
        boolean deduplicated = false;
        if (!paymentMonitored.uniqueIdExpression().isBlank()) {
            String resolvedId = evaluateSpel(paymentMonitored.uniqueIdExpression(), method, args);
            if (resolvedId != null && !resolvedId.isBlank()) {
                String cacheKey = metricName + ":" + resolvedId;
                if (!deduplicationCache.tryRegister(cacheKey)) {
                    log.debug("[PaymentMonitoredAspect] Duplicate invocation for key '{}', skipping metrics",
                            cacheKey);
                    deduplicated = true;
                }
            }
        }

        // ── Timer setup ───────────────────────────────────────────────────────
        boolean recordOnlyErrors = paymentMonitored.recordOnlyErrors();
        boolean usePercentiles = paymentMonitored.percentiles() || globalPercentiles;
        Timer timer = (recordOnlyErrors || deduplicated)
                ? null
                : latencyRecorder.buildTimer(metricName, metricTags, paymentMonitored.sloMs(), usePercentiles);

        // ── LongTaskTimer ─────────────────────────────────────────────────────
        LongTaskTimer.Sample activeSample = null;
        if (paymentMonitored.trackActive()) {
            LongTaskTimer ltt = latencyRecorder.buildLongTaskTimer(metricName, metricTags);
            activeSample = ltt.start();
        }

        // ── Span creation ─────────────────────────────────────────────────────
        Span span = null;
        if (paymentMonitored.createSpan()) {
            String spanName = paymentMonitored.spanName().isBlank() ? metricName : paymentMonitored.spanName();
            span = tracer.nextSpan().name(spanName).start();
            enrichSpan(span, paymentMonitored, method, args, resolvedState);
        }

        // ── Timeout setup ─────────────────────────────────────────────────────
        ScheduledFuture<?> timeoutTask = null;
        Thread callingThread = Thread.currentThread();
        if (paymentMonitored.timeoutMs() > 0) {
            long timeoutMs = paymentMonitored.timeoutMs();
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
                latencyRecorder.recordSuccess(timer, elapsedNanos, metricTags, paymentMonitored.sloMs());
            }
            if (span != null) {
                span.tag("outcome", "success");
            }
            return result;

        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            long elapsedNanos = System.nanoTime() - startNanos;
            recordError(metricName, metricTags, timer, elapsedNanos, recordOnlyErrors,
                    usePercentiles, paymentMonitored.sloMs(), deduplicated, span, ex);
            if (paymentMonitored.timeoutMs() > 0) {
                throw new MonitoredTimeoutException("PaymentMonitored method " + method.getName()
                        + " exceeded timeout of " + paymentMonitored.timeoutMs() + "ms",
                        paymentMonitored.timeoutMs());
            }
            throw ex;

        } catch (Throwable ex) {
            long elapsedNanos = System.nanoTime() - startNanos;
            if (Thread.currentThread().isInterrupted() && paymentMonitored.timeoutMs() > 0) {
                Thread.currentThread().interrupt();
                recordError(metricName, metricTags, timer, elapsedNanos, recordOnlyErrors,
                        usePercentiles, paymentMonitored.sloMs(), deduplicated, span, ex);
                throw new MonitoredTimeoutException("PaymentMonitored method " + method.getName()
                        + " exceeded timeout of " + paymentMonitored.timeoutMs() + "ms",
                        paymentMonitored.timeoutMs());
            }
            recordError(metricName, metricTags, timer, elapsedNanos, recordOnlyErrors,
                    usePercentiles, paymentMonitored.sloMs(), deduplicated, span, ex);
            throw ex;

        } finally {
            if (timeoutTask != null) {
                timeoutTask.cancel(false);
                Thread.interrupted(); // clear any leftover interrupt after successful return
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

    private String resolveMetricName(PaymentMonitored annotation, Method method) {
        if (!annotation.metric().isBlank()) {
            return annotation.metric();
        }
        return method.getDeclaringClass().getSimpleName().toLowerCase()
                + "." + method.getName().toLowerCase();
    }

    private List<Tag> buildMetricTags(PaymentMonitored annotation, Method method, Object[] args) {
        List<Tag> tags = new ArrayList<>();

        if (!annotation.component().isBlank()) {
            tags.add(Tag.of("component", annotation.component()));
        }

        // Static tags
        for (String entry : annotation.tags()) {
            int idx = entry.indexOf('=');
            if (idx > 0) {
                String key = entry.substring(0, idx).trim();
                String value = entry.substring(idx + 1).trim();
                if (!key.isBlank()) {
                    tags.add(Tag.of(key, value.isEmpty() ? "unknown" : value));
                }
            }
        }

        // Dynamic SpEL tags
        StandardEvaluationContext ctx = buildContext(method, args);
        for (String dynamicTag : annotation.dynamicTags()) {
            int idx = dynamicTag.indexOf('=');
            if (idx > 0) {
                String key = dynamicTag.substring(0, idx).trim();
                String spelExpr = dynamicTag.substring(idx + 1).trim();
                if (!key.isBlank() && !spelExpr.isBlank()) {
                    try {
                        Object value = spelParser.parseExpression(spelExpr).getValue(ctx);
                        String tagValue = value != null ? value.toString() : "unknown";
                        tags.add(Tag.of(key, tagValue.isBlank() ? "unknown" : tagValue));
                    } catch (Exception e) {
                        tags.add(Tag.of(key, "spel_error"));
                    }
                }
            }
        }

        // Baggage
        if (annotation.recordBaggage()) {
            String[] fields = annotation.baggageFields().length > 0
                    ? annotation.baggageFields()
                    : new String[0];
            for (String field : fields) {
                String value = baggageReader.read(field);
                if (value != null && !value.isBlank()) {
                    tags.add(Tag.of(field, value));
                }
            }
        }

        return tags;
    }

    private void enrichSpan(Span span, PaymentMonitored annotation, Method method, Object[] args,
                             String resolvedState) {
        // Add metric tags to span
        List<Tag> metricTagsForSpan = buildMetricTags(annotation, method, args);
        for (Tag tag : metricTagsForSpan) {
            span.tag(tag.getKey(), tag.getValue());
        }

        // State tag on span
        if (resolvedState != null && !resolvedState.isBlank()) {
            span.tag("state", resolvedState);
        }

        // High-cardinality spanTags (trace only)
        StandardEvaluationContext ctx = buildContext(method, args);
        for (String spanTag : annotation.spanTags()) {
            int idx = spanTag.indexOf('=');
            if (idx > 0) {
                String key = spanTag.substring(0, idx).trim();
                String spelExpr = spanTag.substring(idx + 1).trim();
                if (!key.isBlank() && !spelExpr.isBlank()) {
                    try {
                        Object value = spelParser.parseExpression(spelExpr).getValue(ctx);
                        span.tag(key, value != null ? value.toString() : "unknown");
                    } catch (Exception e) {
                        span.tag(key, "spel_error");
                    }
                }
            }
        }

        // Cross-service correlation
        try {
            Span current = tracer.currentSpan();
            if (current != null && current.context() != null) {
                span.tag("correlation.trace_id", current.context().traceId());
                span.tag("correlation.parent_span_id", current.context().spanId());
            }
        } catch (Exception e) {
            log.debug("[PaymentMonitoredAspect] Could not attach correlation tags: {}", e.getMessage());
        }
    }

    private void recordError(String metricName, List<Tag> metricTags, Timer timer,
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

    private StandardEvaluationContext buildContext(Method method, Object[] args) {
        return new MethodBasedEvaluationContext(null, method, args, nameDiscoverer);
    }

    private String evaluateSpel(String expression, Method method, Object[] args) {
        try {
            StandardEvaluationContext ctx = buildContext(method, args);
            Object value = spelParser.parseExpression(expression).getValue(ctx);
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            log.debug("[PaymentMonitoredAspect] SpEL evaluation failed for '{}': {}", expression, e.getMessage());
            return null;
        }
    }
}
