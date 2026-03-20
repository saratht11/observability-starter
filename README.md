# observability-starter

A production-minded Spring Boot observability starter centered around a custom `@Monitored` annotation.

This project helps teams add consistent, low-friction method-level observability for:
- latency
- active in-flight operations
- error tracking
- low-cardinality metric tags
- optional tracing/span enrichment

It is designed for organizations adopting observability for the first time and wanting a safe, scalable pattern.

---

## Why this project exists

Most teams either:
1. add no instrumentation, or
2. add ad-hoc metrics with inconsistent names/tags.

Both lead to poor traceability and hard-to-maintain alerts/dashboard queries.

`observability-starter` standardizes instrumentation through:
- a single annotation contract (`@Monitored`)
- Spring AOP interception
- Micrometer metric emission
- explicit low vs high cardinality separation

---

## Core idea

Annotate important methods with `@Monitored` and let the aspect automatically:
- resolve metric name
- resolve static/dynamic tags
- record latency (`*.latency`)
- optionally track active work (`*.latency.active`)
- record errors (`*.error.total`)
- optionally enrich spans

---

## Architecture (high level)

1. `@Monitored` on a method
2. `MonitoredAspect` intercepts invocation (`@Around`)
3. `MonitoredTagResolver` builds tags from static + SpEL expressions
4. `LatencyRecorder` writes metrics to `MeterRegistry`
5. Optional baggage fields are read via `BaggageReader`
6. Optional span tags are attached to tracing spans only

---

## Annotation contract

Typical fields used in this project:

- `metric`: base metric name
- `component`: subsystem/service tag
- `tags`: static key=value tags
- `dynamicTags`: low-cardinality runtime tags (metrics + span)
- `spanTags`: high-cardinality tags (span only)
- `sloMs`: SLO latency threshold in milliseconds
- `percentiles`: publish p50/p90/p99
- `recordOnlyErrors`: skip success latency if true
- `trackActive`: enable long task timer
- `createSpan`: create a child span around execution
- `recordBaggage`: include configured baggage fields as metric tags (use carefully)

---

## Quick usage

```java
@Monitored(
    metric = "payment.processing",
    component = "payments",
    tags = {"operation=charge"},
    dynamicTags = {"channel=#channel"},
    sloMs = 800,
    percentiles = true,
    trackActive = true
)
public PaymentResult processPayment(String channel, PaymentRequest request) {
    // business logic
}
```

---

## Metrics emitted

For `metric = payment.processing`, starter emits:

- `payment.processing.latency`
- `payment.processing.latency.active` (if `trackActive=true`)
- `payment.processing.error.total`

Recommended standard tags:
- `component`
- `outcome` (`success` / `error`)
- `slo_breach` (`true` / `false`)
- controlled business dimensions (low cardinality only)

---

## Do’s (required practices)

1. **Use stable metric names**
   - Keep metric names predictable and long-lived.
   - Prefer domain + action naming (`payment.processing`, `order.validation`).

2. **Keep metric tags low cardinality**
   - Good: `component`, `channel`, `region`, `outcome`
   - Bad: `userId`, `orderId`, `transactionId`, `requestId`

3. **Use `spanTags` for high-cardinality values**
   - Put IDs and unique request metadata in tracing spans, not metrics.

4. **Annotate service boundaries and critical paths**
   - External calls, core domain operations, bottlenecks.

5. **Set SLOs intentionally**
   - Use realistic `sloMs` based on service objective, not guesswork.

6. **Treat annotation as contract**
   - Teams should not invent random tag keys per method.

7. **Review cardinality before merge**
   - Any new tag key/value dimension should be reviewed.

---

## Don’ts (anti-patterns)

1. **Do not put unbounded values into metric tags**
   - Never use IDs, UUIDs, raw error messages, timestamps, payload fields.

2. **Do not create per-method custom naming styles**
   - Inconsistent naming breaks discoverability and reuse.

3. **Do not enable `createSpan=true` everywhere blindly**
   - Adds tracing overhead; use on important boundaries.

4. **Do not tag PII/secrets**
   - No emails, tokens, account numbers, auth data in metrics/spans.

5. **Do not over-tag**
   - More tags ≠ better observability.
   - Keep only dimensions that answer operational questions.

6. **Do not bypass framework conventions**
   - Avoid ad-hoc metric emissions with different names/tags for same behavior.

---

## How not to abuse `@Monitored`

### 1) Cardinality abuse

**Bad**
```java
@Monitored(dynamicTags = {"user_id=#userId", "request_id=#requestId"})
```

**Why bad**
- Explodes time series cardinality in Prometheus.
- Increases memory usage and query cost.
- Makes dashboards/alerts unreliable.

**Good**
```java
@Monitored(dynamicTags = {"channel=#channel", "partner_tier=#partnerTier"},
           spanTags = {"request_id=#requestId", "user_id=#userId"})
```

### 2) Annotation sprawl

**Bad**
- Annotating every trivial private/helper method.

**Good**
- Annotate meaningful business operations and integration boundaries.

### 3) Noisy outcome dimensions

**Bad**
- Encoding many custom outcomes per method.

**Good**
- Standardize on compact operational outcomes (`success`, `error`).

### 4) Misusing baggage as free tags

**Bad**
- Turning on `recordBaggage=true` by default for all methods.

**Good**
- Enable baggage only where correlation is truly needed and fields are bounded.

---

## Tag policy recommendation

Maintain a central allowlist for metric tags (for example):
- `component`
- `outcome`
- `slo_breach`
- `channel`
- `region`
- `partner_name` (only if bounded)

Everything else should be rejected or moved to `spanTags`.

---

## Suggested defaults

- `percentiles = true`
- `trackActive = false` by default, enable for long-running operations
- `createSpan = false` by default, opt-in on important boundaries
- `recordOnlyErrors = false`
- global `defaultSloMs` set via config

---

## Minimal configuration example

```yaml
app:
  observability:
    enabled: true
    default-slo-ms: 500
    percentiles-default: true

management:
  metrics:
    tags:
      application: observability-starter
```

---

## Team adoption guidance

- Start with 2–3 critical flows.
- Validate cardinality before broad rollout.
- Publish naming/tag conventions in architecture docs.
- Enforce rules in code review.
- Expand gradually across services.

---

## Repository assets

- Starter implementation (annotation, aspect, recorder, tag resolver)
- Reference docs (`monitored-annotation-reference.pdf`)
- Architecture diagram (`custom_monitored_annotation_architecture.svg`)

---

## Final principle

Observability quality is not about “more metrics.”
It is about **consistent semantics, bounded cardinality, and operationally useful signals**.

Use `@Monitored` as a disciplined contract, not as an unrestricted logging substitute.
