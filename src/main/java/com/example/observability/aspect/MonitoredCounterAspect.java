package com.example.observability.aspect;

import com.example.observability.annotation.MonitoredCounter;
import com.example.observability.dedup.DeduplicationCache;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/**
 * Spring AOP aspect that intercepts methods annotated with {@link MonitoredCounter} and
 * increments a Micrometer {@link Counter} by one on each successful invocation.
 *
 * <p>The counter metric name is resolved from {@link MonitoredCounter#metric()}, falling back
 * to {@code <ClassName>.<methodName>} when no explicit name is set.
 *
 * <p>Tags are built from:
 * <ol>
 *   <li>{@link MonitoredCounter#component()} — added as the {@code component} tag</li>
 *   <li>{@link MonitoredCounter#tags()} — static {@code "key=value"} pairs</li>
 * </ol>
 */
@Aspect
@Component
public class MonitoredCounterAspect {

    private static final Logger log = LoggerFactory.getLogger(MonitoredCounterAspect.class);

    private final MeterRegistry registry;
    private final DeduplicationCache deduplicationCache;

    private final ExpressionParser spelParser = new SpelExpressionParser();
    private final ParameterNameDiscoverer nameDiscoverer = new DefaultParameterNameDiscoverer();

    public MonitoredCounterAspect(MeterRegistry registry, DeduplicationCache deduplicationCache) {
        this.registry = registry;
        this.deduplicationCache = deduplicationCache;
    }

    @After("@annotation(monitoredCounter)")
    public void increment(JoinPoint jp, MonitoredCounter monitoredCounter) {
        MethodSignature signature = (MethodSignature) jp.getSignature();
        Method method = signature.getMethod();
        Object[] args = jp.getArgs();

        String metricName = resolveMetricName(monitoredCounter, method);

        // Deduplication: skip count when the same uniqueId is seen within TTL
        if (!monitoredCounter.uniqueId().isBlank()) {
            String resolvedId = evaluateSpel(monitoredCounter.uniqueId(), method, args);
            if (resolvedId != null && !resolvedId.isBlank()) {
                String cacheKey = metricName + ":" + resolvedId;
                if (!deduplicationCache.tryRegister(cacheKey)) {
                    log.debug("[MonitoredCounterAspect] Duplicate invocation for key '{}', skipping count", cacheKey);
                    return;
                }
            }
        }

        Tags tags = buildTags(monitoredCounter);

        Counter.builder(metricName)
                .description("Invocation count for " + metricName)
                .tags(tags)
                .register(registry)
                .increment();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String resolveMetricName(MonitoredCounter annotation, Method method) {
        if (!annotation.metric().isBlank()) {
            return annotation.metric();
        }
        return method.getDeclaringClass().getSimpleName().toLowerCase()
                + "." + method.getName().toLowerCase();
    }

    private Tags buildTags(MonitoredCounter annotation) {
        List<io.micrometer.core.instrument.Tag> tagList = new ArrayList<>();

        if (!annotation.component().isBlank()) {
            tagList.add(io.micrometer.core.instrument.Tag.of("component", annotation.component()));
        }

        for (String entry : annotation.tags()) {
            int idx = entry.indexOf('=');
            if (idx > 0) {
                String key = entry.substring(0, idx).trim();
                String value = entry.substring(idx + 1).trim();
                if (!key.isBlank()) {
                    tagList.add(io.micrometer.core.instrument.Tag.of(key, value.isEmpty() ? "unknown" : value));
                }
            }
        }

        return Tags.of(tagList);
    }

    private String evaluateSpel(String expression, Method method, Object[] args) {
        try {
            StandardEvaluationContext ctx = new MethodBasedEvaluationContext(
                    null, method, args, nameDiscoverer);
            Object value = spelParser.parseExpression(expression).getValue(ctx);
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            log.debug("[MonitoredCounterAspect] SpEL evaluation failed for '{}': {}", expression, e.getMessage());
            return null;
        }
    }
}
