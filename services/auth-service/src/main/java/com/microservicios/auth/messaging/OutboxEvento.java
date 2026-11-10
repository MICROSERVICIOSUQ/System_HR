package com.microservicios.auth.messaging;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Se confirma en la misma transacción que la cuenta y el evento consumido. */
@Entity
@Table(name = "outbox_evento")
public class OutboxEvento {
    @Id
    @Column(length = 36)
    private String id;
    @Column(nullable = false, length = 80)
    private String tipo;
    @Column(name = "agregado_id", nullable = false, length = 60)
    private String agregadoId;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;
    @Column(name = "traceparent", length = 55)
    private String traceparent;
    @Column(name = "creado_en", nullable = false)
    private LocalDateTime creadoEn;
    @Column(name = "publicado_en")
    private LocalDateTime publicadoEn;
    @Column(nullable = false)
    private int intentos;
    @Column(name = "ultimo_error", length = 500)
    private String ultimoError;

    protected OutboxEvento() {}

    public OutboxEvento(String id, String tipo, String agregadoId, String payload, String traceparent) {
        this.id = id;
        this.tipo = tipo;
        this.agregadoId = agregadoId;
        this.payload = payload;
        this.traceparent = traceparent;
        this.creadoEn = LocalDateTime.now();
    }

    public void marcarPublicado() {
        publicadoEn = LocalDateTime.now();
        ultimoError = null;
        // El evento ya fue confirmado por RabbitMQ; no conservar su token cifrado.
        payload = "";
    }

    public void registrarIntentoFallido(Exception error) {
        intentos++;
        // Un mensaje de una excepción de serialización podría incluir el token.
        ultimoError = error.getClass().getSimpleName();
    }

    public String getId() { return id; }
    public String getTipo() { return tipo; }
    public String getPayload() { return payload; }
    public String getTraceparent() { return traceparent; }
    public LocalDateTime getPublicadoEn() { return publicadoEn; }
    public int getIntentos() { return intentos; }
}
