package com.microservicios.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sonda de salud del servicio (Reto 8).
 *
 * <p>Devuelve el formato que pide el enunciado —{@code status}, {@code service} y
 * un mapa {@code checks} con cada dependencia— pero <strong>no reimplementa las
 * comprobaciones</strong>: delega en el {@link HealthEndpoint} de Actuator, que
 * ya sabe consultar el pool de Hikari y la conexión a RabbitMQ. Esta clase solo
 * traduce su salida.
 *
 * <p>Esa delegación es deliberada. Un health check escrito a mano acaba siendo
 * un antipatrón de dos formas: o devuelve {@code {"status":"ok"}} sin verificar
 * nada —que es exactamente lo que hacía este servicio antes del Reto 8— o abre
 * sus propias conexiones para probar y entonces el propio sondeo degrada el
 * servicio que pretende vigilar.
 *
 * <p>El código HTTP forma parte del contrato: 200 cuando el estado es
 * {@code UP} y 503 cuando es {@code DOWN}, para que un orquestador o un
 * balanceador puedan decidir sin leer el cuerpo.
 */
@RestController
@Tag(name = "Observabilidad", description = "Sonda de salud del microservicio")
public class SaludController {

    /**
     * Nombres de las dependencias en el vocabulario del enunciado.
     *
     * <p>Actuator las llama {@code db} y {@code rabbit}; el enunciado pide
     * {@code database} y {@code messageBroker}. Traducir aquí mantiene la
     * respuesta homogénea con la de los servicios Node, que no tienen Actuator
     * y construyen el mismo JSON a mano.
     */
    private static final Map<String, String> NOMBRES = Map.of(
            "db", "database",
            "rabbit", "messageBroker",
            "diskSpace", "diskSpace",
            "ping", "process");

    private final HealthEndpoint healthEndpoint;
    private final String nombreServicio;

    public SaludController(HealthEndpoint healthEndpoint,
                           @Value("${spring.application.name:auth-service}") String nombreServicio) {
        this.healthEndpoint = healthEndpoint;
        this.nombreServicio = nombreServicio;
    }

    @GetMapping("/health")
    @Operation(summary = "Estado operativo del servicio y de sus dependencias",
            description = "Devuelve 200 con status UP si el servicio y sus dependencias responden, "
                    + "y 503 con status DOWN si alguna falla. Las comprobaciones las realiza "
                    + "Spring Boot Actuator sobre PostgreSQL y RabbitMQ.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Servicio operativo",
                    content = @Content(schema = @Schema(example =
                            "{\"status\":\"UP\",\"service\":\"auth-service\","
                                    + "\"checks\":{\"database\":\"UP\",\"messageBroker\":\"UP\"}}"))),
            @ApiResponse(responseCode = "503", description = "Servicio degradado: alguna dependencia no responde",
                    content = @Content(schema = @Schema(example =
                            "{\"status\":\"DOWN\",\"service\":\"auth-service\","
                                    + "\"checks\":{\"database\":\"DOWN\",\"messageBroker\":\"UP\"}}")))
    })
    public ResponseEntity<Map<String, Object>> health() {
        HealthComponent salud = healthEndpoint.health();

        Map<String, String> checks = new LinkedHashMap<>();
        if (salud instanceof CompositeHealth compuesta) {
            compuesta.getComponents().forEach((clave, componente) ->
                    checks.put(NOMBRES.getOrDefault(clave, clave),
                            componente.getStatus().getCode()));
        }

        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("status", salud.getStatus().getCode());
        cuerpo.put("service", nombreServicio);
        cuerpo.put("checks", checks);

        HttpStatus codigo = Status.UP.equals(salud.getStatus())
                ? HttpStatus.OK
                : HttpStatus.SERVICE_UNAVAILABLE;

        return ResponseEntity.status(codigo).body(cuerpo);
    }
}
