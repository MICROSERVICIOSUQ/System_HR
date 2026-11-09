package com.microservicios.auth.service;

import com.microservicios.auth.events.AuthEvents;
import com.microservicios.auth.events.IncomingEvent;
import com.microservicios.auth.messaging.AuthEventPublisher;
import com.microservicios.auth.model.AccountStatus;
import com.microservicios.auth.model.ProcessedEvent;
import com.microservicios.auth.model.UserAccount;
import com.microservicios.auth.repository.ProcessedEventRepository;
import com.microservicios.auth.repository.UserAccountRepository;

import static net.logstash.logback.argument.StructuredArguments.kv;
import com.microservicios.auth.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Ciclo de vida de la cuenta dirigido por eventos.
 *
 * <pre>
 *   empleado.creado        → PENDIENTE_ACTIVACION  → publica usuario.creado
 *   (reset-password)       → ACTIVA                → publica cuenta.activada
 *   vacaciones.iniciadas   → SUSPENDIDA_TEMPORAL   → publica cuenta.desactivada (permanente=false)
 *   vacaciones.finalizadas → ACTIVA                → publica cuenta.activada (FIN_VACACIONES)
 *   empleado.retirado      → DESACTIVADA_PERMANENTE → publica cuenta.desactivada (permanente=true)
 * </pre>
 *
 * <p><strong>Caso borde evaluado:</strong> si el empleado se retira estando de
 * vacaciones, al llegar la {@code fechaFin} entra {@code vacaciones.finalizadas}
 * sobre una cuenta ya {@link AccountStatus#DESACTIVADA_PERMANENTE}. La reactivación
 * se omite porque solo {@link AccountStatus#esReactivablePorVacaciones()} habilita
 * el cambio, y ese estado es exclusivamente SUSPENDIDA_TEMPORAL.
 */
@Service
public class AccountLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(AccountLifecycleService.class);

    private final UserAccountRepository repository;
    private final ProcessedEventRepository processedEvents;
    private final JwtService jwtService;
    private final AuthEventPublisher publisher;

    public AccountLifecycleService(UserAccountRepository repository,
                                   ProcessedEventRepository processedEvents,
                                   JwtService jwtService,
                                   AuthEventPublisher publisher) {
        this.repository = repository;
        this.processedEvents = processedEvents;
        this.jwtService = jwtService;
        this.publisher = publisher;
    }

    /** Resultado del procesamiento, para que el listener decida el ack y lo registre. */
    public enum Resultado { PROCESADO, DUPLICADO, IGNORADO, SIN_EFECTO }

    @Transactional
    public Resultado procesar(IncomingEvent evento) {
        if (evento == null || evento.id() == null || evento.type() == null) {
            log.warn("[AUTH] Evento descartado: envelope sin id o type");
            return Resultado.IGNORADO;
        }

        if (!esDeInteres(evento.type())) {
            log.debug("[AUTH] Evento ignorado | tipo='{}' no concierne a este servicio", evento.type());
            return Resultado.IGNORADO;
        }

        if (processedEvents.existsById(evento.id())) {
            log.warn("[AUTH] [DUPLICADO] Evento ya procesado | eventId='{}' | tipo='{}'",
                    evento.id(), evento.type());
            return Resultado.DUPLICADO;
        }

        String empleadoId = evento.empleadoId();
        if (empleadoId == null) {
            log.warn("[AUTH] Evento descartado: no trae empleadoId | eventId='{}' | tipo='{}'",
                    evento.id(), evento.type());
            processedEvents.save(new ProcessedEvent(evento.id(), evento.type()));
            return Resultado.IGNORADO;
        }

        log.info("[AUTH] [RECIBIDO] {} | eventId='{}' | empleado='{}'",
                evento.type(), evento.id(), empleadoId);

        Resultado resultado = switch (evento.type()) {
            case AuthEvents.EMPLEADO_CREADO -> altaDeCuenta(empleadoId, evento.email());
            case AuthEvents.EMPLEADO_RETIRADO -> bajaPermanente(empleadoId);
            case AuthEvents.VACACIONES_INICIADAS -> suspenderPorVacaciones(empleadoId);
            case AuthEvents.VACACIONES_FINALIZADAS -> reactivarTrasVacaciones(empleadoId);
            default -> Resultado.IGNORADO;
        };

        // Se marca como procesado incluso si no tuvo efecto: reprocesarlo tampoco lo tendría.
        processedEvents.save(new ProcessedEvent(evento.id(), evento.type()));
        return resultado;
    }

    private Resultado altaDeCuenta(String empleadoId, String email) {
        if (email == null) {
            log.warn("[AUTH] No se puede crear la cuenta sin email | empleado='{}'", empleadoId);
            return Resultado.IGNORADO;
        }

        Optional<UserAccount> existente = repository.findById(empleadoId);
        if (existente.isPresent()) {
            log.warn("[AUTH] La cuenta ya existía, no se recrea | empleado='{}'", empleadoId);
            return Resultado.SIN_EFECTO;
        }
        if (repository.existsByEmailIgnoreCase(email)) {
            log.warn("[AUTH] Ya hay una cuenta con el email '{}', no se crea | empleado='{}'",
                    email, empleadoId);
            return Resultado.SIN_EFECTO;
        }

        UserAccount cuenta = repository.save(UserAccount.pendienteDeActivacion(empleadoId, email));
        String tokenActivacion = jwtService.generarResetToken(cuenta);

        log.info("[AUTH] Cuenta creada en PENDIENTE_ACTIVACION con rol USER | empleado='{}'",
                empleadoId,
                kv("empleadoId", empleadoId), kv("estado", "PENDIENTE_ACTIVACION"),
                kv("rol", "USER"));
        publisher.publicarUsuarioCreado(cuenta, tokenActivacion);
        return Resultado.PROCESADO;
    }

    private Resultado bajaPermanente(String empleadoId) {
        Optional<UserAccount> encontrada = repository.findById(empleadoId);
        if (encontrada.isEmpty()) {
            log.warn("[AUTH] Retiro sobre cuenta inexistente | empleado='{}'", empleadoId);
            return Resultado.IGNORADO;
        }

        UserAccount cuenta = encontrada.get();
        if (cuenta.getEstado() == AccountStatus.DESACTIVADA_PERMANENTE) {
            log.info("[AUTH] La cuenta ya estaba desactivada permanentemente | empleado='{}'",
                    empleadoId);
            return Resultado.SIN_EFECTO;
        }

        cuenta.desactivarPermanentemente();
        repository.save(cuenta);

        log.info("[AUTH] Cuenta DESACTIVADA_PERMANENTE por retiro | empleado='{}'", empleadoId,
                kv("empleadoId", empleadoId), kv("estado", "DESACTIVADA_PERMANENTE"),
                kv("motivo", "retiro"));
        publisher.publicarCuentaDesactivada(cuenta, AuthEvents.MOTIVO_RETIRO, true);
        return Resultado.PROCESADO;
    }

    private Resultado suspenderPorVacaciones(String empleadoId) {
        Optional<UserAccount> encontrada = repository.findById(empleadoId);
        if (encontrada.isEmpty()) {
            log.warn("[AUTH] Inicio de vacaciones sobre cuenta inexistente | empleado='{}'",
                    empleadoId);
            return Resultado.IGNORADO;
        }

        UserAccount cuenta = encontrada.get();
        if (cuenta.getEstado() == AccountStatus.DESACTIVADA_PERMANENTE) {
            log.warn("[AUTH] No se suspende: la cuenta tiene baja permanente | empleado='{}'",
                    empleadoId);
            return Resultado.SIN_EFECTO;
        }
        if (cuenta.getEstado() == AccountStatus.SUSPENDIDA_TEMPORAL) {
            log.info("[AUTH] La cuenta ya estaba suspendida | empleado='{}'", empleadoId);
            return Resultado.SIN_EFECTO;
        }

        cuenta.suspenderTemporalmente();
        repository.save(cuenta);

        log.info("[AUTH] Cuenta SUSPENDIDA_TEMPORAL por vacaciones | empleado='{}'", empleadoId,
                kv("empleadoId", empleadoId), kv("estado", "SUSPENDIDA_TEMPORAL"),
                kv("motivo", "vacaciones"));
        publisher.publicarCuentaDesactivada(cuenta, AuthEvents.MOTIVO_VACACIONES, false);
        return Resultado.PROCESADO;
    }

    /**
     * Reactiva solo si la cuenta está suspendida temporalmente.
     *
     * <p>Aquí se resuelve el caso borde: una cuenta con baja permanente recibe este
     * evento y no se toca, por lo que el login del empleado retirado sigue fallando.
     */
    private Resultado reactivarTrasVacaciones(String empleadoId) {
        Optional<UserAccount> encontrada = repository.findById(empleadoId);
        if (encontrada.isEmpty()) {
            log.warn("[AUTH] Fin de vacaciones sobre cuenta inexistente | empleado='{}'",
                    empleadoId);
            return Resultado.IGNORADO;
        }

        UserAccount cuenta = encontrada.get();
        if (!cuenta.getEstado().esReactivablePorVacaciones()) {
            log.warn("[AUTH] NO se reactiva: estado '{}' no es reactivable por vacaciones "
                            + "(caso borde retiro durante vacaciones) | empleado='{}'",
                    cuenta.getEstado(), empleadoId);
            return Resultado.SIN_EFECTO;
        }

        cuenta.reactivar();
        repository.save(cuenta);

        log.info("[AUTH] Cuenta ACTIVA de nuevo tras vacaciones | empleado='{}'", empleadoId,
                kv("empleadoId", empleadoId), kv("estado", "ACTIVA"),
                kv("motivo", "fin de vacaciones"));
        publisher.publicarCuentaActivada(cuenta, AuthEvents.MOTIVO_FIN_VACACIONES);
        return Resultado.PROCESADO;
    }

    private static boolean esDeInteres(String tipo) {
        return AuthEvents.EMPLEADO_CREADO.equals(tipo)
                || AuthEvents.EMPLEADO_RETIRADO.equals(tipo)
                || AuthEvents.VACACIONES_INICIADAS.equals(tipo)
                || AuthEvents.VACACIONES_FINALIZADAS.equals(tipo);
    }
}
