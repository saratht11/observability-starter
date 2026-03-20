package com.example.observability;

import com.example.observability.annotation.Monitored;
import com.example.observability.metrics.MonitoredTagResolver;
import io.micrometer.core.instrument.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MonitoredTagResolver}.
 */
class MonitoredTagResolverTest {

    private MonitoredTagResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new MonitoredTagResolver();
    }

    @Test
    void staticTags_areResolvedCorrectly() throws Exception {
        Method method = TagSamples.class.getMethod("withStaticTags", String.class);
        Monitored annotation = method.getAnnotation(Monitored.class);
        List<Tag> tags = resolver.resolveMetricTags(annotation, method, new Object[]{"web"});

        assertThat(tags).contains(Tag.of("operation", "charge"));
        assertThat(tags).contains(Tag.of("region", "eu"));
    }

    @Test
    void component_isIncludedAsTag() throws Exception {
        Method method = TagSamples.class.getMethod("withStaticTags", String.class);
        Monitored annotation = method.getAnnotation(Monitored.class);
        List<Tag> tags = resolver.resolveMetricTags(annotation, method, new Object[]{"web"});

        assertThat(tags).contains(Tag.of("component", "payments"));
    }

    @Test
    void dynamicTags_areResolvedViaSpel() throws Exception {
        Method method = TagSamples.class.getMethod("withDynamicTags", String.class);
        Monitored annotation = method.getAnnotation(Monitored.class);
        List<Tag> tags = resolver.resolveMetricTags(annotation, method, new Object[]{"mobile"});

        assertThat(tags).contains(Tag.of("channel", "mobile"));
    }

    @Test
    void dynamicTags_spelError_useSentinelValue() throws Exception {
        Method method = TagSamples.class.getMethod("withBadSpelTag", String.class);
        Monitored annotation = method.getAnnotation(Monitored.class);
        List<Tag> tags = resolver.resolveMetricTags(annotation, method, new Object[]{"mobile"});

        assertThat(tags).anyMatch(t -> t.getKey().equals("bad") && t.getValue().equals("spel_error"));
    }

    @Test
    void spanTags_notIncludedInMetricTags() throws Exception {
        Method method = TagSamples.class.getMethod("withSpanTags", String.class, String.class);
        Monitored annotation = method.getAnnotation(Monitored.class);
        List<Tag> metricTags = resolver.resolveMetricTags(annotation, method, new Object[]{"web", "req-123"});

        assertThat(metricTags).noneMatch(t -> t.getKey().equals("request_id"));
    }

    @Test
    void spanTags_includedInSpanTags() throws Exception {
        Method method = TagSamples.class.getMethod("withSpanTags", String.class, String.class);
        Monitored annotation = method.getAnnotation(Monitored.class);
        List<Tag> spanTags = resolver.resolveSpanTags(annotation, method, new Object[]{"web", "req-123"});

        assertThat(spanTags).contains(Tag.of("request_id", "req-123"));
    }

    // ── Sample methods with annotations ──────────────────────────────────────

    static class TagSamples {

        @Monitored(
                metric = "payment.processing",
                component = "payments",
                tags = {"operation=charge", "region=eu"}
        )
        public void withStaticTags(String channel) {}

        @Monitored(
                metric = "payment.processing",
                component = "payments",
                dynamicTags = {"channel=#channel"}
        )
        public void withDynamicTags(String channel) {}

        @Monitored(
                metric = "payment.processing",
                component = "payments",
                dynamicTags = {"bad=#nonExistentVar.nope()"}
        )
        public void withBadSpelTag(String channel) {}

        @Monitored(
                metric = "payment.processing",
                component = "payments",
                dynamicTags = {"channel=#channel"},
                spanTags = {"request_id=#requestId"}
        )
        public void withSpanTags(String channel, String requestId) {}
    }
}
