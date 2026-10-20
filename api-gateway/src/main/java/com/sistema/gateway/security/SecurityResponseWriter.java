package com.sistema.gateway.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Escribe los 401 y 403 del borde con el mismo cuerpo JSON que devuelven los
 * microservicios, para que el cliente no tenga que distinguir si el rechazo vino
 * del gateway o del servicio final.
 */
@Component
public class SecurityResponseWriter {

    private final ObjectMapper objectMapper;

    public SecurityResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Mono<Void> escribir(ServerWebExchange exchange, HttpStatus status, String mensaje) {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("timestamp", OffsetDateTime.now().toString());
        cuerpo.put("status", status.value());
        cuerpo.put("error", status.getReasonPhrase());
        cuerpo.put("message", mensaje);
        cuerpo.put("path", exchange.getRequest().getPath().value());

        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        // Cabecera estándar para un 401 con esquema Bearer.
        if (status == HttpStatus.UNAUTHORIZED) {
            exchange.getResponse().getHeaders().set("WWW-Authenticate",
                    "Bearer realm=\"system-hr\"");
        }

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(cuerpo);
        } catch (Exception ex) {
            bytes = ("{\"status\":" + status.value() + ",\"message\":\"" + mensaje + "\"}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
