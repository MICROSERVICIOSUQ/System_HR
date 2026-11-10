package com.microservicios.auth.messaging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservicios.auth.events.EventEnvelope;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Mantiene el bloqueo hasta recibir confirmación de RabbitMQ y confirmar la marca. */
@Component
@ConditionalOnProperty(name = "outbox.enabled", havingValue = "true", matchIfMissing = true)
public class PublicadorDeOutbox {
    private static final Logger log = LoggerFactory.getLogger(PublicadorDeOutbox.class);
    private final OutboxRepository repository;
    private final AuthEventPublisher publisher;
    private final OutboxCipher cipher;
    private final ObjectMapper mapper;
    private final Tracer tracer;

    public PublicadorDeOutbox(OutboxRepository repository, AuthEventPublisher publisher,
                             OutboxCipher cipher, ObjectMapper mapper, ObjectProvider<Tracer> tracer) {
        this.repository = repository;
        this.publisher = publisher;
        this.cipher = cipher;
        this.mapper = mapper;
        this.tracer = tracer.getIfAvailable();
    }

    @Scheduled(fixedDelayString = "${outbox.intervalo-ms:1000}",
               initialDelayString = "${outbox.retraso-inicial-ms:1000}")
    @Transactional
    public void publicarPendientes() {
        for (OutboxEvento event : repository.bloquearPendientes(50)) {
            Span span = continuarTraza(event.getTraceparent());
            try (Tracer.SpanInScope scope = span == null ? null : tracer.withSpan(span)) {
                EventEnvelope<Object> envelope = mapper.readValue(cipher.decrypt(event.getPayload()),
                        new TypeReference<EventEnvelope<Object>>() {});
                publisher.enviarAlBroker(envelope);
                event.marcarPublicado();
            } catch (Exception error) {
                event.registrarIntentoFallido(error);
                log.warn("[OUTBOX] Evento pendiente de reintento | eventId={} | tipo={} | causa={}",
                        event.getId(), event.getTipo(), error.getClass().getSimpleName());
                break;
            } finally {
                if (span != null) span.end();
            }
        }
    }

    private Span continuarTraza(String parent) {
        if (tracer == null || parent == null) return null;
        String[] parts = parent.split("-");
        return tracer.spanBuilder().setParent(tracer.traceContextBuilder().traceId(parts[1]).spanId(parts[2])
                .sampled(parts[3].equals("01")).build()).name("outbox.publish").start();
    }
}
