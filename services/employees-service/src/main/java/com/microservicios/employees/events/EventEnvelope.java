package com.microservicios.employees.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/**
 * Sobre estándar para todos los eventos del sistema System_HR.
 * Estructura estricta con exactamente 6 campos.
 *
 * @param <T> Tipo del objeto payload en {@code data}
 */
public record EventEnvelope<T>(
        @JsonProperty("id") String id,
        @JsonProperty("type") String type,
        @JsonProperty("version") String version,
        @JsonProperty("occurredAt") String occurredAt,
        @JsonProperty("producer") String producer,
        @JsonProperty("data") T data
) {
    public static final String VERSION_1_0 = "1.0";
    public static final String PRODUCER_NAME = "employees-service";

    public static <T> EventEnvelope<T> of(String type, T data) {
        return new EventEnvelope<>(
                UUID.randomUUID().toString(),
                type,
                VERSION_1_0,
                Instant.now().toString(),
                PRODUCER_NAME,
                data
        );
    }
}
