package com.microservicios.auth.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Vista laxa del envelope para los eventos que llegan desde otros servicios.
 *
 * <p>{@code data} se deja como mapa a propósito: el auth-service solo necesita el
 * identificador del empleado y su email, y no debe romperse si el productor añade
 * campos al payload. El Reto 4 ya fija que ignorar campos desconocidos es la
 * política de los consumidores.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IncomingEvent(
        @JsonProperty("id") String id,
        @JsonProperty("type") String type,
        @JsonProperty("version") String version,
        @JsonProperty("occurredAt") String occurredAt,
        @JsonProperty("producer") String producer,
        @JsonProperty("data") Map<String, Object> data
) {

    /**
     * Identificador del empleado dentro del payload.
     *
     * <p>Los eventos de empleado lo traen en {@code data.id}; los de vacaciones, en
     * {@code data.empleadoId} (su {@code data.id} es el UUID del período). Se
     * consultan ambos para cubrir las dos familias de eventos.
     */
    public String empleadoId() {
        String desdeEmpleadoId = texto("empleadoId");
        return desdeEmpleadoId != null ? desdeEmpleadoId : texto("id");
    }

    public String email() {
        return texto("email");
    }

    private String texto(String clave) {
        if (data == null) {
            return null;
        }
        Object valor = data.get(clave);
        if (valor == null) {
            return null;
        }
        String s = valor.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
