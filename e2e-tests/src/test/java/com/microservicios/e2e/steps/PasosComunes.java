package com.microservicios.e2e.steps;

import com.microservicios.e2e.support.ApiCliente;
import com.microservicios.e2e.support.Configuracion;
import com.microservicios.e2e.support.ContextoEscenario;
import io.cucumber.java.es.Cuando;
import io.cucumber.java.es.Dado;
import io.cucumber.java.es.Entonces;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pasos reutilizables por todas las características: salud, autenticación y
 * aserciones sobre la última respuesta.
 *
 * <p>El contexto y el cliente llegan por el constructor: PicoContainer crea una
 * instancia nueva de cada uno por escenario y la comparte entre todas las clases
 * de pasos del mismo escenario.
 */
public class PasosComunes {

    private final ContextoEscenario contexto;
    private final ApiCliente api;

    public PasosComunes(ContextoEscenario contexto, ApiCliente api) {
        this.contexto = contexto;
        this.api = api;
    }

    @Dado("que el sistema está desplegado y operativo")
    public void elSistemaEstaOperativo() {
        api.salud();
    }

    @Cuando("me autentico como administrador")
    public void meAutenticoComoAdministrador() {
        api.login(Configuracion.ADMIN_USUARIO, Configuracion.ADMIN_PASSWORD);
        if (contexto.getUltimaRespuesta().statusCode() == 200) {
            contexto.autenticado(
                    contexto.getUltimaRespuesta().path("token"),
                    contexto.getUltimaRespuesta().path("rol"));
        }
    }

    @Dado("que estoy autenticado como {string}")
    public void queEstoyAutenticadoComo(String rol) {
        if (!"ADMIN".equals(rol)) {
            throw new IllegalArgumentException(
                    "Solo el rol ADMIN tiene credenciales fijas. Para un USER, use "
                            + "el paso que registra y activa un empleado.");
        }
        api.autenticar(Configuracion.ADMIN_USUARIO, Configuracion.ADMIN_PASSWORD);
        assertThat(contexto.getRol()).isEqualTo("ADMIN");
    }

    // ── Aserciones sobre la última respuesta ────────────────────────────────

    @Entonces("la respuesta debe tener código {int}")
    public void laRespuestaDebeTenerCodigo(int codigoEsperado) {
        assertThat(contexto.getUltimaRespuesta().statusCode())
                .as("cuerpo de la respuesta: %s", contexto.getUltimaRespuesta().asString())
                .isEqualTo(codigoEsperado);
    }

    @Entonces("el mensaje de error debe contener {string}")
    public void elMensajeDeErrorDebeContener(String fragmento) {
        String cuerpo = contexto.getUltimaRespuesta().asString();
        assertThat(cuerpo)
                .as("se esperaba que el mensaje de error mencionara '%s'", fragmento)
                .containsIgnoringCase(fragmento);
    }

    @Entonces("el token recibido debe tener el rol {string}")
    public void elTokenRecibidoDebeTenerElRol(String rolEsperado) {
        assertThat(contexto.getUltimaRespuesta().path("rol").toString())
                .isEqualTo(rolEsperado);
    }
}
