package com.example.observability.aspect;

import com.example.observability.annotation.Monitored;
import com.example.observability.baggage.BaggageReader;
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
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
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

    @Value("${observability.baggage.fields:}")
    private List<String> globalBaggageFields;

    @Value("${observability.metrics.percentiles:false}")
    private boolean globalPercentiles;

    public MonitoredAspect(MonitoredTagResolver tagResolver,
                           LatencyRecorder latencyRecorder,
                           BaggageReader baggageReader,
                           Tracer tracer) {
        this.tagResolver = tagResolver;
        this.latencyRecorder = latencyRecorder;
        this.baggageReader = baggageReader;
        this.tracer = tracer;
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

        boolean recordOnlyErrors = monitored.recordOnlyErrors();
        boolean usePercentiles = monitored.percentiles() || globalPercentiles;

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
        }

        long startNanos = System.nanoTime();
        try {
            Object result = pjp.proceed();
            long elapsedNanos = System.nanoTime() - startNanos;

            if (!recordOnlyErrors) {
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
                boolean sloBreach = monitored.sloMs() > 0 && elapsedMs > monitored.sloMs();

                List<Tag> successTags = new ArrayList<>(metricTags);
                successTags.add(Tag.of("outcome", "success"));
                successTags.add(Tag.of("slo_breach", String.valueOf(sloBreach)));

                Timer successTimer = latencyRecorder.buildTimer(
                        metricName, successTags, monitored.sloMs(), usePercentiles);
                successTimer.record(elapsedNanos, TimeUnit.NANOSECONDS);
            }

            if (span != null) {
                span.tag("outcome", "success");
            }
            return result;

        } catch (Throwable ex) {
            long elapsedNanos = System.nanoTime() - startNanos;

            List<Tag> errorTags = new ArrayList<>(metricTags);
            errorTags.add(Tag.of("outcome", "error"));

            Timer errorTimer = latencyRecorder.buildTimer(
                    metricName, errorTags, monitored.sloMs(), usePercentiles);
            latencyRecorder.recordError(metricName, errorTags, errorTimer, elapsedNanos);

            if (span != null) {
                span.tag("outcome", "error");
                span.tag("error.class", ex.getClass().getSimpleName());
                span.error(ex);
            }
            throw ex;

        } finally {
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
}
