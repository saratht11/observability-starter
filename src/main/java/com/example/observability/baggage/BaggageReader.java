package com.example.observability.baggage;

import java.util.Map;

/**
 * Abstraction for reading OpenTelemetry baggage entries from the current trace context.
 *
 * <p>Baggage values are propagated across service boundaries in distributed traces.
 * This interface allows the {@code MonitoredAspect} to read those values and optionally
 * include them as metric tags or span attributes.
 *
 * <p><b>Important:</b> Only include baggage fields that are guaranteed to be low-cardinality
 * when used as metric tags (e.g. {@code tenant}, {@code region}, {@code channel}).
 */
public interface BaggageReader {

    /**
     * Returns the value of a single baggage field by name, or {@code null} if not present.
     *
     * @param field the baggage field name
     * @return the baggage value, or {@code null}
     */
    String read(String field);

    /**
     * Returns all currently available baggage entries as an immutable name-to-value map.
     *
     * @return map of all baggage entries; never {@code null}
     */
    Map<String, String> readAll();
}
