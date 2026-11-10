package com.microservicios.employees.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Evento pendiente de publicar, guardado en la misma transacción que el cambio
 * de negocio que lo produjo.
 *
 * <p><strong>El problema que resuelve.</strong> Hasta ahora el alta de un
 * empleado hacía dos cosas que podían separarse: guardaba la fila y, después,
 * publicaba el evento con {@code convertAndSend} dentro de un try/catch que solo
 * registraba el fallo. Si RabbitMQ no estaba disponible en ese instante, el
 * empleado quedaba creado y {@code empleado.creado} no llegaba nunca. Nadie
 * reintentaba, así que auth-service no creaba la cuenta, profiles no creaba el
 * perfil y vacations no creaba el saldo: una inconsistencia permanente que solo
 * se notaba más tarde y que había que arreglar a mano.
 *
 * <p><strong>Cómo lo resuelve.</strong> El evento se escribe en esta tabla
 * dentro de la transacción del cambio de negocio. O se guardan los dos o no se
 * guarda ninguno, porque es la misma transacción de la misma base de datos: no
 * hace falta una transacción distribuida. Un publicador periódico
 * ({@link PublicadorDeOutbox}) lee lo pendiente y lo envía al broker, marcando
 * cada fila cuando el envío tiene éxito. Si el broker está caído, las filas se
 * quedan y se reintentan solas.
 *
 * <p><strong>Lo que garantiza y lo que no.</strong> Garantiza entrega
 * <em>al menos una vez</em>: un corte entre el envío y la marca de publicado
 * reenvía el evento. No garantiza exactamente una vez, y no hace falta: los
 * consumidores son idempotentes por {@code eventId} (ADR-007), así que un evento
 * repetido se descarta. El orden tampoco se garantiza de forma estricta, pero el
 * publicador procesa por fecha de creación, que es el mismo orden en el que
 * ocurrieron los hechos.
 */
@Entity
@Table(name = "outbox_evento")
public class OutboxEvento {

    /** El {@code id} del sobre del evento. Es también la clave de idempotencia. */
    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "tipo", nullable = false, length = 80)
    private String tipo;

    /** Identificador del empleado al que se refiere, para poder rastrearlo. */
    @Column(name = "agregado_id", nullable = false, length = 60)
    private String agregadoId;

    /** El sobre completo serializado, tal como se enviará al broker. */
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "traceparent", length = 55)
    private String traceparent;

    @Column(name = "creado_en", nullable = false)
    private LocalDateTime creadoEn;

    /** Null mientras está pendiente. Se rellena cuando el broker lo acepta. */
    @Column(name = "publicado_en")
    private LocalDateTime publicadoEn;

    /** Cuántas veces se ha intentado enviar. Sirve para detectar un atasco. */
    @Column(name = "intentos", nullable = false)
    private int intentos;

    /** Último error de publicación, para no tener que buscarlo en los logs. */
    @Column(name = "ultimo_error", length = 500)
    private String ultimoError;

    protected OutboxEvento() {
        // Requerido por JPA.
    }

    public OutboxEvento(String id, String tipo, String agregadoId, String payload) {
        this.id = id;
        this.tipo = tipo;
        this.agregadoId = agregadoId;
        this.payload = payload;
        this.creadoEn = LocalDateTime.now();
        this.intentos = 0;
    }

    public void marcarPublicado() {
        this.publicadoEn = LocalDateTime.now();
        this.ultimoError = null;
    }

    public void registrarIntentoFallido(String motivo) {
        this.intentos++;
        // Se recorta porque un stack trace no cabe en la columna y tampoco
        // aporta: el detalle completo queda en el log con el mismo eventId.
        this.ultimoError = motivo == null ? null
                : motivo.substring(0, Math.min(motivo.length(), 500));
    }

    public String getId() {
        return id;
    }

    public String getTipo() {
        return tipo;
    }

    public String getAgregadoId() {
        return agregadoId;
    }

    public String getPayload() {
        return payload;
    }

    public String getTraceparent() { return traceparent; }
    public void setTraceparent(String traceparent) { this.traceparent = traceparent; }

    public LocalDateTime getCreadoEn() {
        return creadoEn;
    }

    public LocalDateTime getPublicadoEn() {
        return publicadoEn;
    }

    public int getIntentos() {
        return intentos;
    }

    public String getUltimoError() {
        return ultimoError;
    }
}
