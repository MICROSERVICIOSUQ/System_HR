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
import java.util.concurrent.TimeoutException;

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
    @Cuando("registro un empleado con datos válidos e ID único")
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

    /**
     * Espera a que el alta asíncrona haya creado la cuenta y se queda con su
     * token de activación.
     *
     * <p>El token se pide a auth-service, que es quien lo firma. Antes se leía
     * del payload que notifications-service guardaba en su historial, y ese era
     * precisamente el hallazgo de seguridad que la auditoría pedía cerrar: un
     * token de un solo uso en texto plano en una base de datos y visible en
     * {@code GET /notificaciones}. Al sanearlo, la suite se quedó sin forma de
     * activar una cuenta; este es el canal legítimo.
     *
     * <p>Se reintenta porque el alta llega por evento: hasta que auth-service
     * procesa {@code empleado.creado} la cuenta no existe y el endpoint
     * responde 404. Que acabe respondiendo es, de paso, la prueba de que el
     * evento se consumió.
     */
    @Entonces("eventualmente el servicio de autenticación debe haber creado un usuario para el empleado")
    @Entonces("eventualmente el servicio de autenticación debe haber creado un usuario para ese correo")
    public void eventualmenteSeCreoElUsuario() throws TimeoutException {
        String empleadoId = contexto.leer(EMPLEADO_ID);

        String token = Espera.obtener(
                "auth-service debía haber creado la cuenta de " + empleadoId,
                () -> api.tokenDeRestablecimiento(empleadoId));

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
    public void eventualmenteExisteLaNotificacion(String tipoEvento) throws TimeoutException {
        String email = contexto.leer(EMPLEADO_EMAIL);

        Espera.hasta(
                "notificaciones debía registrar " + tipoEvento + " para " + email,
                () -> api.buscarNotificacion(tipoEvento, email) != null);
    }

    @Dado("que obtengo su token de activación")
    @Dado("que se generó un token de activación para el nuevo empleado")
    public void queObtengoSuTokenDeActivacion() throws TimeoutException {
        eventualmenteSeCreoElUsuario();
    }

    /**
     * El correo de bienvenida se registra, pero sin el token dentro.
     *
     * <p>Las dos mitades importan. Que la notificación exista demuestra que el
     * evento {@code usuario.creado} se consumió y que el correo se compuso; que
     * su payload no traiga el token demuestra que el historial no guarda
     * credenciales. Antes esta prueba afirmaba lo contrario —leía el token de
     * ahí— porque el servicio sí lo guardaba.
     */
    @Entonces("eventualmente el servicio de notificaciones debe registrar el correo de bienvenida sin filtrar el token")
    public void eventualmenteSeRegistroCorreoDeBienvenidaSinToken() throws TimeoutException {
        String email = contexto.leer(EMPLEADO_EMAIL);

        Espera.hasta(
                "notificaciones debía registrar el correo de bienvenida para " + email,
                () -> api.buscarNotificacion("usuario.creado", email) != null);

        assertThat(api.notificacionSinTokenFiltrado("usuario.creado", email))
                .as("el historial de notificaciones no debe conservar el token")
                .isTrue();

        // El token para activar se pide a quien lo firma.
        eventualmenteSeCreoElUsuario();
    }

    // ── Activación y login del empleado ─────────────────────────────────────

    @Cuando("el empleado establece su contraseña con el token de activación")
    @Cuando("el empleado establece su nueva contraseña utilizando el token de activación")
    public void elEmpleadoEstableceSuPassword() {
        api.resetPassword(contexto.leer(TOKEN_ACTIVACION), Configuracion.PASSWORD_EMPLEADO);
        contexto.guardar(PASSWORD_ACTUAL, Configuracion.PASSWORD_EMPLEADO);
    }

    @Entonces("el empleado puede iniciar sesión")
    @Entonces("eventualmente el empleado puede iniciar sesión exitosamente con su nueva contraseña")
    @Dado("que el empleado puede iniciar sesión")
    public void elEmpleadoPuedeIniciarSesion() throws TimeoutException {
        String email = contexto.leer(EMPLEADO_EMAIL);
        String password = contexto.tiene(PASSWORD_ACTUAL)
                ? contexto.leer(PASSWORD_ACTUAL)
                : Configuracion.PASSWORD_EMPLEADO;
        Espera.hasta(
                "el empleado " + email + " debía poder iniciar sesión",
                () -> api.login(email, password).statusCode() == 200);

        Response r = contexto.getUltimaRespuesta();
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
    public void queExisteUnEmpleadoActivoConCredenciales() throws TimeoutException {
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
        api.autenticar(Configuracion.ADMIN_USER, Configuracion.ADMIN_PASSWORD);
    }

    /** Igual que el anterior, pero deja autenticado al propio empleado (rol USER). */
    @Dado("que el empleado activa su cuenta y queda autenticado")
    public void elEmpleadoActivaSuCuentaYQuedaAutenticado() throws TimeoutException {
        eventualmenteSeCreoElUsuario();

        Response reset = api.resetPassword(
                contexto.leer(TOKEN_ACTIVACION), Configuracion.PASSWORD_EMPLEADO);
        assertThat(reset.statusCode()).isEqualTo(200);
        contexto.guardar(PASSWORD_ACTUAL, Configuracion.PASSWORD_EMPLEADO);

        api.autenticar(contexto.leer(EMPLEADO_EMAIL), Configuracion.PASSWORD_EMPLEADO);
        assertThat(contexto.getRol()).isEqualTo("USER");
    }
}
