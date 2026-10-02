package com.microservicios.employees;

import com.microservicios.employees.client.DepartmentCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas unitarias del componente {@link DepartmentCache}.
 *
 * <p>No requieren Spring Boot. Se instancia el componente directamente para
 * comprobar el comportamiento del caché en todos los escenarios relevantes.
 * La expiración se prueba manipulando directamente el instante de la entrada
 * (reflexión) para no hacer lenta la suite ni esperar el TTL real.
 */
class DepartmentCacheTest {

    private DepartmentCache cache;

    @BeforeEach
    void setUp() {
        // TTL de 30 minutos (valor real de producción)
        cache = new DepartmentCache(30L);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Escenario 1 — Operación normal: put() luego isValid() devuelve true
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void putAndIsValid_devuelveTrue() {
        cache.put("DEP-001");

        assertThat(cache.isValid("DEP-001")).isTrue();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Escenario 3 — Caché inexistente: isValid() devuelve false sin put previo
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void isValid_sinPutPrevio_devuelveFalse() {
        assertThat(cache.isValid("DEP-NUNCA-VISTO")).isFalse();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Escenario 4 — Caché expirado: la entrada existe pero superó el TTL
    // La expiración se simula forzando un lastUpdated muy anterior mediante
    // reflexión, sin necesidad de esperar el TTL real.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void isValid_entradaExpirada_devuelveFalse() throws Exception {
        cache.put("DEP-EXPIRADO");

        // Forzar lastUpdated a 31 minutos en el pasado
        forzarExpiracion("DEP-EXPIRADO", 31);

        assertThat(cache.isValid("DEP-EXPIRADO")).isFalse();
    }

    @Test
    void isValid_entradaCasiExpirada_devuelveTrue() throws Exception {
        cache.put("DEP-VIGENTE");

        // 29 minutos en el pasado: todavía dentro del TTL de 30 minutos
        forzarExpiracion("DEP-VIGENTE", 29);

        assertThat(cache.isValid("DEP-VIGENTE")).isTrue();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Escenario 2 — Actualización refresca el TTL
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void putDoble_refrescaTTL() throws Exception {
        cache.put("DEP-REFRESH");
        // Primero envejecer la entrada
        forzarExpiracion("DEP-REFRESH", 31);
        assertThat(cache.isValid("DEP-REFRESH")).isFalse();

        // Un nuevo put (respuesta real) refresca el TTL
        cache.put("DEP-REFRESH");
        assertThat(cache.isValid("DEP-REFRESH")).isTrue();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // evict() elimina la entrada
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void evict_eliminaEntrada() {
        cache.put("DEP-TO-EVICT");
        cache.evict("DEP-TO-EVICT");

        assertThat(cache.isValid("DEP-TO-EVICT")).isFalse();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // clear() elimina todas las entradas
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void clear_eliminaTodas() {
        cache.put("DEP-A");
        cache.put("DEP-B");
        cache.clear();

        assertThat(cache.isValid("DEP-A")).isFalse();
        assertThat(cache.isValid("DEP-B")).isFalse();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TTL configurado correctamente
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void ttlConfiguracion_treintaMinutos() {
        assertThat(cache.getTtl().toMinutes()).isEqualTo(30);
    }

    @Test
    void ttlConfiguracionPersonalizado() {
        DepartmentCache cache5min = new DepartmentCache(5L);
        assertThat(cache5min.getTtl().toMinutes()).isEqualTo(5);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Concurrencia: múltiples hilos no corrompen el estado
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void concurrencia_multiplesHilos_noCorrompenEstado() throws InterruptedException {
        int hilos = 20;
        Thread[] threads = new Thread[hilos];
        for (int i = 0; i < hilos; i++) {
            final String dep = "DEP-CONC-" + (i % 5);
            threads[i] = new Thread(() -> {
                cache.put(dep);
                cache.isValid(dep);
            });
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        // Al menos DEP-CONC-0..4 deben estar en el caché sin excepción
        for (int i = 0; i < 5; i++) {
            assertThat(cache.isValid("DEP-CONC-" + i)).isTrue();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilidad interna: simula el envejecimiento de una entrada via reflexión
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void forzarExpiracion(String departmentId, long minutosAtras) throws Exception {
        Field storeField = DepartmentCache.class.getDeclaredField("store");
        storeField.setAccessible(true);
        ConcurrentHashMap<String, ?> store =
                (ConcurrentHashMap<String, ?>) storeField.get(cache);

        // La entrada es un record: DepartmentCache.Entry(String departmentId, Instant lastUpdated)
        // Reconstruimos la entrada con un lastUpdated en el pasado.
        Class<?> entryClass = Class.forName("com.microservicios.employees.client.DepartmentCache$Entry");
        var constructor = entryClass.getDeclaredConstructor(String.class, Instant.class);
        constructor.setAccessible(true);
        Object entradaVieja = constructor.newInstance(departmentId, Instant.now().minusSeconds(minutosAtras * 60));

        // Reemplazar directamente en el mapa (ConcurrentHashMap es thread-safe)
        ((ConcurrentHashMap<String, Object>) store).put(departmentId, entradaVieja);
    }
}
