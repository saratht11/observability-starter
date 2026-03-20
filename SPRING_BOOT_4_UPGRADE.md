# Spring Boot 4 Upgrade Guide — Observability

This document describes how the observability features introduced in this starter
(`@Monitored`, `@MonitoredCounter`, `@MonitoredGauge`, `ObservabilityContext`) are
expected to evolve when upgrading from **Spring Boot 3.5.x** to **Spring Boot 4**.

---

## 1. Spring Boot 4 Baseline

| Area | Spring Boot 3.5 (current) | Spring Boot 4 (expected) |
|---|---|---|
| Java baseline | Java 17 | Java 25 |
| Micrometer | 1.12.x | 1.14+ |
| Micrometer Tracing | 1.2.x | 1.4+ |
| OTel SDK | 1.34.x | 1.40+ |
| Spring Framework | 6.1.x | 7.x |

---

## 2. `@Monitored` — Migrating to the Observation API

### Current Behaviour (Spring Boot 3.5)

The `MonitoredAspect` manually creates a `Timer`, optionally a `LongTaskTimer`, and
optionally a Micrometer Tracing `Span`.  These three signals are wired together by
hand.

### Spring Boot 4 Direction

Spring Boot 4 is expected to fully endorse the **Micrometer Observation API**
(`ObservationRegistry`) as the single entry-point for all signals.  A single
`Observation` automatically emits:

* A Prometheus timer (metrics)
* An OTel span (traces)
* A structured log via `ObservationHandler`

**Recommended migration in `MonitoredAspect`:**

```java
// Spring Boot 4 — replace Timer + Span with a single Observation
Observation obs = Observation.createNotStarted(metricName, observationRegistry)
        .contextualName(monitored.component() + " " + pjp.getSignature().getName())
        .lowCardinalityKeyValue("component", monitored.component());

for (Tag tag : metricTags) {
    obs.lowCardinalityKeyValue(tag.getKey(), tag.getValue());
}

return obs.observeChecked(() -> pjp.proceed());
```

The `TraceContext` propagation, exemplar injection into Prometheus, and span lifecycle
management all become automatic.

---

## 3. `@MonitoredCounter` — Stable, Minor Naming Shift

The `MonitoredCounterAspect` uses `Counter` directly and will continue to work in
Spring Boot 4 without changes.

However, with the broader push toward OTel **Semantic Conventions** in Spring Boot 4,
consider renaming metric suffixes to align with OTel conventions:

| Current (Micrometer convention) | OTel Semantic Convention |
|---|---|
| `cache.access.total` | `cache.hits` / `cache.misses` |
| `kafka.records.processed` | `messaging.process.messages` |
| `<name>.error.total` | `<name>.errors` |

If you prefer to keep existing names, add an OTel `View` in your SDK configuration to
alias them.

---

## 4. `@MonitoredGauge` — No Breaking Changes Expected

`Gauge` registration via `GaugeRegistrar` on `ContextRefreshedEvent` works the same
in Spring Boot 4.

One optional enhancement: Spring Boot 4 / Micrometer 1.14 plans tighter integration
with virtual-thread-friendly patterns.  If the gauge method performs I/O, consider
making it non-blocking or using a `ScheduledObservation` instead of a pull-based gauge.

---

## 5. `ObservabilityContext` — Span Events via OTel API

### Current Behaviour (Spring Boot 3.5)

`ObservabilityContext.recordEvent` calls `Span#event(String)` on the Micrometer
Tracing `Span` bridge.  This maps to an OTel `Span.addEvent()` under the hood.

### Spring Boot 4 / OTel 1.40 Direction

Spring Boot 4 is expected to expose the raw OTel `io.opentelemetry.api.trace.Span`
more directly via `ObservationContext`.  Developers will be able to attach OTel
`Attributes` (key-value) to span events, enabling richer structured event data.

**Recommended migration:**

```java
// Spring Boot 4 — span events with attributes (OTel 1.40+)
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;

Span otelSpan = Span.current();
otelSpan.addEvent(name, Attributes.builder()
        .put("description", description)
        .put("component", component)
        .build());
```

This replaces the current string-concatenation approach
(`current.event(name + ": " + description)`) with structured, queryable attributes.

---

## 6. OTel Semantic Conventions (Breaking in OTel 1.26+)

OTel stabilised its **Semantic Conventions** schema in version 1.26.  Several
attribute names changed:

| Old attribute | New attribute |
|---|---|
| `messaging.destination` | `messaging.destination.name` |
| `http.url` | `url.full` |
| `http.status_code` | `http.response.status_code` |
| `db.statement` | `db.query.text` |

If you have custom span tags referencing old attribute names in `@Monitored#spanTags`,
update them to the new names.  The OTel Java SDK replaced the deprecated
`SemanticAttributes` class (removed in 1.31) with per-domain attribute classes such as
`HttpAttributes`, `UrlAttributes`, `MessagingAttributes`, and `DbAttributes`.
Use these domain-specific classes when constructing span attributes in Spring Boot 4.

---

## 7. Native OpenTelemetry Java Auto-Instrumentation

Spring Boot 4 is expected to provide a first-class `spring-boot-starter-otel-agent`
that wraps the OTel Java agent.  Key impacts on this starter:

* The OTel agent already instruments JDBC, HTTP clients, Kafka consumers, and Redis
  automatically.  **You do not need** `@MonitoredGauge` or `@Monitored` for these
  when the agent is attached.
* The agent propagates `W3C TraceContext` and `Baggage` headers without custom
  `BaggageReader` code.  The `OtelBaggageReader` in this starter can be simplified or
  removed when the agent is present.
* If both the agent and this starter are active, ensure you disable duplicate
  instrumentation via `otel.instrumentation.<name>.enabled=false` in your OTel agent
  configuration.

---

## 8. Summary Checklist for the Upgrade

- [ ] Bump `spring-boot-starter-parent` to `4.0.x`.
- [ ] Update `micrometer-tracing-bridge-otel` and `opentelemetry-sdk` to versions
      compatible with Spring Boot 4 (check Spring Boot BOM).
- [ ] Migrate `MonitoredAspect` to use `ObservationRegistry` instead of separate
      `Timer` and `Tracer` constructs.
- [ ] Review `@Monitored#spanTags` for deprecated OTel attribute names and update to
      Semantic Conventions 1.26+.
- [ ] Decide whether to adopt the OTel Java agent; disable overlapping instrumentation
      if so.
- [ ] Replace string-based span events in `ObservabilityContext` with structured OTel
      `Attributes` once `io.opentelemetry.api.trace.Span` is directly accessible.
- [ ] Review metric naming to align with OTel Semantic Conventions where applicable.
