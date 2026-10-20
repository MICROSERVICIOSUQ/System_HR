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

    @Test
    void recoverPasswordPublicaUsuarioRecuperacion() throws Exception {
        mockMvc.perform(post("/auth/recover-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", EMAIL))))
                .andExpect(status().isAccepted());

        verify(publisher).publicarUsuarioRecuperacion(any(), any());
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
