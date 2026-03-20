package com.example.observability.exception;

/**
 * Unchecked exception thrown when a {@code @Monitored} or {@code @PaymentMonitored} method
 * exceeds its configured {@code timeoutMs} threshold.
 *
 * <p>Using an unchecked exception avoids requiring callers to handle a checked
 * {@link java.util.concurrent.TimeoutException}, while still providing a clear,
 * named exception type that can be caught explicitly if desired.
 *
 * <h3>Example</h3>
 * <pre>{@code
 * try {
 *     paymentService.processPayment(...);
 * } catch (MonitoredTimeoutException ex) {
 *     log.error("Payment timed out after {}ms: {}", ex.getTimeoutMs(), ex.getMessage());
 *     // handle timeout — e.g. return a fallback response
 * }
 * }</pre>
 */
public class MonitoredTimeoutException extends RuntimeException {

    private final long timeoutMs;

    public MonitoredTimeoutException(String message, long timeoutMs) {
        super(message);
        this.timeoutMs = timeoutMs;
    }

    /** Returns the configured timeout in milliseconds that was exceeded. */
    public long getTimeoutMs() {
        return timeoutMs;
    }
}
