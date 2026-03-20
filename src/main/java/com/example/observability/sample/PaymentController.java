package com.example.observability.sample;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sample REST controller demonstrating end-to-end observability with {@code @Monitored}.
 *
 * <p>Metrics are emitted automatically when service methods are called.
 * Visit {@code /actuator/prometheus} to see emitted metrics.
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/process")
    public ResponseEntity<String> processPayment(
            @RequestParam String channel,
            @RequestParam String requestId,
            @RequestParam double amount) {
        String result = paymentService.processPayment(channel, requestId, amount);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/refund")
    public ResponseEntity<String> processRefund(
            @RequestParam String channel,
            @RequestParam String transactionId) {
        String result = paymentService.processRefund(channel, transactionId);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}
