package com.microservicios.e2e.steps;

import com.microservicios.e2e.support.ApiCliente;
import com.microservicios.e2e.support.Configuracion;
import com.microservicios.e2e.support.ContextoEscenario;
import com.microservicios.e2e.support.Espera;
import io.cucumber.java.es.Cuando;
import io.cucumber.java.es.Dado;
import io.cucumber.java.es.Entonces;
import io.restassured.response.Response;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pasos del ciclo de vida de la cuenta: vacaciones y offboarding.
 *
 * <p>Las dos características comparten el mismo mecanismo —un evento de dominio
 * cambia el estado de la cuenta, y eso se observa intentando iniciar sesión— así
 * que viven en la misma clase para no duplicar los pasos de acceso.
 *
 * <p><strong>Control del tiempo.</strong> El scheduler del vacations-service
 * revisa los períodos a intervalos. Acoplar la prueba a esa frecuencia la
 * volvería lenta —habría que esperar un ciclo completo por transición— y frágil,
 * porque cambiar el intervalo la rompería. En su lugar se usan los endpoints de
 * disparo manual {@code POST /vacaciones/{id}/forzar-inicio} y
 * {@code /forzar-fin}, que ejecutan exactamente la misma transición y publican el
 * mismo evento que el scheduler. La prueba verifica el comportamiento del
 * sistema, no la puntualidad del reloj.
 *
 * <p>Cada texto de paso está declarado una sola vez: Cucumber ignora la palabra
 * clave al emparejar, así que registrar el mismo texto con dos anotaciones
 * distintas sería una definición duplicada.
 */
public class PasosCicloDeVida {

    private static final String PERIODO_ID = "periodoId";

    private final ContextoEscenario contexto;
    private final ApiCliente api;

    public PasosCicloDeVida(ContextoEscenario contexto, ApiCliente api) {
        this.contexto = contexto;
        this.api = api;
    }

    private String empleado() {
        return contexto.leer(PasosOnboarding.EMPLEADO_ID);
    }

    private String email() {
        return contexto.leer(PasosOnboarding.EMPLEADO_EMAIL);
    }

    private String password() {
        return contexto.tiene(PasosOnboarding.PASSWORD_ACTUAL)
                ? contexto.leer(PasosOnboarding.PASSWORD_ACTUAL)
                : Configuracion.PASSWORD_EMPLEADO;
    }

    private void guardarPeriodoSiSeCreo(Response r) {
        if (r.statusCode() != 201) {
            return;
        }
        // El servicio responde el período directamente o envuelto en "data".
        Object id = r.path("id");
        if (id == null) {
            id = r.path("data.id");
        }
        if (id != null) {
            contexto.guardar(PERIODO_ID, String.valueOf(id));
        }
    }

    private String estadoDelPeriodo(Response r) {
        Object estado = r.path("estado");
        if (estado == null) {
            estado = r.path("data.estado");
        }
        return estado == null ? null : String.valueOf(estado);
    }

    // ── Programación de períodos ────────────────────────────────────────────

    @Cuando("programo un período de vacaciones de {int} días que empieza mañana")
    public void programoUnPeriodoDeDias(int dias) {
        LocalDate inicio = LocalDate.now().plusDays(1);
        guardarPeriodoSiSeCreo(
                api.programarVacaciones(empleado(), inicio, inicio.plusDays(dias - 1L)));
    }

    @Dado("que programo un período de vacaciones de {int} días que empieza mañana")
    public void queProgramoUnPeriodoDeDias(int dias) {
        programoUnPeriodoDeDias(dias);
    }

    @Cuando("programo un período de vacaciones de {int} día que empieza mañana")
    public void programoUnPeriodoDeUnDia(int dias) {
        programoUnPeriodoDeDias(dias);
    }

    @Cuando("programo un período de vacaciones cuya fecha de fin es anterior a la de inicio")
    public void programoUnPeriodoConFechasInvertidas() {
        LocalDate inicio = LocalDate.now().plusDays(10);
        api.programarVacaciones(empleado(), inicio, inicio.minusDays(5));
    }

    @Cuando("programo un período de vacaciones que empezó la semana pasada")
    public void programoUnPeriodoEnElPasado() {
        LocalDate inicio = LocalDate.now().minusDays(7);
        api.programarVacaciones(empleado(), inicio, inicio.plusDays(3));
    }

    @Cuando("programo otro período que se solapa con el anterior")
    public void programoOtroPeriodoQueSeSolapa() {
        // El período anterior empieza mañana y dura cinco días; este empieza
        // dentro de ese rango, así que comparte días con él.
        LocalDate inicio = LocalDate.now().plusDays(3);
        api.programarVacaciones(empleado(), inicio, inicio.plusDays(4));
    }

    /**
     * Período de un solo día que arranca hoy.
     *
     * <p>Es la forma de tener un período elegible para iniciar de inmediato: su
     * fecha de inicio ya llegó, así que tanto el scheduler como el disparo manual
     * pueden ponerlo EN_CURSO.
     */
    @Dado("que programo un período de vacaciones que inicia de inmediato")
    public void programoUnPeriodoQueIniciaDeInmediato() {
        LocalDate hoy = LocalDate.now();
        Response r = api.programarVacaciones(empleado(), hoy, hoy);
        assertThat(r.statusCode())
                .as("no se pudo programar el período inmediato: %s", r.asString())
                .isEqualTo(201);
        guardarPeriodoSiSeCreo(r);
    }

    @Cuando("programo un período de vacaciones que inicia de inmediato")
    public void cuandoProgramoUnPeriodoQueIniciaDeInmediato() {
        programoUnPeriodoQueIniciaDeInmediato();
    }

    @Entonces("el período debe quedar en estado {string}")
    public void elPeriodoDebeQuedarEnEstado(String estadoEsperado) {
        assertThat(estadoDelPeriodo(contexto.getUltimaRespuesta()))
                .isEqualTo(estadoEsperado);
    }

    // ── Transiciones del período ───────────────────────────────────────────

    @Cuando("el período de vacaciones inicia")
    @Cuando("fuerzo la ejecución del scheduler para iniciar el período")
    public void elPeriodoDeVacacionesInicia() {
        Response r = api.forzarInicioVacaciones(contexto.leer(PERIODO_ID));
        assertThat(r.statusCode())
                .as("el período debía poder iniciarse: %s", r.asString())
                .isEqualTo(200);
        assertThat(estadoDelPeriodo(r)).isEqualTo("EN_CURSO");
    }

    @Dado("que el período de vacaciones inicia")
    public void queElPeriodoDeVacacionesInicia() {
        elPeriodoDeVacacionesInicia();
    }

    @Cuando("el período de vacaciones finaliza")
    @Cuando("fuerzo la finalización del período de vacaciones")
    public void elPeriodoDeVacacionesFinaliza() {
        Response r = api.forzarFinVacaciones(contexto.leer(PERIODO_ID));
        assertThat(r.statusCode())
                .as("el período debía poder finalizarse: %s", r.asString())
                .isEqualTo(200);
        assertThat(estadoDelPeriodo(r)).isEqualTo("FINALIZADA");
    }

    // ── Verificación asíncrona del estado de la cuenta ─────────────────────

    /**
     * Polling sobre una condición negativa.
     *
     * <p>Comprobar que «el empleado eventualmente no puede entrar» no es lo mismo
     * que comprobar que algo aparece: un login que falla podría estar fallando
     * porque el evento aún no llegó, o porque el sistema está roto. Se espera a
     * que el login empiece a fallar y luego se confirma varias veces, de modo que
     * un estado transitorio no se confunda con el definitivo.
     */
    @Entonces("eventualmente el empleado no puede iniciar sesión")
    public void eventualmenteElEmpleadoNoPuedeIniciarSesion() throws TimeoutException {
        Espera.establemente(
                "el login de " + email() + " debía quedar bloqueado tras el evento",
                () -> api.login(email(), password()).statusCode() == 401,
                2);
    }

    @Dado("que eventualmente el empleado no puede iniciar sesión")
    public void queEventualmenteElEmpleadoNoPuedeIniciarSesion() throws TimeoutException {
        eventualmenteElEmpleadoNoPuedeIniciarSesion();
    }

    @Entonces("eventualmente el empleado puede iniciar sesión de nuevo")
    public void eventualmenteElEmpleadoPuedeIniciarSesionDeNuevo() throws TimeoutException {
        Espera.hasta(
                "la cuenta de " + email() + " debía reactivarse al finalizar las vacaciones",
                () -> api.login(email(), password()).statusCode() == 200);
    }

    @Entonces("el motivo de la desactivación debe ser {string}")
    public void elMotivoDeLaDesactivacionDebeSer(String motivoEsperado) throws TimeoutException {
        String motivo = Espera.obtener(
                "cuenta.desactivada debía traer el motivo " + motivoEsperado,
                () -> api.motivoDeEvento("cuenta.desactivada", email()));

        assertThat(motivo).isEqualTo(motivoEsperado);
    }

    // ── Offboarding ────────────────────────────────────────────────────────

    @Cuando("retiro al empleado")
    public void retiroAlEmpleado() {
        // El retiro es una operación de administrador: si el escenario dejó
        // autenticado al propio empleado, se recupera la identidad de ADMIN.
        if (!"ADMIN".equals(contexto.getRol())) {
            api.autenticar(Configuracion.ADMIN_USER, Configuracion.ADMIN_PASSWORD);
        }
        Response r = api.retirarEmpleado(empleado());
        assertThat(r.statusCode())
                .as("el retiro debía aceptarse: %s", r.asString())
                .isIn(200, 204);
        contexto.setUltimaRespuesta(r);
    }

    @Dado("que retiro al empleado")
    public void queRetiroAlEmpleado() {
        retiroAlEmpleado();
    }

    @Cuando("eventualmente el empleado solicita recuperar su contraseña y es rechazado")
    public void eventualmenteLaRecuperacionEsRechazada() throws TimeoutException {
        // El rechazo depende de que auth-service ya haya procesado
        // empleado.retirado: hasta entonces la recuperación podría aceptarse.
        Espera.hasta(
                "la recuperación de " + email() + " debía quedar bloqueada tras el retiro",
                () -> api.recuperarPassword(email()).statusCode() == 401);
    }

    @Cuando("consulto la auditoría de empleados retirados")
    public void consultoLaAuditoriaDeRetirados() {
        api.auditoriaRetirados();
    }

    @Entonces("el empleado debe figurar en la auditoría con su fecha de retiro")
    public void elEmpleadoDebeFigurarEnLaAuditoria() {
        List<Map<String, Object>> retirados =
                ApiCliente.extraerLista(contexto.getUltimaRespuesta());

        Map<String, Object> encontrado = retirados.stream()
                .filter(e -> empleado().equals(e.get("id")))
                .findFirst()
                .orElse(null);

        assertThat(encontrado)
                .as("el empleado %s debía aparecer en la auditoría de retirados", empleado())
                .isNotNull();
        assertThat(encontrado.get("estado")).isEqualTo("RETIRADO");
        assertThat(encontrado.get("fechaRetiro"))
                .as("la baja es lógica: debe quedar constancia de cuándo ocurrió")
                .isNotNull();
    }
}
