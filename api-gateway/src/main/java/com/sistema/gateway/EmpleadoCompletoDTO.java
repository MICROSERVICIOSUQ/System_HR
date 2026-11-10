package com.sistema.gateway;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Vista agregada de un empleado y sus datos asociados.
 */
public record EmpleadoCompletoDTO(
        JsonNode empleado,
        JsonNode perfil,
        JsonNode vacaciones,
        @JsonProperty("_warnings") List<String> warnings) {
}
