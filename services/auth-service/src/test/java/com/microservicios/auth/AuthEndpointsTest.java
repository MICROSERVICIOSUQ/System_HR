package com.microservicios.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservicios.auth.controller.AuthController;
import com.microservicios.auth.events.AuthEvents;
import com.microservicios.auth.events.IncomingEvent;
import com.microservicios.auth.messaging.AuthEventPublisher;
import com.microservicios.auth.repository.ProcessedEventRepository;
import com.microservicios.auth.repository.UserAccountRepository;
import com.microservicios.auth.security.JwtService;
import com.microservicios.auth.service.AccountLifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP de los cuatro endpoints de autenticación.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthEndpointsTest {

    private static final String EMPLEADO = "E001";
    private static final String EMAIL = "juan.perez@empresa.com";
    private static final String PASSWORD = "Secreta123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountLifecycleService lifecycle;

    @Autowired
    private UserAccountRepository cuentas;

    @Autowired
    private ProcessedEventRepository eventosProcesados;

    @Autowired
    private JwtService jwtService;

    /** El secreto y el emisor del servicio, para forjar tokens de prueba. */
    @org.springframework.beans.factory.annotation.Value("${jwt.secret}")
    private String secretoDePruebas;

    @org.springframework.beans.factory.annotation.Value("${jwt.issuer}")
    private String emisorDePruebas;

    @MockBean
    private AuthEventPublisher publisher;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void prepararCuentaPendiente() {
        eventosProcesados.deleteAll();
        // Se borra solo la cuenta del empleado de prueba: el administrador
        // semilla lo crea el ApplicationRunner al arrancar el contexto y hay un
        // test que comprueba precisamente que puede iniciar sesión.
        cuentas.findById(EMPLEADO).ifPresent(cuentas::delete);
        lifecycle.procesar(new IncomingEvent(UUID.randomUUID().toString(),
                AuthEvents.EMPLEADO_CREADO, "1.0", "2026-10-20T10:00:00Z", "test",
                Map.of("id", EMPLEADO, "email", EMAIL)));
    }

    private String tokenDeActivacion() {
        return jwtService.generarResetToken(cuentas.findById(EMPLEADO).orElseThrow());
    }

    private void establecerPassword(String password) throws Exception {
        mockMvc.perform(post("/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "token", tokenDeActivacion(),
                                "nuevaPassword", password))))
                .andExpect(status().isOk());
    }

    @Test
    void loginFallaMientrasLaCuentaSigaPendienteDeActivacion() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "usuario", EMAIL, "password", PASSWORD))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void resetPasswordActivaLaCuentaYPublicaCuentaActivada() throws Exception {
        establecerPassword(PASSWORD);

        assertThat(cuentas.findById(EMPLEADO).orElseThrow().puedeAutenticarse()).isTrue();
        verify(publisher).publicarCuentaActivada(any(),
                eq(AuthEvents.MOTIVO_ACTIVACION_INICIAL));
    }

    @Test
    void resetPasswordSeRechazaSiLaCuentaFueSuspendidaDespuesDeEmitirElToken() throws Exception {
        establecerPassword(PASSWORD);
        String tokenEmitido = jwtService.generarResetToken(
                cuentas.findById(EMPLEADO).orElseThrow());

        lifecycle.procesar(new IncomingEvent(UUID.randomUUID().toString(),
                AuthEvents.VACACIONES_INICIADAS, "1.0", "2026-11-08T10:00:00Z", "test",
                Map.of("id", "periodo", "empleadoId", EMPLEADO)));

        mockMvc.perform(post("/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "token", tokenEmitido,
                                "nuevaPassword", "Nueva4567"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value("La cuenta no está activa y no puede restablecer su contraseña"));
    }

    @Test
    void loginDevuelveUnJwtConSubYRoleTrasEstablecerLaPassword() throws Exception {
        establecerPassword(PASSWORD);

        String cuerpo = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "usuario", EMAIL, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tipo").value("Bearer"))
                .andExpect(jsonPath("$.empleadoId").value(EMPLEADO))
                .andExpect(jsonPath("$.rol").value("USER"))
                .andReturn().getResponse().getContentAsString();

        String token = mapper.readTree(cuerpo).get("token").asText();
        assertThat(token.split("\\.")).as("un JWT tiene tres segmentos").hasSize(3);

        JsonNode header = mapper.readTree(java.util.Base64.getUrlDecoder()
                .decode(token.split("\\.")[0]));
        assertThat(header.get("alg").asText())
                .as("HS256 explícito, no el que elija la librería por el largo de la clave")
                .isEqualTo("HS256");
        assertThat(header.get("typ").asText())
                .as("el typ de la cabecera JOSE debe valer JWT (RFC 7519)")
                .isEqualTo("JWT");

        JsonNode payload = mapper.readTree(java.util.Base64.getUrlDecoder()
                .decode(token.split("\\.")[1]));
        assertThat(payload.get("sub").asText()).isEqualTo(EMPLEADO);
        assertThat(payload.get("role").asText()).isEqualTo("USER");
        assertThat(payload.hasNonNull("iat")).isTrue();
        assertThat(payload.hasNonNull("exp")).isTrue();
        assertThat(payload.has("password"))
                .as("la contraseña nunca viaja en el token")
                .isFalse();
    }

    @Test
    void laPasswordSeGuardaHasheadaYNoEnClaro() throws Exception {
        establecerPassword(PASSWORD);

        String hash = cuentas.findById(EMPLEADO).orElseThrow().getPasswordHash();
        assertThat(hash).isNotEqualTo(PASSWORD);
        assertThat(hash).startsWith("$2");
    }

    @Test
    void loginConPasswordIncorrectaDevuelve401() throws Exception {
        establecerPassword(PASSWORD);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "usuario", EMAIL, "password", "OtraCosa123"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Credenciales inválidas"));
    }

    @Test
    void unaPasswordDebilSeRechazaCon400() throws Exception {
        mockMvc.perform(post("/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "token", tokenDeActivacion(),
                                "nuevaPassword", "corta"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("La contraseña debe tener al menos 8 caracteres"));
    }

    @Test
    void unAccessTokenNoSirveComoTokenDeReset() throws Exception {
        establecerPassword(PASSWORD);
        String accessToken = jwtService.generarAccessToken(
                cuentas.findById(EMPLEADO).orElseThrow());

        mockMvc.perform(post("/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "token", accessToken,
                                "nuevaPassword", "Nueva456"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message")
                        .value("El token no es de restablecimiento de contraseña"));
    }

    /**
     * El {@code typ} de la cabecera no distingue el propósito del token —para eso
     * está el claim {@code type}—, pero debe estar presente y valer {@code JWT} en
     * los dos tipos, como pide el RFC 7519.
     */
    @Test
    void losDosTiposDeTokenDeclaranTypJwtEnLaCabecera() throws Exception {
        establecerPassword(PASSWORD);
        var cuenta = cuentas.findById(EMPLEADO).orElseThrow();

        for (String token : java.util.List.of(
                jwtService.generarAccessToken(cuenta),
                jwtService.generarResetToken(cuenta))) {

            JsonNode header = mapper.readTree(java.util.Base64.getUrlDecoder()
                    .decode(token.split("\\.")[0]));
            assertThat(header.get("typ").asText()).isEqualTo("JWT");
            assertThat(header.get("alg").asText()).isEqualTo("HS256");
        }
    }

    /**
     * Un token sin {@code typ} en la cabecera se rechaza aunque la firma sea
     * legítima: la cabecera entra en el cálculo de la firma, así que no se puede
     * quitar sin invalidar el token, y exigirla cierra la puerta a un JWS que no
     * sea un JWT.
     */
    @Test
    void unTokenSinTypEnLaCabeceraSeRechaza() throws Exception {
        String sinTyp = io.jsonwebtoken.Jwts.builder()
                .issuer(emisorDePruebas)
                .subject(EMPLEADO)
                .claim(JwtService.CLAIM_TYPE, JwtService.TYPE_RESET_PASSWORD)
                .expiration(new java.util.Date(System.currentTimeMillis() + 60_000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        secretoDePruebas.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        io.jsonwebtoken.Jwts.SIG.HS256)
                .compact();

        mockMvc.perform(post("/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "token", sinTyp,
                                "nuevaPassword", "Nueva456"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message")
                        .value("La cabecera del token no declara typ=JWT"));
    }

    @Test
    void recoverPasswordPublicaUsuarioRecuperacion() throws Exception {
        // La cuenta debe estar ACTIVA para poder recuperar su contraseña, así
        // que primero se activa. Antes esta prueba pasaba sobre una cuenta en
        // PENDIENTE_ACTIVACION, que es justo el caso que ahora se rechaza.
        establecerPassword(PASSWORD);

        mockMvc.perform(post("/auth/recover-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", EMAIL))))
                .andExpect(status().isAccepted());

        verify(publisher).publicarUsuarioRecuperacion(any(), any());
    }

    /**
     * Una cuenta sin activar no puede "recuperar" una contraseña que nunca tuvo.
     *
     * <p>Sin esta restricción, el endpoint público de recuperación servía como
     * generador de tokens de activación a demanda: bastaba conocer el correo de
     * un empleado recién registrado para pedir uno. El token de activación ya
     * viajó en {@code usuario.creado}, y ese es su único canal de entrega.
     */
    @Test
    void recoverPasswordSeRechazaSiLaCuentaNoEstaActivada() throws Exception {
        mockMvc.perform(post("/auth/recover-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", EMAIL))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("aún no ha sido activada")));

        verify(publisher, never()).publicarUsuarioRecuperacion(any(), any());
    }

    /**
     * Durante las vacaciones tampoco: la cuenta está suspendida y el login la
     * rechazaría igual, así que entregar un token sería prometer un acceso que
     * no va a funcionar.
     */
    @Test
    void recoverPasswordSeRechazaSiLaCuentaEstaSuspendidaPorVacaciones() throws Exception {
        establecerPassword(PASSWORD);

        lifecycle.procesar(new IncomingEvent(UUID.randomUUID().toString(),
                AuthEvents.VACACIONES_INICIADAS, "1.0", "2026-11-08T10:00:00Z", "test",
                Map.of("id", "periodo", "empleadoId", EMPLEADO)));

        mockMvc.perform(post("/auth/recover-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", EMAIL))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("vacaciones")));

        verify(publisher, never()).publicarUsuarioRecuperacion(any(), any());
    }

    // ── Emisión de tokens por un administrador ──────────────────────────────

    /**
     * El canal legítimo para obtener un token de activación.
     *
     * <p>Lo necesitan la suite BDD y las colecciones de Postman desde que
     * notifications-service dejó de guardar el token en su historial. Antes lo
     * leían de {@code GET /notificaciones}, que es el sitio donde no debe estar.
     */
    @Test
    void emiteUnTokenDeRestablecimientoUtilizableParaActivarLaCuenta() throws Exception {
        String token = mapper.readTree(
                mockMvc.perform(post("/auth/internal/tokens/restablecimiento")
                                .header(AuthController.HEADER_USUARIO, "admin")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(Map.of("empleadoId", EMPLEADO))))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.empleadoId").value(EMPLEADO))
                        .andExpect(jsonPath("$.tipo").value("RESET_PASSWORD"))
                        .andExpect(jsonPath("$.expiraEnSegundos").isNumber())
                        .andReturn().getResponse().getContentAsString()
        ).get("token").asText();

        // Que el token sirva es la prueba de verdad: lo mismo que haría la suite
        // BDD a continuación.
        mockMvc.perform(post("/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "token", token, "nuevaPassword", "Secreta123"))))
                .andExpect(status().isOk());
    }

    @Test
    void emitirTokenExigeLaIdentidadDelSolicitante() throws Exception {
        // Sin la cabecera que propaga el gateway no se emite: un token
        // privilegiado sin solicitante conocido no se puede auditar.
        mockMvc.perform(post("/auth/internal/tokens/restablecimiento")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("empleadoId", EMPLEADO))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void emitirTokenDeUnaCuentaInexistenteDevuelve404() throws Exception {
        mockMvc.perform(post("/auth/internal/tokens/restablecimiento")
                        .header(AuthController.HEADER_USUARIO, "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("empleadoId", "NO-EXISTE"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void noSeEmiteTokenParaUnaCuentaConBajaPermanente() throws Exception {
        lifecycle.procesar(new IncomingEvent(UUID.randomUUID().toString(),
                AuthEvents.EMPLEADO_RETIRADO, "1.0", "2026-11-09T10:00:00Z", "test",
                Map.of("id", EMPLEADO)));

        mockMvc.perform(post("/auth/internal/tokens/restablecimiento")
                        .header(AuthController.HEADER_USUARIO, "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("empleadoId", EMPLEADO))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("permanente")));
    }

    @Test
    void recoverPasswordConEmailDesconocidoDevuelve404() throws Exception {
        mockMvc.perform(post("/auth/recover-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", "nadie@empresa.com"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void changePasswordCambiaLaCredencialYInvalidaLaAnterior() throws Exception {
        establecerPassword(PASSWORD);

        mockMvc.perform(post("/auth/change-password")
                        .header(AuthController.HEADER_USUARIO, EMPLEADO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "passwordActual", PASSWORD,
                                "nuevaPassword", "Nueva4567"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "usuario", EMAIL, "password", PASSWORD))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "usuario", EMAIL, "password", "Nueva4567"))))
                .andExpect(status().isOk());
    }

    @Test
    void changePasswordSinIdentidadPropagadaDevuelve401() throws Exception {
        establecerPassword(PASSWORD);

        mockMvc.perform(post("/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "passwordActual", PASSWORD,
                                "nuevaPassword", "Nueva4567"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("La petición no llegó autenticada"));
    }

    @Test
    void loginFallaTrasSuspenderLaCuentaPorVacaciones() throws Exception {
        establecerPassword(PASSWORD);

        lifecycle.procesar(new IncomingEvent(UUID.randomUUID().toString(),
                AuthEvents.VACACIONES_INICIADAS, "1.0", "2026-10-20T10:00:00Z", "test",
                Map.of("id", "periodo", "empleadoId", EMPLEADO)));

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "usuario", EMAIL, "password", PASSWORD))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(
                        "La cuenta está suspendida temporalmente porque el empleado se encuentra de vacaciones"));
    }

    @Test
    void elAdministradorSemillaPuedeIniciarSesion() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "usuario", "admin@empresa.com", "password", "Admin12345"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rol").value("ADMIN"))
                .andExpect(jsonPath("$.empleadoId").value("admin"));
    }
}
