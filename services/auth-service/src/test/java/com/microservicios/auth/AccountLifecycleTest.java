package com.microservicios.auth;

import com.microservicios.auth.events.AuthEvents;
import com.microservicios.auth.events.IncomingEvent;
import com.microservicios.auth.messaging.AuthEventPublisher;
import com.microservicios.auth.model.AccountStatus;
import com.microservicios.auth.model.Role;
import com.microservicios.auth.model.UserAccount;
import com.microservicios.auth.repository.ProcessedEventRepository;
import com.microservicios.auth.repository.UserAccountRepository;
import com.microservicios.auth.service.AccountLifecycleService;
import com.microservicios.auth.service.AccountLifecycleService.Resultado;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Ciclo de vida de la cuenta dirigido por eventos, incluido el caso borde del
 * retiro durante las vacaciones que el enunciado evalúa de forma explícita.
 */
@SpringBootTest
class AccountLifecycleTest {

    private static final String EMPLEADO = "E001";
    private static final String EMAIL = "juan.perez@empresa.com";

    @Autowired
    private AccountLifecycleService lifecycle;

    @Autowired
    private UserAccountRepository cuentas;

    @Autowired
    private ProcessedEventRepository eventosProcesados;

    @MockBean
    private AuthEventPublisher publisher;

    @BeforeEach
    void limpiar() {
        eventosProcesados.deleteAll();
        // Solo la cuenta de prueba: el administrador semilla del contexto se conserva.
        cuentas.findById(EMPLEADO).ifPresent(cuentas::delete);
    }

    private static IncomingEvent evento(String tipo, Map<String, Object> data) {
        return new IncomingEvent(UUID.randomUUID().toString(), tipo, "1.0",
                "2026-10-20T10:00:00Z", "test", data);
    }

    private static IncomingEvent empleadoCreado() {
        return evento(AuthEvents.EMPLEADO_CREADO, Map.of("id", EMPLEADO, "email", EMAIL));
    }

    private static IncomingEvent vacacionesIniciadas() {
        return evento(AuthEvents.VACACIONES_INICIADAS,
                Map.of("id", "periodo-uuid", "empleadoId", EMPLEADO));
    }

    private static IncomingEvent vacacionesFinalizadas() {
        return evento(AuthEvents.VACACIONES_FINALIZADAS,
                Map.of("id", "periodo-uuid", "empleadoId", EMPLEADO));
    }

    private static IncomingEvent empleadoRetirado() {
        return evento(AuthEvents.EMPLEADO_RETIRADO, Map.of("id", EMPLEADO, "email", EMAIL));
    }

    @Test
    void empleadoCreadoDaDeAltaLaCuentaEnPendienteConRolUser() {
        assertThat(lifecycle.procesar(empleadoCreado())).isEqualTo(Resultado.PROCESADO);

        UserAccount cuenta = cuentas.findById(EMPLEADO).orElseThrow();
        assertThat(cuenta.getEstado()).isEqualTo(AccountStatus.PENDIENTE_ACTIVACION);
        assertThat(cuenta.getRole()).isEqualTo(Role.USER);
        assertThat(cuenta.getPasswordHash())
                .as("la cuenta nace sin contraseña válida")
                .isNull();
        assertThat(cuenta.puedeAutenticarse()).isFalse();

        verify(publisher).publicarUsuarioCreado(any(), any());
    }

    @Test
    void vacacionesIniciadasSuspendeTemporalmenteYPublicaConPermanenteFalse() {
        lifecycle.procesar(empleadoCreado());
        activarCuenta();

        assertThat(lifecycle.procesar(vacacionesIniciadas())).isEqualTo(Resultado.PROCESADO);

        assertThat(cuentas.findById(EMPLEADO).orElseThrow().getEstado())
                .isEqualTo(AccountStatus.SUSPENDIDA_TEMPORAL);
        verify(publisher).publicarCuentaDesactivada(any(),
                eq(AuthEvents.MOTIVO_VACACIONES), eq(false));
    }

    @Test
    void vacacionesFinalizadasReactivaLaCuentaSuspendida() {
        lifecycle.procesar(empleadoCreado());
        activarCuenta();
        lifecycle.procesar(vacacionesIniciadas());

        assertThat(lifecycle.procesar(vacacionesFinalizadas())).isEqualTo(Resultado.PROCESADO);

        assertThat(cuentas.findById(EMPLEADO).orElseThrow().getEstado())
                .isEqualTo(AccountStatus.ACTIVA);
        verify(publisher).publicarCuentaActivada(any(), eq(AuthEvents.MOTIVO_FIN_VACACIONES));
    }

    @Test
    void empleadoRetiradoDesactivaDeFormaPermanenteYPublicaConPermanenteTrue() {
        lifecycle.procesar(empleadoCreado());
        activarCuenta();

        assertThat(lifecycle.procesar(empleadoRetirado())).isEqualTo(Resultado.PROCESADO);

        assertThat(cuentas.findById(EMPLEADO).orElseThrow().getEstado())
                .isEqualTo(AccountStatus.DESACTIVADA_PERMANENTE);
        verify(publisher).publicarCuentaDesactivada(any(),
                eq(AuthEvents.MOTIVO_RETIRO), eq(true));
    }

    /**
     * CASO BORDE EVALUADO: el empleado se retira estando de vacaciones. Al llegar
     * la fechaFin entra vacaciones.finalizadas sobre una cuenta con baja
     * permanente, y la cuenta NO debe reactivarse.
     */
    @Test
    void retiroDuranteVacacionesImpideLaReactivacionAlFinalizarElPeriodo() {
        lifecycle.procesar(empleadoCreado());
        activarCuenta();
        lifecycle.procesar(vacacionesIniciadas());
        lifecycle.procesar(empleadoRetirado());

        assertThat(cuentas.findById(EMPLEADO).orElseThrow().getEstado())
                .isEqualTo(AccountStatus.DESACTIVADA_PERMANENTE);

        Resultado resultado = lifecycle.procesar(vacacionesFinalizadas());

        assertThat(resultado)
                .as("el evento se reconoce pero no produce ningún cambio")
                .isEqualTo(Resultado.SIN_EFECTO);
        assertThat(cuentas.findById(EMPLEADO).orElseThrow().getEstado())
                .as("la cuenta sigue con baja permanente: el login debe seguir fallando")
                .isEqualTo(AccountStatus.DESACTIVADA_PERMANENTE);
        verify(publisher, never())
                .publicarCuentaActivada(any(), eq(AuthEvents.MOTIVO_FIN_VACACIONES));
    }

    @Test
    void unEventoRepetidoSeDescartaPorIdempotencia() {
        IncomingEvent alta = empleadoCreado();

        assertThat(lifecycle.procesar(alta)).isEqualTo(Resultado.PROCESADO);
        assertThat(lifecycle.procesar(alta)).isEqualTo(Resultado.DUPLICADO);

        verify(publisher).publicarUsuarioCreado(any(), any());
    }

    @Test
    void unEventoAjenoSeIgnoraSinTocarNada() {
        IncomingEvent ajeno = evento("departamento.creado", Map.of("id", "IT"));

        assertThat(lifecycle.procesar(ajeno)).isEqualTo(Resultado.IGNORADO);
        assertThat(cuentas.findById(EMPLEADO)).isEmpty();
    }

    /** Simula el POST /auth/reset-password que establece la contraseña inicial. */
    private void activarCuenta() {
        UserAccount cuenta = cuentas.findById(EMPLEADO).orElseThrow();
        cuenta.establecerPassword("$2a$10$hashDePrueba");
        cuentas.save(cuenta);
    }
}
