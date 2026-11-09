package com.sistema.gateway;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Vista agregada de un empleado y sus datos asociados.
 */
public record EmpleadoCompletoDTO(
        JsonNode empleado,
        JsonNode perfil,
        JsonNode vacaciones) {
}
