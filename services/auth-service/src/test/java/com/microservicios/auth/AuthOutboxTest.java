package com.microservicios.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservicios.auth.events.AuthEvents;
import com.microservicios.auth.events.EventEnvelope;
import com.microservicios.auth.events.IncomingEvent;
import com.microservicios.auth.messaging.*;
import com.microservicios.auth.repository.ProcessedEventRepository;
import com.microservicios.auth.repository.UserAccountRepository;
import com.microservicios.auth.service.AccountLifecycleService;
import com.microservicios.auth.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability(tracing = false)
@SpringBootTest(properties = {"outbox.enabled=true", "outbox.retraso-inicial-ms=600000",
        "spring.datasource.url=jdbc:h2:mem:auth-outbox;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
        "management.endpoints.web.exposure.include=health,prometheus"})
@AutoConfigureMockMvc
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class AuthOutboxTest {
    @Autowired AccountLifecycleService lifecycle;
    @Autowired AuthService auth;
    @Autowired OutboxRepository outbox;
    @Autowired PublicadorDeOutbox worker;
    @Autowired OutboxCipher cipher;
    @Autowired ObjectMapper mapper;
    @Autowired UserAccountRepository accounts;
    @Autowired ProcessedEventRepository processed;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc http;
    @MockBean RabbitTemplate rabbit;
    @Value("${jwt.secret}") String jwtSecret;

    @BeforeEach
    void clean() {
        outbox.deleteAll();
        processed.deleteAll();
        accounts.findAll().stream().filter(account -> !account.getEmpleadoId().equals("admin"))
                .forEach(accounts::delete);
    }

    IncomingEvent created() {
        return new IncomingEvent(UUID.randomUUID().toString(), AuthEvents.EMPLEADO_CREADO, "1.0",
                "2026-11-09T10:00:00Z", "test", Map.of("id", "OUTBOX-1", "email", "outbox@example.test"));
    }

    @Test
    void cuentaEventoConsumidoYEventoSalienteSeGuardanSinLlamarAlBroker() throws Exception {
        IncomingEvent input = created();
        lifecycle.procesar(input);
        assertThat(accounts.existsById("OUTBOX-1")).isTrue();
        assertThat(processed.existsById(input.id())).isTrue();
        OutboxEvento event = outbox.findAll().getFirst();
        var envelope = mapper.readTree(cipher.decrypt(event.getPayload()));
        assertThat(envelope.path("type").asText()).isEqualTo("usuario.creado");
        String token = envelope.path("data").path("token").asText();
        assertThat(token).isNotBlank();
        assertThat(event.getPayload()).doesNotContain(token).doesNotContain("outbox@example.test");
        verifyNoInteractions(rabbit);
    }

    @Test
    void rollbackRevierteCuentaMarcadorYOutboxJuntos() {
        IncomingEvent input = created();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            lifecycle.procesar(input);
            throw new IllegalStateException("Fallo de la operación de negocio");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(accounts.existsById("OUTBOX-1")).isFalse();
        assertThat(processed.existsById(input.id())).isFalse();
        assertThat(outbox.count()).isZero();
    }

    @Test
    void brokerCaidoDejaPendienteYReintentaElMismoIdHastaConfirmar() throws Exception {
        lifecycle.procesar(created());
        String id = outbox.findAll().getFirst().getId();
        doThrow(new AmqpException("Broker no disponible")).when(rabbit)
                .convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));
        worker.publicarPendientes();
        assertThat(outbox.findById(id).orElseThrow().getPublicadoEn()).isNull();
        assertThat(outbox.findById(id).orElseThrow().getIntentos()).isEqualTo(1);
        reset(rabbit);
        doAnswer(call -> {
            EventEnvelope<?> envelope = call.getArgument(2);
            assertThat(envelope.id()).isEqualTo(id);
            CorrelationData confirmation = call.getArgument(3);
            confirmation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));
        worker.publicarPendientes();
        assertThat(outbox.countByPublicadoEnIsNull()).isZero();
        assertThat(outbox.findById(id).orElseThrow().getPayload()).isEmpty();
        verify(rabbit, times(1)).convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));
    }

    @Test
    void nackDelBrokerNoMarcaElEventoComoPublicado() {
        lifecycle.procesar(created());
        doAnswer(call -> {
            CorrelationData confirmation = call.getArgument(3);
            confirmation.getFuture().complete(new CorrelationData.Confirm(false, "NACK"));
            return null;
        }).when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));
        worker.publicarPendientes();
        assertThat(outbox.countByPublicadoEnIsNull()).isEqualTo(1);
        assertThat(outbox.findAll().getFirst().getIntentos()).isEqualTo(1);
    }

    @Test
    void recuperacionPuedeGuardarSuEventoEnLaTransaccion() {
        // El administrador semilla ya es una cuenta activa.
        auth.recuperarPassword("admin@empresa.com");
        assertThat(outbox.findAll()).singleElement().extracting(OutboxEvento::getTipo)
                .isEqualTo("usuario.recuperacion");
    }

    @Test
    void metricsYLaRutaAnteriorSiguenSirviendoPrometheus() throws Exception {
        for (String path : new String[]{"/metrics", "/actuator/prometheus"}) {
            http.perform(get(path)).andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("# HELP")));
        }
    }

    @Test
    void cifradoRechazaOtroSecretoYUnPayloadAlterado() throws Exception {
        String encrypted = cipher.encrypt("token-de-prueba");
        assertThat(encrypted).startsWith("v1:gcm:");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("token-de-prueba");
        assertThatThrownBy(() -> new OutboxCipher("otro-secreto-con-mas-de-treinta-y-dos-bytes").decrypt(encrypted))
                .isInstanceOf(java.security.GeneralSecurityException.class);
        byte[] bytes = java.util.Base64.getDecoder().decode(encrypted.substring("v1:gcm:".length()));
        bytes[bytes.length - 1] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt("v1:gcm:" + java.util.Base64.getEncoder().encodeToString(bytes)))
                .isInstanceOf(java.security.GeneralSecurityException.class);
    }

    @Test
    void cifradoPuedeLeerPayloadsHeredadosSinPrefijo() throws Exception {
        byte[] nonce = new byte[12];
        new java.security.SecureRandom().nextBytes(nonce);
        byte[] material = java.security.MessageDigest.getInstance("SHA-256")
                .digest(("system-hr:auth-outbox:v1:" + jwtSecret)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var key = new javax.crypto.spec.SecretKeySpec(material, "AES");
        var aes = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(javax.crypto.Cipher.ENCRYPT_MODE, key,
                new javax.crypto.spec.GCMParameterSpec(128, nonce));
        byte[] ciphertext = aes.doFinal("legacy-token".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] payload = java.nio.ByteBuffer.allocate(nonce.length + ciphertext.length)
                .put(nonce).put(ciphertext).array();

        assertThat(cipher.decrypt(java.util.Base64.getEncoder().encodeToString(payload)))
                .isEqualTo("legacy-token");
    }
}
