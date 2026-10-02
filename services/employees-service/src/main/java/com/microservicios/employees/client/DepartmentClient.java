package com.microservicios.employees.client;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Coordina la consulta al servicio de departamentos con resiliencia en dos niveles:
 *
 * <ol>
 *   <li><strong>Nivel 1 — Servicio real:</strong> {@link DepartmentHttpClient} con
 *       retry + {@link CircuitBreaker}. Es siempre la primera opción.</li>
 *   <li><strong>Nivel 2 — Caché de contingencia:</strong> {@link DepartmentCache}
 *       consultado únicamente cuando la dependencia real no está disponible
 *       (excepción de red, 5xx o circuito abierto). Un 404 del servicio real
 *       no activa el caché como sustituto.</li>
 * </ol>
 *
 * <p>Flujo normal (servicio disponible):
 * <pre>
 *   existsById() → CircuitBreaker → DepartmentHttpClient (Retry + HTTP)
 *                                          ↓ respuesta válida
 *                                   actualizar DepartmentCache
 * </pre>
 *
 * <p>Flujo de contingencia (servicio indisponible):
 * <pre>
 *   existsById() → CircuitBreaker → fallo / OPEN
 *                        ↓
 *                  DepartmentCache.isValid()
 *                   ┌── true  → retorna true (empleado puede registrarse)
 *                   └── false → relanza excepción → GlobalExceptionHandler → 503
 * </pre>
 */
@Component
public class DepartmentClient {

    public static final String CIRCUIT_NAME = "departmentsCircuitBreaker";

    private final DepartmentHttpClient httpClient;
    private final CircuitBreaker circuitBreaker;
    private final DepartmentCache cache;

    public DepartmentClient(DepartmentHttpClient httpClient,
                            CircuitBreakerRegistry registry,
                            DepartmentCache cache) {
        this.httpClient = httpClient;
        this.circuitBreaker = registry.circuitBreaker(CIRCUIT_NAME);
        this.cache = cache;
        circuitBreaker.getEventPublisher().onStateTransition(event ->
                LoggerFactory.getLogger(DepartmentClient.class).info("Circuito departamentos: {}",
                        event.getStateTransition()));
    }

    /**
     * Comprueba si el departamento existe.
     *
     * <p>Orden de prioridad:
     * <ol>
     *   <li>Consulta real a {@code departments-service} (siempre primer intento).</li>
     *   <li>Si la consulta real fue exitosa, actualiza el caché y retorna el resultado.</li>
     *   <li>Si la consulta real falla por indisponibilidad, consulta el caché de
     *       contingencia. Si hay una entrada vigente, retorna {@code true}. Si no,
     *       relanza la excepción original para que llegue al {@code GlobalExceptionHandler}
     *       y produzca el {@code 503} de siempre.</li>
     * </ol>
     *
     * <p>Un {@code 404} del servicio real devuelve {@code false} sin excepción
     * (comunicación exitosa). En ese caso el caché <strong>no se consulta</strong>
     * como fallback, preservando el comportamiento de negocio actual.
     *
     * @param departmentId identificador del departamento a validar
     * @return {@code true} si el departamento existe (real o por caché de contingencia);
     *         {@code false} si el servicio respondió que no existe (404)
     * @throws ResourceAccessException      si el servicio no es alcanzable y no hay caché válido
     * @throws HttpServerErrorException     si el servicio respondió 5xx y no hay caché válido
     * @throws CallNotPermittedException    si el circuito está abierto y no hay caché válido
     */
    public boolean existsById(String departmentId) {
        try {
            // One exhausted retry sequence is one failed call. OPEN never enters the HTTP client.
            // A remote 404 returns false and counts as a successful communication.
            boolean exists = circuitBreaker.executeSupplier(() -> httpClient.existsById(departmentId));

            // Respuesta real exitosa: actualizar caché (solo cuando el departamento existe).
            // Un false (404) no se cachea: no sería útil como contingencia.
            if (exists) {
                cache.put(departmentId);
            }
            return exists;

        } catch (ResourceAccessException | HttpServerErrorException | CallNotPermittedException ex) {
            // La dependencia real no está disponible. Consultar caché de contingencia.
            if (cache.isValid(departmentId)) {
                LoggerFactory.getLogger(DepartmentClient.class)
                        .warn("Usando caché de contingencia para departamento='{}' — departments-service no disponible",
                                departmentId);
                return true;
            }
            // Sin caché válido: relanzar para que GlobalExceptionHandler devuelva 503.
            throw ex;
        }
    }
}
