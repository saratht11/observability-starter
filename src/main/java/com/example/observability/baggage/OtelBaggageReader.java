package com.example.observability.baggage;

import io.opentelemetry.api.baggage.Baggage;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * OpenTelemetry-backed implementation of {@link BaggageReader}.
 *
 * <p>Reads baggage from the current OTel context using {@link Baggage#current()}.
 * This works correctly when called within a span / trace context that propagates baggage.
 */
@Component
public class OtelBaggageReader implements BaggageReader {

    @Override
    public String read(String field) {
        if (field == null || field.isBlank()) {
            return null;
        }
        return Baggage.current().getEntryValue(field);
    }

    @Override
    public Map<String, String> readAll() {
        Map<String, String> result = new HashMap<>();
        Baggage.current().forEach((key, entry) -> result.put(key, entry.getValue()));
        return Collections.unmodifiableMap(result);
    }
}
