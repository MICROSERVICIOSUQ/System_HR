package com.microservicios.e2e.support;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cliente HTTP de la suite, sobre Rest Assured.
 *
 * <p>Centraliza aquí las peticiones que varios escenarios comparten —login,
 * creación de empleados, consulta de notificaciones— para que los step
 * definitions describan intención y no mecánica HTTP. Todo pasa por el API
 * Gateway, igual que lo haría un cliente real: la suite nunca llama a un
 * microservicio por su puerto interno, porque entonces se saltaría la validación
 * del JWT que precisamente quiere comprobar.
 */
public class ApiCliente {

    private final ContextoEscenario contexto;

    public ApiCliente(ContextoEscenario contexto) {
        this.contexto = contexto;
        RestAssured.baseURI = Configuracion.BASE_URL;
    }

    /** Petición base: JSON, y el Bearer solo si el escenario tiene token. */
    private RequestSpecification peticion() {
        RequestSpecification spec = RestAssured.given()
                .accept(ContentType.JSON)
                .contentType(ContentType.JSON);
        if (contexto.hayToken()) {
            spec = spec.header("Authorization", contexto.getAuthorizationHeader());
        }
        return spec;
    }

    /** Petición deliberadamente sin credenciales, para los escenarios de 401. */
    private RequestSpecification peticionAnonima() {
        return RestAssured.given().accept(ContentType.JSON).contentType(ContentType.JSON);
    }

    // ── Autenticación ────────────────────────────────────────────────────────

    public Response login(String usuario, String password) {
        Response r = peticionAnonima()
                .body(Map.of("usuario", usuario, "password", password))
                .when().post("/auth/login");
        contexto.setUltimaRespuesta(r);
        return r;
    }

    /** Autentica y deja el token en el contexto. Falla si las credenciales no sirven. */
    public void autenticar(String usuario, String password) {
        Response r = login(usuario, password);
        if (r.statusCode() != 200) {
            throw new AssertionError(String.format(
                    "No se pudo autenticar a '%s': HTTP %d — %s",
                    usuario, r.statusCode(), r.asString()));
        }
        contexto.autenticado(r.path("token"), r.path("rol"));
    }

    public Response resetPassword(String token, String nuevaPassword) {
        Response r = peticionAnonima()
                .body(Map.of("token", token, "nuevaPassword", nuevaPassword))
                .when().post("/auth/reset-password");
        contexto.setUltimaRespuesta(r);
        return r;
    }

    public Response recuperarPassword(String email) {
        Response r = peticionAnonima()
                .body(Map.of("email", email))
                .when().post("/auth/recover-password");
        contexto.setUltimaRespuesta(r);
        return r;
    }

    public Response cambiarPassword(String actual, String nueva) {
        Response r = peticion()
                .body(Map.of("passwordActual", actual, "nuevaPassword", nueva))
                .when().post("/auth/change-password");
        contexto.setUltimaRespuesta(r);
        return r;
    }

    // ── Departamentos ────────────────────────────────────────────────────────

    public Response crearDepartamento(String id, String nombre, String descripcion) {
        Response r = peticion()
                .body(Map.of("id", id, "nombre", nombre, "descripcion", descripcion))
                .when().post("/departamentos");
        contexto.setUltimaRespuesta(r);
        return r;
    }

    public Response obtenerDepartamento(String id) {
        Response r = peticion().when().get("/departamentos/{id}", id);
        contexto.setUltimaRespuesta(r);
        return r;
    }

    // ── Empleados ────────────────────────────────────────────────────────────

    /** Cuerpo completo del modelo canónico, con los diez campos obligatorios. */
    public Map<String, Object> cuerpoEmpleado(String id, String email, String numero,
                                              String departamentoId) {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("id", id);
        cuerpo.put("nombre", "Empleado");
        cuerpo.put("apellido", "DePrueba");
        cuerpo.put("email", email);
        cuerpo.put("numeroEmpleado", numero);
        cuerpo.put("cargo", "Analista");
        cuerpo.put("area", "Tecnología");
        cuerpo.put("departamentoId", departamentoId);
        cuerpo.put("fechaIngreso", LocalDate.now().toString());
        cuerpo.put("estado", "ACTIVO");
        return cuerpo;
    }

    public Response crearEmpleado(Map<String, Object> cuerpo) {
        Response r = peticion().body(cuerpo).when().post("/empleados");
        contexto.setUltimaRespuesta(r);
        return r;
    }

    public Response obtenerEmpleado(String id) {
        Response r = peticion().when().get("/empleados/{id}", id);
        contexto.setUltimaRespuesta(r);
        return r;
    }

    public Response listarEmpleados() {
        Response r = peticion().when().get("/empleados");
        contexto.setUltimaRespuesta(r);
        return r;
    }

    public Response listarEmpleadosSinToken() {
        Response r = peticionAnonima().when().get("/empleados");
        contexto.setUltimaRespuesta(r);
        return r;
    }

    public Response listarEmpleadosConToken(String tokenCrudo) {
        Response r = peticionAnonima()
                .header("Authorization", "Bearer " + tokenCrudo)
                .when().get("/empleados");
        contexto.setUltimaRespuesta(r);
        return r;
    }

    public Response auditoriaRetirados() {
        Response r = peticion().queryParam("estado", "RETIRADO").when().get("/empleados");
        contexto.setUltimaRespuesta(r);
        return r;
    }

    public Response retirarEmpleado(String id) {
        Response r = peticion().when().delete("/empleados/{id}", id);
        contexto.setUltimaRespuesta(r);
        return r;
    }

    public Response actualizarPerfil(String empleadoId, Map<String, Object> cambios) {
        Response r = peticion().body(cambios).when().put("/perfiles/{id}", empleadoId);
        contexto.setUltimaRespuesta(r);
        return r;
    }

    // ── Vacaciones ───────────────────────────────────────────────────────────

    public Response programarVacaciones(String empleadoId, LocalDate inicio, LocalDate fin) {
        Response r = peticion()
                .body(Map.of(
                        "empleadoId", empleadoId,
                        "fechaInicio", inicio.toString(),
                        "fechaFin", fin.toString()))
                .when().post("/vacaciones");
        contexto.setUltimaRespuesta(r);
        return r;
    }

    public Response forzarInicioVacaciones(String periodoId) {
        Response r = peticion().when()
                .post("/vacaciones/{id}/forzar-inicio", periodoId);
        contexto.setUltimaRespuesta(r);
        return r;
    }

    public Response forzarFinVacaciones(String periodoId) {
        Response r = peticion().when()
                .post("/vacaciones/{id}/forzar-fin", periodoId);
        contexto.setUltimaRespuesta(r);
        return r;
    }

    // ── Notificaciones: la ventana a los efectos asíncronos ──────────────────

    /**
     * Historial de notificaciones procesadas.
     *
     * <p>Es la forma de observar por API lo que ocurrió por eventos. El servicio
     * guarda el envelope completo en {@code payload}, así que desde aquí se puede
     * comprobar no solo que la notificación existe, sino su contenido.
     */
    public Response listarNotificaciones() {
        Response r = peticion().when().get("/notifications");
        contexto.setUltimaRespuesta(r);
        return r;
    }

    /**
     * Busca una notificación por tipo de evento y correo del destinatario.
     *
     * @return el registro, o {@code null} si el evento aún no llegó
     */
    public Map<String, Object> buscarNotificacion(String tipoEvento, String email) {
        Response r = peticion().when().get("/notifications");
        if (r.statusCode() != 200) {
            return null;
        }
        List<Map<String, Object>> registros = extraerLista(r);
        return registros.stream()
                .filter(n -> tipoEvento.equals(n.get("eventType")))
                .filter(n -> email.equalsIgnoreCase(String.valueOf(n.get("email"))))
                .findFirst()
                .orElse(null);
    }

    /**
     * Comprueba que una notificación NO lleva el token en su payload.
     *
     * <p>Antes la suite leía el token de aquí, porque notifications-service lo
     * persistía en el historial. Eso era el hallazgo de seguridad que la
     * auditoría pedía cerrar —un token de un solo uso guardado en texto plano en
     * una base de datos y visible en {@code GET /notificaciones}— y el servicio
     * reconstruido en Go ya solo conserva campos públicos.
     *
     * <p>El método se queda, pero con el signo cambiado: ahora sirve para
     * verificar la ausencia. Lo que antes era el canal de obtención es hoy una
     * aserción de seguridad.
     *
     * @return {@code true} si la notificación existe y su payload no trae token
     */
    @SuppressWarnings("unchecked")
    public boolean notificacionSinTokenFiltrado(String tipoEvento, String email) {
        Map<String, Object> notificacion = buscarNotificacion(tipoEvento, email);
        if (notificacion == null) {
            return false;
        }
        Object payload = notificacion.get("payload");
        if (!(payload instanceof Map<?, ?> envelope)) {
            return true;
        }
        Object data = ((Map<String, Object>) envelope).get("data");
        if (!(data instanceof Map<?, ?> datos)) {
            return true;
        }
        return !((Map<String, Object>) datos).containsKey("token");
    }

    /**
     * Pide a auth-service un token de restablecimiento para una cuenta.
     *
     * <p>Es el canal legítimo: lo entrega quien firma el token, no el historial
     * de correos de otro servicio. Requiere estar autenticado como ADMIN, que es
     * lo que el gateway exige para este POST.
     *
     * @return el token, o {@code null} si la cuenta todavía no existe —el alta
     *         llega por evento y puede tardar, así que quien llama suele
     *         reintentar
     */
    public String tokenDeRestablecimiento(String empleadoId) {
        Response r = peticion()
                .body(Map.of("empleadoId", empleadoId))
                .when().post("/auth/internal/tokens/restablecimiento");
        if (r.statusCode() != 200) {
            return null;
        }
        return r.path("token");
    }

    /** Motivo que viajó en un evento de cuenta ({@code VACACIONES}, {@code RETIRO}…). */
    @SuppressWarnings("unchecked")
    public String motivoDeEvento(String tipoEvento, String email) {
        Map<String, Object> notificacion = buscarNotificacion(tipoEvento, email);
        if (notificacion == null) {
            return null;
        }
        Object payload = notificacion.get("payload");
        if (!(payload instanceof Map<?, ?> envelope)) {
            return null;
        }
        Object data = ((Map<String, Object>) envelope).get("data");
        if (!(data instanceof Map<?, ?> datos)) {
            return null;
        }
        Object motivo = ((Map<String, Object>) datos).get("motivo");
        return motivo == null ? null : String.valueOf(motivo);
    }

    public Response salud() {
        Response r = peticionAnonima().when().get("/health");
        contexto.setUltimaRespuesta(r);
        return r;
    }

    /**
     * Normaliza la forma de los listados.
     *
     * <p>Algunos endpoints devuelven un array y otros un objeto paginado con la
     * lista en {@code data}. Este método absorbe esa diferencia para que los pasos
     * no tengan que saber cuál es cuál.
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> extraerLista(Response r) {
        Object cuerpo = r.as(Object.class);
        if (cuerpo instanceof List<?> lista) {
            return (List<Map<String, Object>>) lista;
        }
        if (cuerpo instanceof Map<?, ?> mapa) {
            Object data = ((Map<String, Object>) mapa).get("data");
            if (data instanceof List<?> lista) {
                return (List<Map<String, Object>>) lista;
            }
        }
        return List.of();
    }
}
