package com.example.observability.metrics;

import com.example.observability.annotation.Monitored;
import io.micrometer.core.instrument.Tag;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves metric and span tags for {@link Monitored}-annotated methods.
 *
 * <p>Supports three tag sources:
 * <ol>
 *   <li><b>Static tags</b> — literal {@code "key=value"} pairs from {@link Monitored#tags()}</li>
 *   <li><b>Dynamic tags</b> — SpEL-evaluated low-cardinality tags from {@link Monitored#dynamicTags()}
 *       (added to both metrics and spans)</li>
 *   <li><b>Span tags</b> — SpEL-evaluated high-cardinality tags from {@link Monitored#spanTags()}
 *       (added to tracing spans only, never to metrics)</li>
 * </ol>
 */
@Component
public class MonitoredTagResolver {

    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer nameDiscoverer = new DefaultParameterNameDiscoverer();
    private final ConcurrentHashMap<String, Expression> expressionCache = new ConcurrentHashMap<>();

    /**
     * Builds the list of Micrometer {@link Tag}s for metric recording.
     * Includes static tags and dynamic (low-cardinality) tags.
     * Does <em>not</em> include span-only tags.
     *
     * @param annotation the {@link Monitored} annotation instance
     * @param method     the intercepted method
     * @param args       the method arguments
     * @return list of resolved metric tags
     */
    public List<Tag> resolveMetricTags(Monitored annotation, Method method, Object[] args) {
        List<Tag> tags = new ArrayList<>();

        if (!annotation.component().isBlank()) {
            tags.add(Tag.of("component", annotation.component()));
        }

        for (String staticTag : annotation.tags()) {
            parseKeyValue(staticTag, tags);
        }

        StandardEvaluationContext context = buildContext(method, args);
        for (String dynamicTag : annotation.dynamicTags()) {
            evaluateTag(dynamicTag, context, tags);
        }

        return tags;
    }

    /**
     * Builds the list of tags to attach to tracing spans.
     * Includes static tags, dynamic tags, and span-specific (high-cardinality) tags.
     *
     * @param annotation the {@link Monitored} annotation instance
     * @param method     the intercepted method
     * @param args       the method arguments
     * @return list of resolved span tags
     */
    public List<Tag> resolveSpanTags(Monitored annotation, Method method, Object[] args) {
        List<Tag> tags = new ArrayList<>(resolveMetricTags(annotation, method, args));

        StandardEvaluationContext context = buildContext(method, args);
        for (String spanTag : annotation.spanTags()) {
            evaluateTag(spanTag, context, tags);
        }

        return tags;
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private StandardEvaluationContext buildContext(Method method, Object[] args) {
        StandardEvaluationContext context = new MethodBasedEvaluationContext(
                null, method, args, nameDiscoverer);
        return context;
    }

    private void parseKeyValue(String expression, List<Tag> tags) {
        int idx = expression.indexOf('=');
        if (idx <= 0) {
            return;
        }
        String key = expression.substring(0, idx).trim();
        String value = expression.substring(idx + 1).trim();
        if (!key.isBlank()) {
            tags.add(Tag.of(key, value.isEmpty() ? "unknown" : value));
        }
    }

    private void evaluateTag(String tagExpression, StandardEvaluationContext context, List<Tag> tags) {
        int idx = tagExpression.indexOf('=');
        if (idx <= 0) {
            return;
        }
        String key = tagExpression.substring(0, idx).trim();
        String spelExpr = tagExpression.substring(idx + 1).trim();
        if (key.isBlank() || spelExpr.isBlank()) {
            return;
        }
        try {
            Expression expression = expressionCache.computeIfAbsent(spelExpr, parser::parseExpression);
            Object value = expression.getValue(context);
            String tagValue = value != null ? value.toString() : "unknown";
            tags.add(Tag.of(key, tagValue.isBlank() ? "unknown" : tagValue));
        } catch (Exception e) {
            // Fail-safe: if SpEL evaluation fails, use a sentinel value rather than crashing.
            tags.add(Tag.of(key, "spel_error"));
        }
    }
}
