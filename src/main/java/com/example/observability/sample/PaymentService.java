package com.example.observability.sample;

import com.example.observability.annotation.Monitored;
import com.example.observability.annotation.PaymentMonitored;
import com.example.observability.events.StateChangeEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Sample service demonstrating {@link Monitored} and {@link PaymentMonitored} annotation
 * usage patterns.
 *
 * <p>This class is for illustration purposes. Copy these patterns into your own services.
 */
@Service
public class PaymentService {

    private final ApplicationEventPublisher eventPublisher;

    public PaymentService(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * Demonstrates a fully-configured @Monitored method with SLO, active tracking, and span creation.
     */
    @Monitored(
            metric = "payment.processing",
            component = "payments",
            tags = {"operation=charge"},
            dynamicTags = {"channel=#channel"},
            spanTags = {"request_id=#requestId"},
            sloMs = 800,
            percentiles = true,
            trackActive = true,
            createSpan = true,
            spanName = "payment.charge"
    )
    public String processPayment(String channel, String requestId, double amount) {
        // Simulate payment processing
        simulateWork(50);
        return "Payment processed: channel=" + channel + ", amount=" + amount;
    }

    /**
     * Demonstrates error-only recording for high-volume, low-failure-rate methods.
     */
    @Monitored(
            metric = "payment.validation",
            component = "payments",
            tags = {"operation=validate"},
            recordOnlyErrors = true
    )
    public boolean validatePayment(String requestId, double amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Payment amount must be positive");
        }
        return true;
    }

    /**
     * Demonstrates baggage propagation recording.
     */
    @Monitored(
            metric = "payment.refund",
            component = "payments",
            tags = {"operation=refund"},
            dynamicTags = {"channel=#channel"},
            recordBaggage = true,
            baggageFields = {"tenant", "region"},
            sloMs = 1000
    )
    public String processRefund(String channel, String transactionId) {
        simulateWork(30);
        return "Refund processed for transaction: " + transactionId;
    }

    /**
     * Demonstrates {@link PaymentMonitored} with SpEL state extraction and deduplication.
     * The {@code payment} parameter's {@code getState()} method provides a low-cardinality
     * {@code state} metric tag; {@code payment.getId()} is the deduplication key so that
     * retries of the same payment are counted only once.
     */
    @PaymentMonitored(
            metric = "payment.charge",
            component = "payments",
            stateExpression = "#payment.getState()",
            uniqueIdExpression = "#payment.getId()",
            sloMs = 800,
            trackActive = true,
            createSpan = true,
            spanName = "payment.charge.monitored"
    )
    public String chargePayment(PaymentRequest payment) {
        String prevState = payment.getState();
        simulateWork(50);
        payment.setState("PROCESSED");

        // Emit a Spring event so StateEventMetricsListener can record the transition metric
        eventPublisher.publishEvent(StateChangeEvent.builder()
                .source(this)
                .component("payments")
                .entityType("payment")
                .entityId(payment.getId())
                .fromState(prevState)
                .toState(payment.getState())
                .build());

        return "Charged payment: id=" + payment.getId() + ", state=" + payment.getState();
    }

    /**
     * Demonstrates {@link Monitored} with {@code uniqueId} deduplication and a timeout.
     * The {@code transactionId} parameter is the deduplication key; if this method is retried
     * with the same ID within the TTL window, only the first invocation is counted.
     */
    @Monitored(
            metric = "payment.idempotent.charge",
            component = "payments",
            tags = {"operation=charge"},
            uniqueId = "#transactionId",
            timeoutMs = 5000,
            createSpan = true
    )
    public String idempotentCharge(String transactionId, double amount) {
        simulateWork(30);
        return "Charged: txn=" + transactionId + ", amount=" + amount;
    }

    // ── Inner helper class for sample ─────────────────────────────────────────

    /**
     * Simple payment domain object used to demonstrate SpEL state extraction.
     */
    public static class PaymentRequest {
        private final String id;
        private String state;

        public PaymentRequest(String id, String state) {
            this.id = id;
            this.state = state;
        }

        public String getId() {
            return id;
        }

        public String getState() {
            return state;
        }

        public void setState(String state) {
            this.state = state;
        }
    }

    private void simulateWork(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
