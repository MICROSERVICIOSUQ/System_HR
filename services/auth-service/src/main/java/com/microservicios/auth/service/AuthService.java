package com.microservicios.auth.service;

import com.microservicios.auth.dto.AuthDtos.LoginResponse;
import com.microservicios.auth.events.AuthEvents;
import com.microservicios.auth.exception.AuthExceptions.CredencialesInvalidasException;
import com.microservicios.auth.exception.AuthExceptions.CuentaNoEncontradaException;
import com.microservicios.auth.exception.AuthExceptions.CuentaNoHabilitadaException;
import com.microservicios.auth.messaging.AuthEventPublisher;
import com.microservicios.auth.model.AccountStatus;
import com.microservicios.auth.model.UserAccount;
import com.microservicios.auth.repository.UserAccountRepository;
import com.microservicios.auth.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Casos de uso de los cuatro endpoints de autenticación.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserAccountRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final JwtService jwtService;
    private final AuthEventPublisher publisher;

    public AuthService(UserAccountRepository repository,
                       PasswordEncoder passwordEncoder,
                       PasswordPolicy passwordPolicy,
                       JwtService jwtService,
                       AuthEventPublisher publisher) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.jwtService = jwtService;
        this.publisher = publisher;
    }

    /**
     * Verifica credenciales y emite el Access JWT.
     *
     * <p>El identificador admite el email o el {@code empleadoId}: el enunciado
     * habla de "usuario y contraseña" y el flujo de prueba usa el correo.
     *
     * <p>Una cuenta suspendida por vacaciones, pendiente de activación o retirada
     * falla con 401 aunque la contraseña sea correcta. Ese es el mecanismo por el
     * que el login deja de funcionar durante las vacaciones.
     */
    @Transactional(readOnly = true)
    public LoginResponse login(String usuario, String password) {
        UserAccount cuenta = buscarPorEmailOId(usuario)
                .orElseThrow(CredencialesInvalidasException::new);

        if (cuenta.getPasswordHash() == null
                || !passwordEncoder.matches(password, cuenta.getPasswordHash())) {
            log.warn("[AUTH] Login rechazado por credenciales | usuario='{}'", usuario);
            throw new CredencialesInvalidasException();
        }

        if (!cuenta.getEstado().permiteLogin()) {
            log.warn("[AUTH] Login rechazado por estado '{}' | empleado='{}'",
                    cuenta.getEstado(), cuenta.getEmpleadoId());
            throw new CuentaNoHabilitadaException(explicar(cuenta.getEstado()));
        }

        String token = jwtService.generarAccessToken(cuenta);
        log.info("[AUTH] Login correcto | empleado='{}' | rol='{}'",
                cuenta.getEmpleadoId(), cuenta.getRole());

        return LoginResponse.de(token, jwtService.getAccessTtlSeconds(),
                cuenta.getEmpleadoId(), cuenta.getRole().name());
    }

    /**
     * Inicia la recuperación: genera un token de vida corta y publica
     * {@code usuario.recuperacion} para que notificaciones simule el correo.
     *
     * <p>Una cuenta con baja permanente no recibe token: permitirlo sería una vía
     * para que un empleado retirado recupere el acceso.
     */
    @Transactional(readOnly = true)
    public void recuperarPassword(String email) {
        UserAccount cuenta = repository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new CuentaNoEncontradaException(email));

        if (cuenta.getEstado() == AccountStatus.DESACTIVADA_PERMANENTE) {
            log.warn("[AUTH] Recuperación rechazada: cuenta con baja permanente | empleado='{}'",
                    cuenta.getEmpleadoId());
            throw new CuentaNoHabilitadaException(
                    "La cuenta está desactivada de forma permanente");
        }

        String token = jwtService.generarResetToken(cuenta);
        publisher.publicarUsuarioRecuperacion(cuenta, token);
        log.info("[AUTH] Token de recuperación emitido | empleado='{}'", cuenta.getEmpleadoId());
    }

    /**
     * Establece la contraseña a partir de un token de activación o recuperación.
     *
     * <p>Si la cuenta estaba {@link AccountStatus#PENDIENTE_ACTIVACION} queda
     * {@link AccountStatus#ACTIVA} y se publica {@code cuenta.activada}: es el
     * "establecimiento inicial de contraseña" del catálogo.
     *
     * <p>Una cuenta con baja permanente no se reactiva por este camino.
     */
    @Transactional
    public void resetPassword(String token, String nuevaPassword) {
        String empleadoId = jwtService.validarResetTokenYObtenerSujeto(token);

        UserAccount cuenta = repository.findById(empleadoId)
                .orElseThrow(() -> new CuentaNoEncontradaException(empleadoId));

        if (cuenta.getEstado() == AccountStatus.DESACTIVADA_PERMANENTE) {
            throw new CuentaNoHabilitadaException(
                    "La cuenta está desactivada de forma permanente");
        }

        passwordPolicy.validar(nuevaPassword);

        boolean eraPrimeraVez = cuenta.getEstado() == AccountStatus.PENDIENTE_ACTIVACION;
        cuenta.establecerPassword(passwordEncoder.encode(nuevaPassword));
        repository.save(cuenta);

        log.info("[AUTH] Contraseña establecida | empleado='{}' | activacionInicial={}",
                empleadoId, eraPrimeraVez);

        if (eraPrimeraVez) {
            publisher.publicarCuentaActivada(cuenta, AuthEvents.MOTIVO_ACTIVACION_INICIAL);
        }
    }

    /**
     * Cambia la contraseña de un usuario autenticado.
     *
     * @param empleadoId claim {@code sub} que el api-gateway propagó tras validar
     *                   el JWT; el usuario solo puede cambiar su propia contraseña
     */
    @Transactional
    public void cambiarPassword(String empleadoId, String passwordActual, String nuevaPassword) {
        UserAccount cuenta = repository.findById(empleadoId)
                .orElseThrow(() -> new CuentaNoEncontradaException(empleadoId));

        if (!cuenta.puedeAutenticarse()
                || !passwordEncoder.matches(passwordActual, cuenta.getPasswordHash())) {
            log.warn("[AUTH] Cambio de contraseña rechazado | empleado='{}'", empleadoId);
            throw new CredencialesInvalidasException();
        }

        passwordPolicy.validar(nuevaPassword);
        cuenta.establecerPassword(passwordEncoder.encode(nuevaPassword));
        repository.save(cuenta);

        log.info("[AUTH] Contraseña cambiada por el propio usuario | empleado='{}'", empleadoId);
    }

    private Optional<UserAccount> buscarPorEmailOId(String identificador) {
        Optional<UserAccount> porEmail = repository.findByEmailIgnoreCase(identificador);
        return porEmail.isPresent() ? porEmail : repository.findById(identificador);
    }

    private static String explicar(AccountStatus estado) {
        return switch (estado) {
            case PENDIENTE_ACTIVACION ->
                    "La cuenta aún no ha sido activada: establezca su contraseña con el token recibido";
            case SUSPENDIDA_TEMPORAL ->
                    "La cuenta está suspendida temporalmente porque el empleado se encuentra de vacaciones";
            case DESACTIVADA_PERMANENTE ->
                    "La cuenta está desactivada de forma permanente";
            case ACTIVA -> "La cuenta está activa";
        };
    }
}
