package com.microservicios.e2e.steps;

import com.microservicios.e2e.support.ApiCliente;
import com.microservicios.e2e.support.ContextoEscenario;
import io.cucumber.java.es.Cuando;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

import java.util.Map;

/**
 * Pasos de seguridad y control de acceso.
 *
 * <p>Son todos sincrónicos: cada petición recibe su respuesta de inmediato, sin
 * eventos de por medio.
 */
public class PasosSeguridad {

    /**
     * Token con payload que se autoproclama ADMIN pero con la firma inventada.
     * Si el gateway lo aceptara, cualquiera podría fabricarse permisos.
     */
    private static final String TOKEN_FIRMA_FALSA =
            "eyJhbGciOiJIUzI1NiJ9"
                    + ".eyJpc3MiOiJzeXN0ZW0taHItYXV0aCIsInN1YiI6ImhhY2tlciIsInJvbGUiOiJBRE1JTiIsImV4cCI6OTk5OTk5OTk5OX0"
                    + ".firma-falsificada-que-no-corresponde-al-secreto";

    private final ContextoEscenario contexto;
    private final ApiCliente api;

    public PasosSeguridad(ContextoEscenario contexto, ApiCliente api) {
        this.contexto = contexto;
        this.api = api;
    }

    @Cuando("consulto la lista de empleados sin token de autenticación")
    public void consultoSinToken() {
        api.listarEmpleadosSinToken();
    }

    @Cuando("consulto la lista de empleados con el token {string}")
    public void consultoConElToken(String token) {
        api.listarEmpleadosConToken(token);
    }

    @Cuando("consulto la lista de empleados con un token de firma falsificada")
    public void consultoConFirmaFalsificada() {
        api.listarEmpleadosConToken(TOKEN_FIRMA_FALSA);
    }

    @Cuando("consulto la lista de empleados con el token de activación")
    public void consultoConElTokenDeActivacion() {
        api.listarEmpleadosConToken(contexto.leer(PasosOnboarding.TOKEN_ACTIVACION));
    }

    /**
     * Comprueba que el gateway descarta las cabeceras de identidad que llegan del
     * cliente. Los microservicios confían en {@code X-Auth-User} y
     * {@code X-Auth-Role}; si el borde no las borrara, bastaría con enviarlas para
     * actuar como administrador.
     */
    @Cuando("consulto la lista de empleados enviando las cabeceras de identidad {string} y {string}")
    public void consultoConCabecerasDeIdentidadFalsificadas(String usuario, String rol) {
        Response r = RestAssured.given()
                .accept(ContentType.JSON)
                .header("X-Auth-User", usuario)
                .header("X-Auth-Role", rol)
                .when().get("/empleados");
        contexto.setUltimaRespuesta(r);
    }

    @Cuando("consulto la lista de empleados")
    public void consultoLaListaDeEmpleados() {
        api.listarEmpleados();
    }

    @Cuando("consulto la lista de departamentos")
    public void consultoLaListaDeDepartamentos() {
        contexto.setUltimaRespuesta(
                RestAssured.given()
                        .accept(ContentType.JSON)
                        .header("Authorization", "Bearer " + contexto.getToken())
                        .when().get("/departamentos"));
    }

    @Cuando("intento crear el departamento {string}")
    public void intentoCrearElDepartamento(String sufijo) {
        api.crearDepartamento(
                contexto.nuevoIdDepartamento() + "-" + sufijo,
                "Departamento " + sufijo,
                "Creado en un escenario de control de acceso");
    }

    @Cuando("intento retirar al empleado")
    public void intentoRetirarAlEmpleado() {
        api.retirarEmpleado(contexto.leer(PasosOnboarding.EMPLEADO_ID));
    }

    @Cuando("actualizo el perfil del empleado con el cargo {string}")
    public void actualizoElPerfilPropio(String cargo) {
        api.actualizarPerfil(contexto.leer(PasosOnboarding.EMPLEADO_ID),
                Map.of("cargo", cargo));
    }

    /**
     * Intento de escritura sobre un recurso ajeno.
     *
     * <p>Es la mitad que convierte el RBAC en ABAC: el token es válido y el verbo
     * está permitido para su dueño, pero el identificador de la ruta pertenece a
     * otro empleado, así que debe responder 403 y no 401.
     */
    @Cuando("actualizo el perfil de otro empleado con el cargo {string}")
    public void actualizoElPerfilAjeno(String cargo) {
        api.actualizarPerfil(contexto.idEmpleadoSecundario(), Map.of("cargo", cargo));
    }
}
