package com.microservicios.employees.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.annotation.PostConstruct;

/**
 * Caché temporal de contingencia para información de departamentos.
 *
 * <p>Propósito: cuando {@code departments-service} no está disponible y los
 * mecanismos de retry y Circuit Breaker ya agotaron sus intentos, este
 * componente permite que {@code employees-service} continúe operando durante
 * un tiempo limitado usando la última respuesta válida que haya recibido.
 *
 * <h2>Semántica de uso</h2>
 * <ul>
 *   <li>El caché se <strong>actualiza únicamente con respuestas reales exitosas</strong>
 *       del servicio de departamentos.</li>
 *   <li>Se <strong>consulta únicamente como fallback</strong> ante indisponibilidad
 *       (excepción de red o circuito abierto). Un 404 del servicio real no activa
 *       el caché como sustituto.</li>
 *   <li>Cada entrada expira a los {@code ttlMinutes} minutos desde su
 *       <em>última actualización exitosa</em>. Una interrupción del servicio no
 *       reinicia el TTL.</li>
 *   <li>Una vez que el servicio real se recupera, las consultas vuelven a pasar
 *       por él y el caché se refresca con información actualizada.</li>
 * </ul>
 *
 * <h2>Consistencia eventual</h2>
 * Durante una ventana de indisponibilidad, este componente puede servir
 * información que ya no coincide con el estado real de la base de datos de
 * departamentos. Esto es una consecuencia controlada y explícita del mecanismo
 * de resiliencia, limitada por el TTL configurado.
 *
 * <h2>Fuente de verdad</h2>
 * La fuente de verdad continúa siendo {@code departments-service → PostgreSQL}.
 * Este caché es una contingencia temporal, no una réplica permanente.
 *
 * <h2>Configuración</h2>
 * El TTL se controla con la propiedad {@code departments.cache.ttl-minutes}
 * (predeterminado: 30 minutos).
 */
@Component
public class DepartmentCache {

    private static final Logger log = LoggerFactory.getLogger(DepartmentCache.class);

    /**
     * Entrada del caché. Almacena únicamente el identificador del departamento y
     * el instante en que fue actualizada por última vez con una respuesta válida.
     * No almacena la respuesta HTTP completa ni ningún dato sensible.
     */
    record Entry(String departmentId, Instant lastUpdated) {}

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();
    private final Duration ttl;

    public DepartmentCache(@Value("${departments.cache.ttl-minutes:30}") long ttlMinutes) {
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    /**
     * Registra que el departamento existe según una respuesta real del servicio.
     * Actualiza la entrada si ya existía, reiniciando su TTL.
     *
     * @param departmentId identificador del departamento confirmado como existente
     */
    public void put(String departmentId) {
        Entry entry = new Entry(departmentId, Instant.now());
        store.put(departmentId, entry);
        log.debug("Cache actualizado: departamento='{}' expira en {} minutos", departmentId, ttl.toMinutes());
    }

    @PostConstruct
    public void init() {
        log.warn("=== CONFIGURACIÓN CACHÉ: El TTL inyectado por Spring es de {} minutos ===", ttl.toMinutes());
    }

    /**
     * Indica si existe una entrada vigente (dentro del TTL) para el departamento.
     *
     * <p>Este método debe llamarse <strong>únicamente ante indisponibilidad</strong>
     * del servicio real, como fallback de último recurso.
     *
     * @param departmentId identificador del departamento a consultar
     * @return {@code true} si hay una entrada válida y no expirada; {@code false}
     *         si no existe entrada o ha superado el TTL
     */
    public boolean isValid(String departmentId) {
        Entry entry = store.get(departmentId);
        if (entry == null) {
            log.debug("Cache miss: no existe entrada para departamento='{}'", departmentId);
            return false;
        }
        boolean expired = Duration.between(entry.lastUpdated(), Instant.now()).compareTo(ttl) > 0;
        if (expired) {
            log.debug("Cache expirado: departamento='{}' (última actualización: {})", departmentId, entry.lastUpdated());
            return false;
        }
        log.info("Cache hit (contingencia): departamento='{}' — usando información de {}", departmentId, entry.lastUpdated());
        return true;
    }

    /**
     * Elimina la entrada del caché para el departamento indicado.
     * Destinado principalmente a uso en pruebas.
     *
     * @param departmentId identificador del departamento a eliminar
     */
    public void evict(String departmentId) {
        store.remove(departmentId);
    }

    /**
     * Elimina todas las entradas del caché.
     * Destinado principalmente a uso en pruebas.
     */
    public void clear() {
        store.clear();
    }

    /** Devuelve el TTL configurado (para pruebas e introspección). */
    public Duration getTtl() {
        return ttl;
    }
}
