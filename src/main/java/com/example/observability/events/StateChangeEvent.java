package com.example.observability.events;

import org.springframework.context.ApplicationEvent;

/**
 * Spring {@link ApplicationEvent} emitted when a domain entity transitions between states.
 *
 * <p>Publish this event via Spring's {@link org.springframework.context.ApplicationEventPublisher}
 * whenever a meaningful state change occurs (e.g. a payment moving from {@code PENDING} to
 * {@code PROCESSED}).  The {@link StateEventMetricsListener} will automatically emit a
 * Micrometer {@code Counter} and record a span event on the active OTel span.
 *
 * <h3>Publishing example</h3>
 * <pre>{@code
 * eventPublisher.publishEvent(StateChangeEvent.builder()
 *     .source(this)
 *     .component("payments")
 *     .entityType("payment")
 *     .entityId(payment.getId())
 *     .fromState(previousState)
 *     .toState(payment.getState())
 *     .build());
 * }</pre>
 */
public class StateChangeEvent extends ApplicationEvent {

    private final String component;
    private final String entityType;
    private final String entityId;
    private final String fromState;
    private final String toState;

    private StateChangeEvent(Builder builder) {
        super(builder.source);
        this.component = builder.component;
        this.entityType = builder.entityType;
        this.entityId = builder.entityId;
        this.fromState = builder.fromState;
        this.toState = builder.toState;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String getComponent() {
        return component;
    }

    public String getEntityType() {
        return entityType;
    }

    /** High-cardinality — used for span events / logs only, never as a metric tag. */
    public String getEntityId() {
        return entityId;
    }

    public String getFromState() {
        return fromState;
    }

    public String getToState() {
        return toState;
    }

    @Override
    public String toString() {
        return "StateChangeEvent{component='" + component
                + "', entityType='" + entityType
                + "', entityId='" + entityId
                + "', " + fromState + " -> " + toState + '}';
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Object source;
        private String component = "";
        private String entityType = "";
        private String entityId = "";
        private String fromState = "UNKNOWN";
        private String toState = "UNKNOWN";

        public Builder source(Object source) {
            this.source = source;
            return this;
        }

        public Builder component(String component) {
            this.component = component;
            return this;
        }

        public Builder entityType(String entityType) {
            this.entityType = entityType;
            return this;
        }

        public Builder entityId(String entityId) {
            this.entityId = entityId;
            return this;
        }

        public Builder fromState(String fromState) {
            this.fromState = fromState;
            return this;
        }

        public Builder toState(String toState) {
            this.toState = toState;
            return this;
        }

        public StateChangeEvent build() {
            if (source == null) {
                throw new IllegalStateException("StateChangeEvent.Builder: source must not be null");
            }
            return new StateChangeEvent(this);
        }
    }
}
