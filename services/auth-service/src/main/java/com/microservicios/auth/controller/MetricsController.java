package com.microservicios.auth.controller;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Alias común; /actuator/prometheus continúa disponible. */
@RestController
public class MetricsController {
    private final ObjectProvider<PrometheusMeterRegistry> registry;

    public MetricsController(ObjectProvider<PrometheusMeterRegistry> registry) {
        this.registry = registry;
    }

    @GetMapping(value = "/metrics", produces = "text/plain; version=0.0.4; charset=utf-8")
    public String metrics() {
        PrometheusMeterRegistry prometheus = registry.getIfAvailable();
        if (prometheus == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Prometheus deshabilitado");
        return prometheus.scrape();
    }
}
