# Latency Measurement in Spring Boot — A Side-by-Side Comparison

This document is a presentation guide for engineering teams covering the main ways to capture
method latency in Spring Boot, finishing with a deep dive into the `@Monitored` annotation
that lives in this repository.

---

## 1. Why Latency Measurement Matters

Latency is the most actionable signal in any service:

- It tells you whether your SLO is being met.
- It points to regressions before users notice.
- Combined with tags, it tells you *which* code path, *which* partner, or *which* channel is slow.

Choosing the wrong measurement technique leads to either **too much noise** (cardinality explosions,
scattered metric names) or **too little signal** (no percentiles, no SLO breach flag, no error counts).

---

## 2. Approaches at a Glance

| # | Approach | Granularity | Effort | Consistency | Tracing Support | SLO Awareness |
|---|----------|-------------|--------|-------------|-----------------|---------------|
| A | `System.nanoTime()` manual | Method | High | ❌ None | ❌ Manual | ❌ None |
| B | Micrometer `Timer` (manual) | Method | Medium | ⚠️ Per-dev | ❌ Manual | ⚠️ Manual |
| C | Spring Boot Actuator auto-metrics | HTTP layer | Zero | ✅ Built-in | ⚠️ Partial | ❌ None |
| D | Micrometer `@Timed` | Method | Low | ⚠️ Per-dev | ❌ None | ❌ None |
| E | `ObservationRegistry` / `Observation` API | Method | Medium | ⚠️ Per-dev | ✅ Built-in | ⚠️ Manual |
| F | **`@Monitored` (this repo)** | Method | **Minimal** | **✅ Enforced** | **✅ Built-in** | **✅ Built-in** |

---

## 3. Approach A — `System.nanoTime()` (Raw Manual)

### How it works

```java
long start = System.nanoTime();
try {
    result = doWork();
} finally {
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    log.info("doWork took {} ms", elapsedMs);
}
```

### Pros
- No external dependencies.
- Works anywhere in any Java program.

### Cons
- Latency lives in logs only — not queryable as a metric.
- No percentiles, no histograms, no Prometheus/Grafana integration.
- Every developer writes it differently (variable names, units, log format).
- No error counter, no SLO threshold, no cardinality policy.
- Requires manual changes in every method that needs measurement.

### When to use
Debugging / one-off profiling only. **Never in production observability.**

---

## 4. Approach B — Micrometer `Timer` (Manual)

### How it works

```java
private final MeterRegistry registry;

public PaymentResult processPayment(PaymentRequest req) {
    Timer timer = Timer.builder("payment.processing.latency")
        .tag("component", "payments")
        .register(registry);

    return timer.record(() -> doPayment(req));
}
```

### Pros
- Emits real Micrometer metrics (Prometheus, Datadog, etc.).
- Developer controls naming and tags.

### Cons
- Every method requires boilerplate wiring and `MeterRegistry` injection.
- Names and tags diverge across the codebase without strict conventions.
- No automatic error counter — you add it yourself or forget.
- No span integration, no SLO flag.
- Teams inevitably produce inconsistent metric names and tag keys.

### When to use
Suitable when you need a single one-off custom metric with no broader convention.

---

## 5. Approach C — Spring Boot Actuator Auto-Metrics

### How it works

Spring Boot auto-configures Micrometer timers for HTTP requests, datasource pools, JVM, etc.

```yaml
# application.yml
management:
  metrics:
    tags:
      application: my-service
```

No code changes needed for HTTP layer metrics.

### Metrics emitted automatically
- `http.server.requests` — all inbound HTTP calls
- `spring.data.repository.invocations` — Spring Data repository calls
- `executor.*` — thread pool metrics
- JVM, GC, memory, CPU metrics

### Pros
- Zero code for HTTP + infrastructure-level latency.
- Works out of the box with Spring Boot Actuator.

### Cons
- **No method-level granularity** — you cannot distinguish which internal service method is slow.
- HTTP tags are fixed (`uri`, `method`, `status`) — cannot add domain-level tags.
- No error counters for domain failures (only HTTP 5xx).
- No SLO awareness, no tracing integration.

### When to use
Always include as a baseline, but it is **not a substitute** for method-level instrumentation.

---

## 6. Approach D — Micrometer `@Timed`

### How it works

```java
import io.micrometer.core.annotation.Timed;

@Timed(value = "payment.processing", extraTags = {"component", "payments"})
public PaymentResult processPayment(PaymentRequest req) {
    return doPayment(req);
}
```

Requires the `TimedAspect` bean to be registered:

```java
@Bean
public TimedAspect timedAspect(MeterRegistry registry) {
    return new TimedAspect(registry);
}
```

### Pros
- Annotation-driven — minimal boilerplate.
- Ships with Micrometer (no extra dependency).

### Cons
- **Static tags only** — no SpEL-evaluated dynamic tags.
- No automatic error counter.
- No SLO threshold / `slo_breach` tag.
- No tracing span integration.
- No in-flight (active) invocation tracking.
- No baggage propagation support.
- Easy to produce inconsistent metric names across a team.

### When to use
Good for simple personal projects or proof-of-concept work. Insufficient for production-grade
observability at scale.

---

## 7. Approach E — `ObservationRegistry` / `Observation` API (Micrometer 1.10+)

### How it works

```java
private final ObservationRegistry observationRegistry;

public PaymentResult processPayment(PaymentRequest req) {
    return Observation.createNotStarted("payment.processing", observationRegistry)
        .lowCardinalityKeyValue("component", "payments")
        .observe(() -> doPayment(req));
}
```

### Pros
- Unified API producing both metrics **and** traces from a single call.
- Supported by Spring Boot 3+ auto-configuration.
- High-cardinality context (span tags) cleanly separated from low-cardinality (metric tags).

### Cons
- Verbose — every method needs `Observation.createNotStarted(...)` boilerplate.
- No automatic error counter (you must handle exceptions manually).
- No `slo_breach` flag or SLO bucket configuration.
- No baggage-to-tag propagation.
- Convention enforcement still depends on developer discipline.

### When to use
Excellent for targeted instrumentation in new greenfield code where you want both metrics and
traces without a full framework. Still requires per-method boilerplate.

---

## 8. Approach F — `@Monitored` (This Repository) ✅

### How it works

Annotate any Spring-managed bean method:

```java
@Monitored(
    metric      = "payment.processing",
    component   = "payments",
    tags        = {"operation=charge"},
    dynamicTags = {"channel=#channel", "partner_tier=#partnerTier"},
    spanTags    = {"user_id=#userId", "request_id=#requestId"},
    sloMs       = 800,
    percentiles = true,
    trackActive = true,
    createSpan  = true
)
public PaymentResult processPayment(String channel, String partnerTier,
                                    String userId, String requestId,
                                    PaymentRequest req) {
    return doPayment(req);
}
```

`MonitoredAspect` (Spring AOP `@Around`) intercepts the call and automatically:

1. Resolves the metric name (explicit `metric` field or `ClassName.methodName` fallback).
2. Builds low-cardinality tags (static `tags` + SpEL `dynamicTags`).
3. Optionally reads OpenTelemetry baggage fields as extra metric tags.
4. Optionally starts a `LongTaskTimer` to track in-flight invocations.
5. Optionally starts a child tracing span with high-cardinality `spanTags`.
6. Executes the method.
7. On success: records latency timer with `outcome=success` and `slo_breach=true/false`.
8. On failure: records latency timer with `outcome=error` **and** increments the error counter.
9. Stops the active timer and ends the span in `finally`.

### Metrics emitted (for `metric = "payment.processing"`)

| Metric | Type | When |
|--------|------|------|
| `payment.processing.latency` | `Timer` | Every call |
| `payment.processing.latency.active` | `LongTaskTimer` | When `trackActive=true` |
| `payment.processing.error.total` | `Counter` | On any exception |

### All annotation fields

| Field | Type | Default | Purpose |
|-------|------|---------|---------|
| `metric` | `String` | `""` (class.method) | Base metric name |
| `component` | `String` | `""` | `component` tag value |
| `tags` | `String[]` | `{}` | Static `key=value` tags |
| `dynamicTags` | `String[]` | `{}` | SpEL-evaluated low-cardinality tags (metrics + span) |
| `spanTags` | `String[]` | `{}` | SpEL-evaluated high-cardinality tags (span only) |
| `sloMs` | `long` | `0` | SLO threshold in ms; adds `slo_breach` tag |
| `percentiles` | `boolean` | `false` | Publish p50/p90/p99 |
| `recordOnlyErrors` | `boolean` | `false` | Skip success latency on high-volume methods |
| `trackActive` | `boolean` | `false` | Enable `LongTaskTimer` |
| `createSpan` | `boolean` | `false` | Create child tracing span |
| `spanName` | `String` | `""` (uses `metric`) | Custom span name |
| `addSpanTags` | `boolean` | `true` | Enrich span with `spanTags` |
| `recordBaggage` | `boolean` | `false` | Read OTel baggage fields as metric tags |
| `baggageFields` | `String[]` | `{}` | Per-method baggage field override |

---

## 9. Side-by-Side Comparison: `@Timed` vs `@Monitored`

| Capability | `@Timed` (Micrometer built-in) | `@Monitored` (this repo) |
|------------|-------------------------------|--------------------------|
| Latency timer | ✅ | ✅ |
| Static tags | ✅ (extra tags) | ✅ |
| Dynamic (SpEL) tags | ❌ | ✅ |
| High-cardinality span-only tags | ❌ | ✅ |
| Automatic error counter | ❌ | ✅ |
| `outcome` tag (`success`/`error`) | ❌ | ✅ |
| SLO threshold + `slo_breach` tag | ❌ | ✅ |
| Percentiles (p50/p90/p99) | ⚠️ Limited | ✅ |
| In-flight tracking (`LongTaskTimer`) | ❌ | ✅ |
| Tracing span creation | ❌ | ✅ |
| OTel baggage → metric tag | ❌ | ✅ |
| Team-wide naming convention | ❌ Manual | ✅ Enforced by aspect |
| Cardinality policy (low vs high) | ❌ | ✅ |

---

## 10. Full Side-by-Side Code Comparison

### Measuring payment processing latency the "naive" way

```java
// ❌ Manual approach — repeated in every method, no consistency
public PaymentResult processPayment(PaymentRequest req) {
    long start = System.nanoTime();
    boolean success = false;
    try {
        PaymentResult result = doPayment(req);
        success = true;
        return result;
    } catch (Exception ex) {
        meterRegistry.counter("payment.errors").increment(); // easy to forget
        throw ex;
    } finally {
        long elapsed = System.nanoTime() - start;
        meterRegistry.timer("payment.latency",
            "outcome", success ? "success" : "error"
        ).record(elapsed, TimeUnit.NANOSECONDS);
    }
}
```

### The same method with `@Timed`

```java
// ⚠️ @Timed — simple but limited
@Timed(value = "payment.latency", extraTags = {"component", "payments"})
public PaymentResult processPayment(PaymentRequest req) {
    return doPayment(req);  // no error counter, no slo_breach, no span, no dynamic tags
}
```

### The same method with `@Monitored`

```java
// ✅ @Monitored — full observability with one annotation
@Monitored(
    metric      = "payment.processing",
    component   = "payments",
    tags        = {"operation=charge"},
    dynamicTags = {"channel=#channel"},
    spanTags    = {"user_id=#userId"},
    sloMs       = 800,
    percentiles = true,
    createSpan  = true
)
public PaymentResult processPayment(String channel, String userId, PaymentRequest req) {
    return doPayment(req);
    // Automatically emits:
    //   payment.processing.latency{component="payments", operation="charge",
    //                              channel="...", outcome="success", slo_breach="false"}
    //   payment.processing.error.total  (on failure)
    //   span with user_id tag (high cardinality — span only, never in metrics)
}
```

---

## 11. Cardinality: The Most Important Concept

> **Cardinality** = the number of unique time series in your metrics store.
> High cardinality = slow queries, high memory, unusable dashboards.

### `@Monitored` enforces the separation:

```
metric tags  (low cardinality)  → stored in Prometheus / Datadog
span tags    (high cardinality) → stored in tracing backend (Tempo / Jaeger)
```

```java
@Monitored(
    dynamicTags = {"channel=#channel"},          // ✅ ~5 unique values
    spanTags    = {"user_id=#userId",             // ✅ high-cardinality → span only
                   "request_id=#requestId"}
)
```

---

## 12. SLO Awareness

`@Monitored` bakes SLO awareness directly into the metric:

```java
@Monitored(metric = "payment.processing", sloMs = 800)
```

This:
1. Configures a histogram bucket at 800 ms (used in `histogram_quantile` queries).
2. Adds `slo_breach=true/false` to every recorded data point.

PromQL example:

```promql
# Percentage of payments within SLO
sum(rate(payment_processing_latency_bucket{le="0.8"}[5m]))
  / sum(rate(payment_processing_latency_count[5m]))
```

---

## 13. Decision Guide

```
Need method-level latency?
  ├─ Simple one-off, no team conventions needed → @Timed or manual Timer
  ├─ Need dynamic tags or SLO tracking → @Monitored
  ├─ Need tracing spans AND metrics in one call → @Monitored or Observation API
  └─ HTTP / JVM / DB pool metrics only → Spring Boot Actuator auto-metrics
```

---

## 14. Summary

| Approach | Best for |
|----------|----------|
| `System.nanoTime()` | Local debugging only |
| Manual `Timer` | One-off custom metric |
| Actuator auto-metrics | HTTP + infrastructure baseline |
| `@Timed` | Simple, single-team, no conventions needed |
| `Observation` API | New greenfield code, metrics + traces together |
| **`@Monitored`** | **Team-wide, production-grade, opinionated observability** |

`@Monitored` is the right choice when you want:

- A single annotation that covers latency, errors, SLO, tracing, and cardinality policy.
- Consistent metric names and tag keys enforced across the entire team.
- Zero boilerplate per method — the aspect handles everything.
