package com.sistema.gateway;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sonda de salud del gateway (Reto 8).
 *
 * <p>Antes devolvía {@code {"status":"ok"}} sin comprobar nada, que es el
 * antipatrón clásico: un health check que siempre responde que todo va bien no
 * aporta información, solo tranquilidad falsa. Ahora delega en el
 * {@link HealthEndpoint} de Actuator y traduce su salida al formato del
 * enunciado ({@code status}, {@code service}, {@code checks}), con 200 si está
 * {@code UP} y 503 si está {@code DOWN}.
 *
 * <p><strong>Por qué el gateway no comprueba los servicios de aguas abajo.</strong>
 * Sería tentador que su salud dependiera de la de los siete microservicios, pero
 * eso convertiría la caída de un servicio secundario en la caída del borde
 * completo: un fallo parcial se propagaría como total y el balanceador retiraría
 * el único punto de entrada del sistema. La salud de cada servicio se vigila en
 * su propio {@code /health}, y Prometheus los raspa por separado. El gateway
 * solo responde por sí mismo.
 */
@RestController
public class HealthController {

    private final HealthEndpoint healthEndpoint;
    private final String nombreServicio;

    public HealthController(HealthEndpoint healthEndpoint,
                            @Value("${spring.application.name:api-gateway}") String nombreServicio) {
        this.healthEndpoint = healthEndpoint;
        this.nombreServicio = nombreServicio;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        HealthComponent salud = healthEndpoint.health();

        Map<String, String> checks = new LinkedHashMap<>();
        if (salud instanceof CompositeHealth compuesta) {
            compuesta.getComponents().forEach((clave, componente) ->
                    checks.put(clave, componente.getStatus().getCode()));
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
