package com.microservicios.employees.messaging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservicios.employees.events.EmployeeEventData;
import com.microservicios.employees.events.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Envía al broker los eventos que esperan en la tabla outbox.
 *
 * <p>Es la segunda mitad del patrón: la primera —escribir el evento en la misma
 * transacción que el cambio de negocio— la hace {@link EmployeeEventPublisher}.
 * Aquí solo se lee lo pendiente, se publica y se marca.
 *
 * <p><strong>Cada evento se marca por separado.</strong> Si el tercero de cinco
 * falla, los dos primeros quedan marcados como publicados y no se reenvían. Con
 * una sola transacción para todo el lote, un fallo al final habría deshecho las
 * marcas de los anteriores y los habría reenviado, multiplicando los duplicados
 * sin necesidad.
 *
 * <p><strong>Un fallo no interrumpe el ciclo</strong> cuando es de un evento
 * concreto, pero sí lo corta cuando es del broker: si no hay conexión, insistir
 * con los 49 restantes solo llena el log. Se registra, se incrementa el contador
 * de intentos y se espera al ciclo siguiente.
 */
@Component
public class PublicadorDeOutbox {

    private static final Logger log = LoggerFactory.getLogger(PublicadorDeOutbox.class);

    /** Tipo concreto del sobre, necesario para deserializar los genéricos. */
    private static final TypeReference<EventEnvelope<EmployeeEventData>> TIPO_DEL_SOBRE =
            new TypeReference<>() {};

    private final OutboxRepository repository;
    private final EmployeeEventPublisher publisher;
    private final ObjectMapper objectMapper;
    private final int porCiclo;

    public PublicadorDeOutbox(OutboxRepository repository,
                              EmployeeEventPublisher publisher,
                              ObjectMapper objectMapper,
                              @Value("${outbox.eventos-por-ciclo:50}") int porCiclo) {
        this.repository = repository;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
        this.porCiclo = porCiclo;
    }

    /**
     * Publica lo pendiente.
     *
     * <p>{@code fixedDelay} y no {@code fixedRate}: la espera cuenta desde que
     * termina el ciclo anterior, así que un ciclo lento —el broker respondiendo
     * despacio— no encadena ejecuciones solapadas.
     */
    @Scheduled(fixedDelayString = "${outbox.intervalo-ms:5000}",
               initialDelayString = "${outbox.retraso-inicial-ms:10000}")
    @Transactional
    public void publicarPendientes() {
        List<OutboxEvento> pendientes =
                repository.findByPublicadoEnIsNullOrderByCreadoEnAsc(Limit.of(porCiclo));
        if (pendientes.isEmpty()) {
            return;
        }

        log.info("[OUTBOX] Publicando {} evento(s) pendiente(s)", pendientes.size(),
                kv("pendientes", pendientes.size()));

        int publicados = 0;
        for (OutboxEvento evento : pendientes) {
            try {
                publicar(evento);
                evento.marcarPublicado();
                publicados++;
                log.info("[OUTBOX] [PUBLICADO] {} | eventId='{}' | empleado='{}'",
                        evento.getTipo(), evento.getId(), evento.getAgregadoId(),
                        kv("eventType", evento.getTipo()), kv("eventId", evento.getId()),
                        kv("empleadoId", evento.getAgregadoId()),
                        kv("intentos", evento.getIntentos()));
            } catch (Exception error) {
                evento.registrarIntentoFallido(error.getMessage());
                log.error("[OUTBOX] [ERROR] No se pudo publicar | eventId='{}' | intentos={} | causa='{}'",
                        evento.getId(), evento.getIntentos(), error.getMessage(),
                        kv("eventId", evento.getId()), kv("eventType", evento.getTipo()),
                        kv("empleadoId", evento.getAgregadoId()),
                        kv("intentos", evento.getIntentos()),
                        kv("causa", error.getMessage()));

                if (esFalloDelBroker(error)) {
                    log.warn("[OUTBOX] Se interrumpe el ciclo: el broker no está disponible. "
                                    + "Quedan {} evento(s) para el siguiente intento",
                            pendientes.size() - publicados,
                            kv("pendientes", pendientes.size() - publicados));
                    break;
                }
            }
        }
        // Las marcas se persisten al cerrar la transacción: las entidades están
        // gestionadas, así que no hace falta un save() explícito.
    }

    private void publicar(OutboxEvento evento) throws Exception {
        // Se deserializa al sobre y se publica con el mismo converter que antes,
        // de modo que lo que llega al broker es byte a byte lo que llegaba con
        // la publicación directa. El outbox cambia CUÁNDO se envía, no QUÉ.
        EventEnvelope<EmployeeEventData> sobre =
                objectMapper.readValue(evento.getPayload(), TIPO_DEL_SOBRE);
        publisher.enviarAlBroker(sobre);
    }

    /**
     * Distingue «el broker no está» de «este evento no se puede enviar».
     *
     * <p>Un evento con un payload corrupto fallaría siempre, y cortar el ciclo
     * por él dejaría atascados a todos los que vienen detrás. Un broker caído, en
     * cambio, hará fallar a todos, así que no tiene sentido intentarlo.
     */
    private boolean esFalloDelBroker(Throwable error) {
        for (Throwable actual = error; actual != null; actual = actual.getCause()) {
            if (actual instanceof org.springframework.amqp.AmqpException) {
                return true;
            }
            if (actual instanceof java.net.ConnectException
                    || actual instanceof java.io.IOException) {
                return true;
            }
        }
        return false;
    }

    /** Cuántos eventos esperan. Lo usa la sonda de salud y las pruebas. */
    @Transactional(readOnly = true)
    public long pendientes() {
        return repository.countByPublicadoEnIsNull();
    }
}
