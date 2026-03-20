package com.example.observability.gauge;

import com.example.observability.annotation.MonitoredGauge;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Scans all Spring beans at startup and registers Micrometer {@link Gauge}s for every
 * method annotated with {@link MonitoredGauge}.
 *
 * <p>Registration happens on {@link ContextRefreshedEvent} so all beans are fully
 * initialised before scanning begins.  Each discovered method is wrapped in a
 * {@link java.util.function.ToDoubleFunction} that Micrometer calls on each scrape
 * to obtain the current value.
 *
 * <p><b>Supported return types:</b> {@code int}, {@code long}, {@code double},
 * {@code float}, and their boxed equivalents.  Methods returning anything else are
 * skipped with a warning.
 */
@Component
public class GaugeRegistrar {

    private static final Logger log = LoggerFactory.getLogger(GaugeRegistrar.class);

    private final MeterRegistry registry;

    public GaugeRegistrar(MeterRegistry registry) {
        this.registry = registry;
    }

    @EventListener(ContextRefreshedEvent.class)
    public void onContextRefreshed(ContextRefreshedEvent event) {
        ApplicationContext ctx = event.getApplicationContext();
        Map<String, Object> beans = ctx.getBeansOfType(Object.class, false, false);

        for (Map.Entry<String, Object> entry : beans.entrySet()) {
            Object bean = entry.getValue();
            Class<?> beanClass = bean.getClass();

            for (Method method : beanClass.getMethods()) {
                MonitoredGauge annotation = method.getAnnotation(MonitoredGauge.class);
                if (annotation == null) {
                    continue;
                }

                if (!isNumericReturnType(method.getReturnType())) {
                    log.warn("[GaugeRegistrar] Skipping @MonitoredGauge on {}.{}: return type {} is not numeric",
                            beanClass.getSimpleName(), method.getName(), method.getReturnType().getSimpleName());
                    continue;
                }

                String metricName = resolveMetricName(annotation, method);
                Tags tags = buildTags(annotation);

                Gauge.builder(metricName, bean, b -> invokeGaugeMethod(b, method))
                        .description("Gauge for " + metricName)
                        .tags(tags)
                        .register(registry);

                log.debug("[GaugeRegistrar] Registered gauge '{}' on {}.{}",
                        metricName, beanClass.getSimpleName(), method.getName());
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private double invokeGaugeMethod(Object bean, Method method) {
        try {
            if (!method.canAccess(bean)) {
                method.setAccessible(true);
            }
            Object result = method.invoke(bean);
            if (result instanceof Number num) {
                return num.doubleValue();
            }
            return 0d;
        } catch (Exception ex) {
            log.warn("[GaugeRegistrar] Failed to read gauge value from {}.{}: {}",
                    bean.getClass().getSimpleName(), method.getName(), ex.getMessage());
            return 0d;
        }
    }

    private boolean isNumericReturnType(Class<?> type) {
        return type == int.class || type == Integer.class
                || type == long.class || type == Long.class
                || type == double.class || type == Double.class
                || type == float.class || type == Float.class
                || Number.class.isAssignableFrom(type);
    }

    private String resolveMetricName(MonitoredGauge annotation, Method method) {
        if (!annotation.metric().isBlank()) {
            return annotation.metric();
        }
        return method.getDeclaringClass().getSimpleName().toLowerCase()
                + "." + method.getName().toLowerCase();
    }

    private Tags buildTags(MonitoredGauge annotation) {
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
}
