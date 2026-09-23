package com.sistema.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
@Order(-2)
public class GatewayErrorHandler implements ErrorWebExceptionHandler {
    private final ObjectMapper mapper;

    public GatewayErrorHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        if (exchange.getResponse().isCommitted() || !isUnavailable(error)) {
            return Mono.error(error);
        }
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return Mono.fromCallable(() -> mapper.writeValueAsBytes(Map.of(
                "timestamp", OffsetDateTime.now().toString(),
                "status", 503,
                "error", "Service Unavailable",
                "message", "El servicio de destino no está disponible temporalmente",
                "path", exchange.getRequest().getPath().value())))
                .flatMap(body -> response.writeWith(Mono.just(response.bufferFactory().wrap(body))));
    }

    private boolean isUnavailable(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException || cause instanceof TimeoutException
                    || cause instanceof io.netty.handler.timeout.TimeoutException
                    || (cause instanceof ResponseStatusException status
                        && status.getStatusCode().value() == 504)) {
                return true;
            }
        }
        return false;
    }
}
