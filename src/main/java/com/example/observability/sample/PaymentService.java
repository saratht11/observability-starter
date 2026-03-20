package com.example.observability.sample;

import com.example.observability.annotation.Monitored;
import org.springframework.stereotype.Service;

/**
 * Sample service demonstrating {@link Monitored} annotation usage patterns.
 *
 * <p>This class is for illustration purposes. Copy these patterns into your own services.
 */
@Service
public class PaymentService {

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

    private void simulateWork(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
