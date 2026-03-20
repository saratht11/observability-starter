package com.example.observability;

import com.example.observability.annotation.MonitoredGauge;
import com.example.observability.gauge.GaugeRegistrar;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.support.GenericApplicationContext;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GaugeRegistrar}.
 */
class GaugeRegistrarTest {

    private MeterRegistry registry;
    private GaugeRegistrar registrar;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        registrar = new GaugeRegistrar(registry);
    }

    @Test
    void gauge_isRegisteredAndReflectsCurrentValue() {
        QueueBean bean = new QueueBean();
        bean.add(); // size = 1

        simulateContextRefresh(bean);

        assertThat(registry.find("orders.queue.size").gauge()).isNotNull();
        assertThat(registry.find("orders.queue.size").gauge().value()).isEqualTo(1.0);

        bean.add(); // size = 2
        assertThat(registry.find("orders.queue.size").gauge().value()).isEqualTo(2.0);
    }

    @Test
    void gauge_componentTag_isPresent() {
        QueueBean bean = new QueueBean();
        simulateContextRefresh(bean);

        assertThat(registry.find("orders.queue.size")
                .tag("component", "order-processor")
                .gauge()).isNotNull();
    }

    @Test
    void gauge_staticTags_arePresent() {
        QueueBean bean = new QueueBean();
        simulateContextRefresh(bean);

        assertThat(registry.find("orders.queue.size")
                .tag("region", "us-east")
                .gauge()).isNotNull();
    }

    @Test
    void gauge_fallbackMetricName_usesClassAndMethod() {
        NoMetricBean bean = new NoMetricBean();
        simulateContextRefresh(bean);

        assertThat(registry.find("nometricbean.getvalue").gauge()).isNotNull();
    }

    @Test
    void nonNumericMethod_isSkippedWithoutError() {
        StringReturnBean bean = new StringReturnBean();
        // Should not throw; String return type is skipped
        simulateContextRefresh(bean);
        assertThat(registry.find("string.metric").gauge()).isNull();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void simulateContextRefresh(Object... beans) {
        GenericApplicationContext ctx = new GenericApplicationContext();
        for (Object bean : beans) {
            String beanName = bean.getClass().getSimpleName().toLowerCase();
            ctx.getBeanFactory().registerSingleton(beanName, bean);
        }
        ctx.refresh();
        registrar.onContextRefreshed(new ContextRefreshedEvent(ctx));
        ctx.close();
    }

    // ── Sample beans ─────────────────────────────────────────────────────────

    static class QueueBean {
        private final AtomicInteger size = new AtomicInteger(0);

        public void add() { size.incrementAndGet(); }

        @MonitoredGauge(
                metric = "orders.queue.size",
                component = "order-processor",
                tags = {"region=us-east"}
        )
        public int getSize() {
            return size.get();
        }
    }

    static class NoMetricBean {
        @MonitoredGauge(component = "test")
        public long getValue() {
            return 42L;
        }
    }

    static class StringReturnBean {
        @MonitoredGauge(metric = "string.metric", component = "test")
        public String notANumber() {
            return "hello";
        }
    }
}
