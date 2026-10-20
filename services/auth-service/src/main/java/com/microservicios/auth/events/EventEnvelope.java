package com.microservicios.auth.events;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/**
 * Sobre estándar del Catálogo de Eventos: exactamente 6 campos.
 * Idéntico al que usan employees-service y los servicios de Node.
 *
 * @param <T> tipo del payload en {@code data}
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
    public static final String PRODUCER_NAME = "auth-service";

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
