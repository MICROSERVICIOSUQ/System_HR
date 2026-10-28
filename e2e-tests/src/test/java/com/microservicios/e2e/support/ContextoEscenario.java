package com.microservicios.e2e.support;

import io.restassured.response.Response;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Estado compartido entre los pasos de un escenario (el "World" de Cucumber).
 *
 * <p>PicoContainer construye <strong>una instancia nueva por escenario</strong> y
 * la inyecta en cada clase de steps que la declare en su constructor. Esa es la
 * decisión de diseño que garantiza el aislamiento: ningún escenario ve el token
 * ni la respuesta del anterior, así que el orden de ejecución no importa y
 * cualquiera puede correrse solo.
 *
 * <p>Lo que el aislamiento del contexto NO resuelve es el estado del sistema
 * desplegado: un empleado creado por un escenario sigue en PostgreSQL cuando
 * empieza el siguiente. Para eso se usan identificadores únicos
 * ({@link #nuevoIdEmpleado()}), de modo que dos escenarios —o dos ejecuciones de
 * la misma suite— nunca compitan por la misma fila.
 */
public class ContextoEscenario {

    /** Token del usuario autenticado en este escenario. */
    private String token;

    /** Rol del token actual, para que los pasos puedan afirmar sobre él. */
    private String rol;

    /** Última respuesta HTTP recibida: la base de los pasos "Entonces". */
    private Response ultimaRespuesta;

    /** Datos que un paso produce y otro consume dentro del mismo escenario. */
    private final Map<String, String> datos = new HashMap<>();

    /** Sufijo único de este escenario, base de todos sus identificadores. */
    private final String sufijo = generarSufijo();

    private static String generarSufijo() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public String getToken() {
        return token;
    }

    public void autenticado(String token, String rol) {
        this.token = token;
        this.rol = rol;
    }

    public String getRol() {
        return rol;
    }

    public boolean hayToken() {
        return token != null && !token.isBlank();
    }

    /** Valor Authorization completo para peticiones autenticadas. */
    public String getAuthorizationHeader() {
        if (!hayToken()) {
            throw new IllegalStateException("El escenario no tiene un token de autenticación activo");
        }
        return "Bearer " + token;
    }

    /** Descarta la identidad: lo usan los escenarios que prueban el acceso sin token. */
    public void olvidarToken() {
        this.token = null;
        this.rol = null;
    }

    public Response getUltimaRespuesta() {
        if (ultimaRespuesta == null) {
            throw new IllegalStateException(
                    "Ningún paso anterior realizó una petición HTTP en este escenario");
        }
        return ultimaRespuesta;
    }

    public void setUltimaRespuesta(Response respuesta) {
        this.ultimaRespuesta = respuesta;
    }

    public void guardar(String clave, String valor) {
        datos.put(clave, valor);
    }

    public String leer(String clave) {
        String valor = datos.get(clave);
        if (valor == null) {
            throw new IllegalStateException(
                    "No hay ningún valor guardado bajo '" + clave
                            + "'. Revise que un paso anterior lo haya producido.");
        }
        return valor;
    }

    public boolean tiene(String clave) {
        return datos.containsKey(clave);
    }

    // ── Identificadores únicos por escenario ────────────────────────────────

    public String nuevoIdEmpleado() {
        return "E2E" + sufijo;
    }

    public String nuevoEmail() {
        return correoUnico(Configuracion.REGULAR_USER, sufijo);
    }

    public String nuevoNumeroEmpleado() {
        return "EMP-E2E-" + sufijo;
    }

    public String nuevoIdDepartamento() {
        return "DEP" + sufijo;
    }

    /** Variante para cuando un escenario necesita un segundo empleado distinto. */
    public String idEmpleadoSecundario() {
        return "E2E" + sufijo + "B";
    }

    public String emailSecundario() {
        return correoUnico(Configuracion.REGULAR_USER, sufijo + "b");
    }

    public String numeroEmpleadoSecundario() {
        return "EMP-E2E-" + sufijo + "B";
    }

    private static String correoUnico(String usuarioBase, String sufijo) {
        int separador = usuarioBase.lastIndexOf('@');
        if (separador < 0) {
            return usuarioBase + sufijo + "@empresa.com";
        }
        return usuarioBase.substring(0, separador)
                + "+" + sufijo + usuarioBase.substring(separador);
    }
}
