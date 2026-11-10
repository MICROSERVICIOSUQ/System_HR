package com.microservicios.employees;

import com.microservicios.employees.client.DepartmentCache;
import com.microservicios.employees.client.DepartmentClient;
import com.microservicios.employees.repository.EmployeeRepository;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import com.microservicios.employees.model.Employee;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prueba de integración: Reto 3 — Caso alternativo: caché de contingencia.
 *
 * <p>Verifica que el caché temporal de departamentos actúa correctamente como
 * fallback ante indisponibilidad de {@code departments-service}, sin sustituir
 * ni alterar el comportamiento del Circuit Breaker ni de las pruebas originales.
 *
 * <p>Escenarios cubiertos:
 * <ol>
 *   <li>Operación normal: respuesta real actualiza el caché.</li>
 *   <li>Caída con caché válido: registro exitoso usando caché.</li>
 *   <li>Caída sin caché: devuelve 503.</li>
 *   <li>Caché expirado: no utiliza información expirada, devuelve 503.</li>
 *   <li>Recuperación del servicio: vuelve a usar la fuente real.</li>
 *   <li>404 no usa caché: comportamiento de negocio preservado.</li>
 * </ol>
 *
 * <p>El TTL se configura en 1 minuto para esta clase. La expiración se simula
 * mediante reflexión sin esperar el TTL real.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:cache-tests;DB_CLOSE_DELAY=-1",
        "resilience4j.circuitbreaker.instances.departmentsCircuitBreaker.sliding-window-size=3",
        "resilience4j.circuitbreaker.instances.departmentsCircuitBreaker.minimum-number-of-calls=3",
        "resilience4j.circuitbreaker.instances.departmentsCircuitBreaker.failure-rate-threshold=100",
        "resilience4j.circuitbreaker.instances.departmentsCircuitBreaker.wait-duration-in-open-state=2s",
        "resilience4j.circuitbreaker.instances.departmentsCircuitBreaker.permitted-number-of-calls-in-half-open-state=1",
        "resilience4j.circuitbreaker.instances.departmentsCircuitBreaker.automatic-transition-from-open-to-half-open-enabled=true",
        "departments.cache.ttl-minutes=1"  // TTL corto para pruebas; expiración se simula via reflexión
})
@AutoConfigureMockMvc
class DepartmentCacheFallbackTest {

    static AtomicInteger responseStatus = new AtomicInteger(200);
    static HttpServer server = startServer();

    static HttpServer startServer() {
        try {
            var srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            srv.createContext("/departamentos", exchange -> {
                exchange.sendResponseHeaders(responseStatus.get(), -1);
                exchange.close();
            });
            srv.start();
            return srv;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void url(DynamicPropertyRegistry props) {
        props.add("departments.service.url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @Autowired DepartmentClient client;
    @Autowired DepartmentCache cache;
    @Autowired CircuitBreakerRegistry registry;
    @Autowired MockMvc mvc;
    @MockBean EmployeeRepository repository;

    CircuitBreaker circuit;

    @BeforeEach
    void reset() {
        circuit = registry.circuitBreaker(DepartmentClient.CIRCUIT_NAME);
        circuit.reset();
        cache.clear();
        responseStatus.set(200);

        // save() devuelve lo que recibe.
        //
        // Sin esta linea el mock devolvia null y el alta seguia con un empleado
        // nulo: al publicar el evento reventaba con NullPointerException. No se
        // notaba porque EmployeeService envolvia la publicacion en un try/catch
        // que se tragaba cualquier excepcion — el mismo que ocultaba la perdida
        // de eventos cuando RabbitMQ estaba caido. Al pasar al outbox, ese catch
        // desaparecio y el fallo del doble quedo a la vista.
        //
        // Esta clase prueba el circuit breaker y la cache, no la persistencia,
        // asi que lo correcto es que el doble se comporte como el repositorio
        // real y devuelva la entidad guardada.
        when(repository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Escenario 1 — Operación normal: respuesta real actualiza caché
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void escenario1_operacionNormal_actualizaCache() {
        // Servicio disponible, responde 200
        responseStatus.set(200);

        boolean exists = client.existsById("DEP-SC1");

        assertThat(exists).isTrue();
        // El caché debe haberse actualizado
        assertThat(cache.isValid("DEP-SC1")).isTrue();
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Escenario 2 — Caída con caché válido: registro exitoso
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void escenario2_caidaConCacheValido_registroExitoso() throws Exception {
        // Primero: servicio disponible → caché se puebla
        responseStatus.set(200);
        client.existsById("DEP-SC2");
        assertThat(cache.isValid("DEP-SC2")).isTrue();

        // Simular caída: el servidor responde 503 (error del lado del servidor)
        responseStatus.set(503);
        // Abrir circuito manualmente (equivalente a 3 fallos)
        circuit.transitionToOpenState();

        // Con circuito abierto y caché válido, existsById debe retornar true usando caché
        boolean exists = client.existsById("DEP-SC2");

        assertThat(exists).isTrue();
        // El circuito debe permanecer abierto (el caché no lo afecta)
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Escenario 2b — Caída con caché válido via POST /empleados → 201
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void escenario2b_caidaConCacheValido_registroViaMvc() throws Exception {
        // Poblar caché con respuesta real
        responseStatus.set(200);
        client.existsById("DEP-SC2B");

        // Simular caída del servicio
        circuit.transitionToOpenState();

        mvc.perform(post("/empleados")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(employeeBody("SC2B", "DEP-SC2B")))
                .andExpect(status().isCreated());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Escenario 3 — Caída sin caché: debe devolver 503
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void escenario3_caidaSinCache_devuelve503() throws Exception {
        // Sin ningún put previo, circuito abierto
        circuit.transitionToOpenState();

        mvc.perform(post("/empleados")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(employeeBody("SC3", "DEP-NUNCA-VISTO-SC3")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message")
                        .value("El servicio de departamentos no está disponible temporalmente"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Escenario 4 — Caché expirado: no utiliza información expirada → 503
    // La expiración se simula via reflexión, sin esperar el TTL real.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void escenario4_cacheExpirado_devuelve503() throws Exception {
        // Poblar caché
        responseStatus.set(200);
        client.existsById("DEP-SC4");
        assertThat(cache.isValid("DEP-SC4")).isTrue();

        // Forzar expiración: retrotraer el lastUpdated a 2 minutos atrás
        // (el TTL de prueba es 1 minuto, así que 2 min > TTL → expirado)
        forzarExpiracion(cache, "DEP-SC4", 2);
        assertThat(cache.isValid("DEP-SC4")).isFalse();

        // Con caché expirado y servicio caído → 503
        circuit.transitionToOpenState();

        mvc.perform(post("/empleados")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(employeeBody("SC4", "DEP-SC4")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message")
                        .value("El servicio de departamentos no está disponible temporalmente"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Escenario 5 — Recuperación: vuelve a usar servicio real y actualiza caché
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void escenario5_recuperacion_vuelveAUsarServicioReal() throws Exception {
        // 1. Servicio disponible → caché se puebla
        responseStatus.set(200);
        assertThat(client.existsById("DEP-SC5")).isTrue();

        // 2. Abrir circuito (simula caída)
        circuit.transitionToOpenState();
        // El caché permite continuar
        assertThat(client.existsById("DEP-SC5")).isTrue();

        // 3. Servicio se recupera → esperar HALF_OPEN
        await().atMost(Duration.ofSeconds(5))
                .until(() -> circuit.getState() == CircuitBreaker.State.HALF_OPEN);

        // 4. Servicio responde 200: circuito se cierra y caché se refresca
        responseStatus.set(200);
        boolean result = client.existsById("DEP-SC5");

        assertThat(result).isTrue();
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        // Caché refrescado
        assertThat(cache.isValid("DEP-SC5")).isTrue();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Escenario 6 — 404 no usa caché: comportamiento de negocio preservado
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void escenario6_respuesta404_noUsaCache_devuelve400() throws Exception {
        // Servicio disponible pero responde 404 (departamento no existe)
        responseStatus.set(404);

        // El caché NO debe consultarse: el servicio respondió correctamente (comunicación exitosa).
        // No hubo put(), por lo que el caché está vacío para este departamento.
        mvc.perform(post("/empleados")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(employeeBody("SC6", "DEP-NO-EXISTE-SC6")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("El departamento con id DEP-NO-EXISTE-SC6 no existe"));

        // El caché no debe haberse poblado (404 no se cachea)
        assertThat(cache.isValid("DEP-NO-EXISTE-SC6")).isFalse();
        // El circuito debe seguir CLOSED (404 es respuesta válida)
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void escenario6b_cacheExistenteNoUsadoSi404() throws Exception {
        // Suponer que en algún momento DEP-FALSO estuvo en el caché
        cache.put("DEP-FALSO-SC6B");
        assertThat(cache.isValid("DEP-FALSO-SC6B")).isTrue();

        // El servicio responde 404 (el departamento fue eliminado)
        responseStatus.set(404);

        // NO debe usar el caché: la respuesta 404 es comunicación exitosa → 400
        mvc.perform(post("/empleados")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(employeeBody("SC6B", "DEP-FALSO-SC6B")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("El departamento con id DEP-FALSO-SC6B no existe"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String employeeBody(String suffix, String depId) {
        return """
            {"id":"CACHE-%s","nombre":"Test","apellido":"Cache","email":"cache-%s@example.com",
             "numeroEmpleado":"CACHE-%s","cargo":"Dev","area":"TI",
             "departamentoId":"%s","fechaIngreso":"2026-09-29"}
            """.formatted(suffix, suffix.toLowerCase(), suffix, depId);
    }

    @SuppressWarnings("unchecked")
    private static void forzarExpiracion(DepartmentCache cache, String departmentId,
                                          long minutosAtras) throws Exception {
        Field storeField = DepartmentCache.class.getDeclaredField("store");
        storeField.setAccessible(true);
        ConcurrentHashMap<String, Object> store =
                (ConcurrentHashMap<String, Object>) storeField.get(cache);

        Class<?> entryClass = Class.forName("com.microservicios.employees.client.DepartmentCache$Entry");
        var constructor = entryClass.getDeclaredConstructor(String.class, Instant.class);
        constructor.setAccessible(true);
        Object entradaVieja = constructor.newInstance(departmentId,
                Instant.now().minusSeconds(minutosAtras * 60));

        store.put(departmentId, entradaVieja);
    }
}
