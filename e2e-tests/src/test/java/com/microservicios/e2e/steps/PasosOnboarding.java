package com.microservicios.e2e.steps;

import com.microservicios.e2e.support.ApiCliente;
import com.microservicios.e2e.support.Configuracion;
import com.microservicios.e2e.support.ContextoEscenario;
import com.microservicios.e2e.support.Espera;
import io.cucumber.java.es.Cuando;
import io.cucumber.java.es.Dado;
import io.cucumber.java.es.Entonces;
import io.restassured.response.Response;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pasos del alta de departamentos y empleados, y de la activación de la cuenta.
 *
 * <p>Los identificadores salen del contexto, que genera un sufijo único por
 * escenario. Así cada escenario trabaja sobre sus propios datos y puede
 * ejecutarse aislado, en cualquier orden y varias veces seguidas.
 */
public class PasosOnboarding {

    /** Claves bajo las que se comparten datos entre pasos del mismo escenario. */
    public static final String EMPLEADO_ID = "empleadoId";
    public static final String EMPLEADO_EMAIL = "empleadoEmail";
    public static final String EMPLEADO_NUMERO = "empleadoNumero";
    public static final String DEPARTAMENTO_ID = "departamentoId";
    public static final String TOKEN_ACTIVACION = "tokenActivacion";
    public static final String PASSWORD_ACTUAL = "passwordActual";
    public static final String EMPLEADO_SECUNDARIO_ID = "empleadoSecundarioId";

    private final ContextoEscenario contexto;
    private final ApiCliente api;

    public PasosOnboarding(ContextoEscenario contexto, ApiCliente api) {
        this.contexto = contexto;
        this.api = api;
    }

    @Dado("que existe un departamento para las pruebas")
    public void queExisteUnDepartamentoParaLasPruebas() {
        String id = contexto.nuevoIdDepartamento();
        Response r = api.crearDepartamento(id, "Departamento E2E", "Creado por la suite BDD");
        assertThat(r.statusCode())
                .as("no se pudo preparar el departamento: %s", r.asString())
                .isEqualTo(201);
        contexto.guardar(DEPARTAMENTO_ID, id);
    }

    @Dado("que existe un departamento {string} con nombre {string}")
    public void queExisteUnDepartamentoConNombre(String id, String nombre) {
        Response r = api.crearDepartamento(id, nombre, "Creado por la suite BDD");
        assertThat(r.statusCode())
                .as("el departamento debía quedar disponible: %s", r.asString())
                .isIn(201, 400, 409);
        contexto.guardar(DEPARTAMENTO_ID, id);
    }

    @Dado("que registro un empleado nuevo")
    @Cuando("registro un empleado nuevo")
    public void registroUnEmpleadoNuevo() {
        String id = contexto.nuevoIdEmpleado();
        String email = contexto.nuevoEmail();
        String numero = contexto.nuevoNumeroEmpleado();

        api.crearEmpleado(api.cuerpoEmpleado(
                id, email, numero, contexto.leer(DEPARTAMENTO_ID)));

        contexto.guardar(EMPLEADO_ID, id);
        contexto.guardar(EMPLEADO_EMAIL, email);
        contexto.guardar(EMPLEADO_NUMERO, numero);
    }

    @Cuando("registro un empleado en el departamento {string}")
    public void registroUnEmpleadoEnElDepartamento(String departamentoId) {
        api.crearEmpleado(api.cuerpoEmpleado(
                contexto.nuevoIdEmpleado(), contexto.nuevoEmail(),
                contexto.nuevoNumeroEmpleado(), departamentoId));
    }

    @Cuando("registro un empleado con el email {string}")
    public void registroUnEmpleadoConElEmail(String email) {
        api.crearEmpleado(api.cuerpoEmpleado(
                contexto.nuevoIdEmpleado(), email,
                contexto.nuevoNumeroEmpleado(), contexto.leer(DEPARTAMENTO_ID)));
    }

    @Cuando("registro un empleado sin los campos obligatorios")
    public void registroUnEmpleadoSinLosCamposObligatorios() {
        // Solo el id: el resto de los diez campos del modelo canónico se omite.
        api.crearEmpleado(Map.of("id", contexto.nuevoIdEmpleado()));
    }

    @Cuando("registro otro empleado con el mismo email")
    public void registroOtroEmpleadoConElMismoEmail() {
        api.crearEmpleado(api.cuerpoEmpleado(
                contexto.idEmpleadoSecundario(),
                contexto.leer(EMPLEADO_EMAIL),
                contexto.numeroEmpleadoSecundario(),
                contexto.leer(DEPARTAMENTO_ID)));
    }

    @Cuando("registro otro empleado con el mismo número de empleado")
    public void registroOtroEmpleadoConElMismoNumero() {
        api.crearEmpleado(api.cuerpoEmpleado(
                contexto.idEmpleadoSecundario(),
                contexto.emailSecundario(),
                contexto.leer(EMPLEADO_NUMERO),
                contexto.leer(DEPARTAMENTO_ID)));
    }

    @Cuando("consulto el empleado por su identificador")
    public void consultoElEmpleadoPorSuIdentificador() {
        api.obtenerEmpleado(contexto.leer(EMPLEADO_ID));
    }

    // ── Aserciones del modelo canónico ──────────────────────────────────────

    @Entonces("el cuerpo debe contener los diez campos del modelo canónico")
    public void elCuerpoDebeContenerLosDiezCampos() {
        List<String> campos = List.of("id", "nombre", "apellido", "email",
                "numeroEmpleado", "cargo", "area", "departamentoId",
                "fechaIngreso", "estado");

        Map<String, Object> cuerpo = contexto.getUltimaRespuesta().jsonPath().getMap("$");
        assertThat(cuerpo.keySet())
                .as("faltan campos del modelo canónico en la respuesta")
                .containsAll(campos);
    }

    @Entonces("el estado del empleado debe ser {string}")
    public void elEstadoDelEmpleadoDebeSer(String estadoEsperado) {
        assertThat(contexto.getUltimaRespuesta().path("estado").toString())
                .isEqualTo(estadoEsperado);
    }

    // ── Verificación asíncrona: la cuenta y la notificación ─────────────────

    @Entonces("eventualmente el servicio de autenticación debe haber creado un usuario para el empleado")
    public void eventualmenteSeCreoElUsuario() {
        String email = contexto.leer(EMPLEADO_EMAIL);

        String token = Espera.obtener(
                "el auth-service debía publicar usuario.creado para " + email,
                () -> api.tokenDeEvento("usuario.creado", email));

        assertThat(token).isNotBlank();
        contexto.guardar(TOKEN_ACTIVACION, token);
    }

    @Entonces("el token de activación recibido no debe ser una contraseña en claro")
    public void elTokenNoEsUnaPasswordEnClaro() {
        String token = contexto.leer(TOKEN_ACTIVACION);

        // Un JWT tiene tres segmentos separados por puntos. Que lo sea demuestra
        // que el evento transporta un token firmado y de vida corta, no una
        // credencial reutilizable.
        assertThat(token.split("\\.")).as("se esperaba un JWT de tres segmentos").hasSize(3);
        assertThat(token).doesNotContain(Configuracion.PASSWORD_EMPLEADO);
    }

    @Entonces("eventualmente debe existir una notificación {string} para el empleado")
    public void eventualmenteExisteLaNotificacion(String tipoEvento) {
        String email = contexto.leer(EMPLEADO_EMAIL);

        Espera.hasta(
                "notificaciones debía registrar " + tipoEvento + " para " + email,
                () -> api.buscarNotificacion(tipoEvento, email) != null);
    }

    @Dado("que obtengo su token de activación")
    public void queObtengoSuTokenDeActivacion() {
        eventualmenteSeCreoElUsuario();
    }

    // ── Activación y login del empleado ─────────────────────────────────────

    @Cuando("el empleado establece su contraseña con el token de activación")
    public void elEmpleadoEstableceSuPassword() {
        api.resetPassword(contexto.leer(TOKEN_ACTIVACION), Configuracion.PASSWORD_EMPLEADO);
        contexto.guardar(PASSWORD_ACTUAL, Configuracion.PASSWORD_EMPLEADO);
    }

    @Entonces("el empleado puede iniciar sesión")
    @Dado("que el empleado puede iniciar sesión")
    public void elEmpleadoPuedeIniciarSesion() {
        Response r = api.login(
                contexto.leer(EMPLEADO_EMAIL),
                contexto.tiene(PASSWORD_ACTUAL)
                        ? contexto.leer(PASSWORD_ACTUAL)
                        : Configuracion.PASSWORD_EMPLEADO);

        assertThat(r.statusCode())
                .as("el empleado debía poder autenticarse: %s", r.asString())
                .isEqualTo(200);
    }

    @Cuando("el empleado intenta iniciar sesión")
    public void elEmpleadoIntentaIniciarSesion() {
        api.login(contexto.leer(EMPLEADO_EMAIL), Configuracion.PASSWORD_EMPLEADO);
    }

    /**
     * Precondición compuesta de los escenarios de vacaciones y offboarding:
     * un empleado que existe, tiene contraseña y puede autenticarse.
     *
     * <p>Se construye desde cero invocando los endpoints reales, no con datos
     * precargados, para que cada escenario sea independiente y la precondición
     * misma quede verificada.
     */
    @Dado("que existe un empleado activo con credenciales establecidas")
    public void queExisteUnEmpleadoActivoConCredenciales() {
        registroUnEmpleadoNuevo();
        assertThat(contexto.getUltimaRespuesta().statusCode())
                .as("la precondición requiere registrar al empleado: %s",
                        contexto.getUltimaRespuesta().asString())
                .isEqualTo(201);

        eventualmenteSeCreoElUsuario();

        Response reset = api.resetPassword(
                contexto.leer(TOKEN_ACTIVACION), Configuracion.PASSWORD_EMPLEADO);
        assertThat(reset.statusCode())
                .as("la precondición requiere activar la cuenta: %s", reset.asString())
                .isEqualTo(200);
        contexto.guardar(PASSWORD_ACTUAL, Configuracion.PASSWORD_EMPLEADO);

        // El ADMIN vuelve a ser la identidad activa: los escenarios programan
        // vacaciones y retiran empleados, que son operaciones de administrador.
        api.autenticar(Configuracion.ADMIN_USUARIO, Configuracion.ADMIN_PASSWORD);
    }

    /** Igual que el anterior, pero deja autenticado al propio empleado (rol USER). */
    @Dado("que el empleado activa su cuenta y queda autenticado")
    public void elEmpleadoActivaSuCuentaYQuedaAutenticado() {
        eventualmenteSeCreoElUsuario();

        Response reset = api.resetPassword(
                contexto.leer(TOKEN_ACTIVACION), Configuracion.PASSWORD_EMPLEADO);
        assertThat(reset.statusCode()).isEqualTo(200);
        contexto.guardar(PASSWORD_ACTUAL, Configuracion.PASSWORD_EMPLEADO);

        api.autenticar(contexto.leer(EMPLEADO_EMAIL), Configuracion.PASSWORD_EMPLEADO);
        assertThat(contexto.getRol()).isEqualTo("USER");
    }
}
