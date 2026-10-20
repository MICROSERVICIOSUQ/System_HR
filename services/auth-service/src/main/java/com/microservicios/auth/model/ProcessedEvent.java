package com.microservicios.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Registro de eventos ya consumidos, para garantizar idempotencia.
 *
 * <p>Mismo patrón que los consumidores del Reto 4: la clave es el {@code id} del
 * envelope. Importa especialmente aquí porque el scheduler del vacaciones-service
 * puede emitir el mismo evento más de una vez si se escala a N instancias.
 */
@Entity
@Table(name = "eventos_procesados")
public class ProcessedEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private String id;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "processed_at", nullable = false)
    private OffsetDateTime processedAt;

    protected ProcessedEvent() {}

    public ProcessedEvent(String id, String eventType) {
        this.id = id;
        this.eventType = eventType;
        this.processedAt = OffsetDateTime.now();
    }

    public String getId() { return id; }
    public String getEventType() { return eventType; }
    public OffsetDateTime getProcessedAt() { return processedAt; }
}
