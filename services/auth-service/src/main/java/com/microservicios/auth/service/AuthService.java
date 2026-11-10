package com.microservicios.auth.service;

import com.microservicios.auth.dto.AuthDtos.LoginResponse;
import com.microservicios.auth.events.AuthEvents;
import com.microservicios.auth.exception.AuthExceptions.CredencialesInvalidasException;
import com.microservicios.auth.exception.AuthExceptions.CuentaNoEncontradaException;
import com.microservicios.auth.exception.AuthExceptions.CuentaNoHabilitadaException;

import static net.logstash.logback.argument.StructuredArguments.kv;
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
            log.warn("[AUTH] Login rechazado por credenciales | usuario='{}'", usuario,
                    kv("usuario", usuario), kv("resultado", "credenciales invalidas"));
            throw new CredencialesInvalidasException();
        }

        if (!cuenta.getEstado().permiteLogin()) {
            log.warn("[AUTH] Login rechazado por estado '{}' | empleado='{}'",
                    cuenta.getEstado(), cuenta.getEmpleadoId(),
                    kv("empleadoId", cuenta.getEmpleadoId()),
                    kv("estado", String.valueOf(cuenta.getEstado())),
                    kv("resultado", "cuenta no habilitada"));
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
    @Transactional
    public void recuperarPassword(String email) {
        UserAccount cuenta = repository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new CuentaNoEncontradaException(email));

        // Solo una cuenta ACTIVA puede recuperar su contraseña.
        //
        // Antes bastaba con no estar dada de baja permanente, lo que dejaba dos
        // puertas abiertas que no deberían estarlo:
        //
        //   PENDIENTE_ACTIVACION — "recuperar" implica haber tenido una
        //     contraseña, y esta cuenta nunca la tuvo. Su token de activación ya
        //     viajó en usuario.creado; usar la recuperación como vía alterna
        //     para pedir otro convierte un endpoint público en un generador de
        //     tokens de activación a demanda, y basta conocer el correo.
        //
        //   SUSPENDIDA_TEMPORAL — el empleado está de vacaciones. Podría fijar
        //     una contraseña nueva y aun así no poder entrar, porque el login
        //     comprueba el estado. Darle un token sería prometerle un acceso que
        //     no va a tener.
        //
        // El mensaje se mantiene específico por estado para que el dueño de la
        // cuenta entienda qué hacer. No filtra nada que no sepa ya: para llegar
        // aquí el correo tuvo que existir, y eso lo delata el 404 de la línea
        // anterior, que es una decisión previa del servicio.
        if (cuenta.getEstado() != AccountStatus.ACTIVA) {
            // Una ráfaga de estos sobre cuentas PENDIENTE_ACTIVACION es el
            // intento de usar la puerta lateral que esta restricción cerró, así
            // que conviene poder contarlos por estado en Loki.
            log.warn("[AUTH] Recuperación rechazada por estado '{}' | empleado='{}'",
                    cuenta.getEstado(), cuenta.getEmpleadoId(),
                    kv("empleadoId", cuenta.getEmpleadoId()),
                    kv("estado", String.valueOf(cuenta.getEstado())),
                    kv("resultado", "recuperacion rechazada"));
            throw new CuentaNoHabilitadaException(explicarRecuperacion(cuenta.getEstado()));
        }

        String token = jwtService.generarResetToken(cuenta);
        publisher.publicarUsuarioRecuperacion(cuenta, token);
        log.info("[AUTH] Token de recuperación emitido | empleado='{}'", cuenta.getEmpleadoId(),
                kv("empleadoId", cuenta.getEmpleadoId()), kv("resultado", "token emitido"));
    }

    /**
     * Emite un token de restablecimiento para una cuenta, a pedido de un ADMIN.
     *
     * <p><strong>Por qué existe.</strong> El token de activación viaja en el
     * evento {@code usuario.creado} y notifications-service lo usaba para
     * componer el correo. Antes también lo guardaba en su historial, de modo que
     * {@code GET /notificaciones} lo devolvía y de ahí lo leían la suite BDD y
     * cuatro colecciones de Postman. Al sanear el payload —que es correcto: un
     * token no debe quedar en texto plano en una base de datos ni en un log— esas
     * pruebas se quedaron sin forma de activar una cuenta.
     *
     * <p>Este endpoint es el canal legítimo: el token lo entrega quien lo firma,
     * no el historial de correos de un tercero. Las pruebas dejan de depender de
     * notifications-service, que es un detalle de implementación del envío.
     *
     * <p><strong>Es una operación privilegiada.</strong> Equivale a poder tomar
     * el control de cualquier cuenta, así que el gateway solo la permite al rol
     * ADMIN —es un POST, y la regla de propiedad únicamente concede a un USER su
     * propio perfil y su propia contraseña— y cada emisión se registra con el
     * administrador que la pidió. No se emite para una cuenta con baja
     * permanente, la misma regla que aplica {@link #resetPassword}.
     *
     * @param empleadoId cuenta para la que se emite el token
     * @param solicitante identidad del administrador, para la traza de auditoría
     * @return el token y los segundos que le quedan de vida
     */
    @Transactional(readOnly = true)
    public TokenEmitido emitirTokenDeRestablecimiento(String empleadoId, String solicitante) {
        UserAccount cuenta = repository.findById(empleadoId)
                .orElseThrow(() -> new CuentaNoEncontradaException(empleadoId));

        if (cuenta.getEstado() == AccountStatus.DESACTIVADA_PERMANENTE) {
            log.warn("[AUTH] Emisión de token rechazada por baja permanente | empleado='{}'",
                    empleadoId,
                    kv("empleadoId", empleadoId), kv("solicitante", solicitante),
                    kv("estado", String.valueOf(cuenta.getEstado())),
                    kv("resultado", "rechazada"));
            throw new CuentaNoHabilitadaException("La cuenta está desactivada de forma permanente");
        }

        String token = jwtService.generarResetToken(cuenta);

        // WARN y no INFO a propósito: no es tráfico normal. Un administrador
        // emitiendo tokens de otras cuentas es justo lo que una auditoría querría
        // ver, y en WARN queda visible sin tener que buscarlo.
        log.warn("[AUTH] Token de restablecimiento emitido por un administrador | empleado='{}' | admin='{}'",
                empleadoId, solicitante,
                kv("empleadoId", empleadoId), kv("solicitante", solicitante),
                kv("estado", String.valueOf(cuenta.getEstado())),
                kv("resultado", "token emitido"));

        return new TokenEmitido(empleadoId, token, jwtService.getResetTtlSeconds());
    }

    /** Resultado de {@link #emitirTokenDeRestablecimiento}. */
    public record TokenEmitido(String empleadoId, String token, long expiraEnSegundos) {}

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

    /**
     * Por qué se rechaza la recuperación, según el estado de la cuenta.
     *
     * <p>Separado de {@link #explicar}, que explica por qué falla un login: el
     * motivo coincide en los dos casos extremos, pero no en
     * {@code PENDIENTE_ACTIVACION}. Allí el login responde «credenciales
     * inválidas» —a propósito, para no revelar que el correo está registrado
     * antes de verificar la contraseña— mientras que aquí el correo ya quedó
     * confirmado por el 404 previo, y lo útil es decirle al dueño que su cuenta
     * se activa con el token que recibió, no con una recuperación.
     */
    private static String explicarRecuperacion(AccountStatus estado) {
        return switch (estado) {
            case PENDIENTE_ACTIVACION ->
                    "La cuenta aún no ha sido activada: establezca su contraseña con el token "
                            + "de activación que recibió, no con una recuperación";
            case SUSPENDIDA_TEMPORAL ->
                    "La cuenta está suspendida temporalmente porque el empleado se encuentra "
                            + "de vacaciones";
            case DESACTIVADA_PERMANENTE ->
                    "La cuenta está desactivada de forma permanente";
            case ACTIVA -> "La cuenta está activa";
        };
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
