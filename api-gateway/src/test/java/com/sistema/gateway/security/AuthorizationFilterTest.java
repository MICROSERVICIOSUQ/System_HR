package com.sistema.gateway.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas de la autorización por propiedad del recurso (ABAC).
 *
 * <p>Es la regla que permite que cada empleado gestione su propio perfil sin
 * darle permisos de administrador, y la que impide que toque el de otro. No es
 * RBAC: el permiso no depende solo del rol, sino de quién es dueño del dato.
 *
 * <p>Se prueba el filtro directamente, con un {@link MockServerWebExchange} en
 * memoria, y no a través de un cliente HTTP. La razón es doble: la regla es
 * lógica pura y no necesita red para verificarse, y el filtro es el único sitio
 * donde vive —los microservicios de aguas abajo no tienen seguridad propia, así
 * que si esta clase se equivoca, no hay una segunda barrera que lo detenga.
 *
 * <p>La distinción entre 401 y 403 también se comprueba, porque es información
 * para quien llama: 401 significa «no sé quién eres» y 403 «sé quién eres y no
 * puedes». Responder 401 a un problema de permisos haría que un cliente intentara
 * autenticarse de nuevo una y otra vez sin que eso resolviera nada.
 */
class AuthorizationFilterTest {

    private static final String PROPIO = "E601";
    private static final String AJENO = "E602";

    private final AuthorizationFilter filtro = new AuthorizationFilter(
            new GatewaySecurityProperties(),
            new SecurityResponseWriter(new ObjectMapper()));

    /** Resultado de pasar una petición por el filtro. */
    private record Resultado(boolean seEnruto, HttpStatus codigo) {}

    private Resultado ejecutar(HttpMethod metodo, String ruta, AuthenticatedUser usuario) {
        MockServerHttpRequest peticion = MockServerHttpRequest.method(metodo, ruta).build();
        MockServerWebExchange exchange = MockServerWebExchange.from(peticion);
        if (usuario != null) {
            exchange.getAttributes().put(AuthenticatedUser.ATRIBUTO, usuario);
        }

        // Si la cadena se invoca, la petición se enrutó al microservicio; si no,
        // el filtro la cortó. Es la diferencia que hay que observar.
        AtomicBoolean seEnruto = new AtomicBoolean(false);
        filtro.filter(exchange, intercambio -> {
            seEnruto.set(true);
            return Mono.empty();
        }).block();

        return new Resultado(seEnruto.get(), HttpStatus.resolve(
                exchange.getResponse().getStatusCode() == null
                        ? 200
                        : exchange.getResponse().getStatusCode().value()));
    }

    private static AuthenticatedUser user(String empleadoId) {
        return new AuthenticatedUser(empleadoId, AuthenticatedUser.ROL_USER);
    }

    private static AuthenticatedUser admin() {
        return new AuthenticatedUser("admin", AuthenticatedUser.ROL_ADMIN);
    }

    @Nested
    @DisplayName("Un USER sobre su propio perfil")
    class PerfilPropio {

        @Test
        @DisplayName("PUT /perfiles/{suyo} se permite")
        void putPropioSePermite() {
            Resultado r = ejecutar(HttpMethod.PUT, "/perfiles/" + PROPIO, user(PROPIO));
            assertThat(r.seEnruto()).as("debía enrutarse al servicio de perfiles").isTrue();
        }

        @Test
        @DisplayName("PATCH /perfiles/{suyo} se permite")
        void patchPropioSePermite() {
            assertThat(ejecutar(HttpMethod.PATCH, "/perfiles/" + PROPIO, user(PROPIO)).seEnruto())
                    .isTrue();
        }

        @Test
        @DisplayName("el alias en inglés /profiles/{suyo} se permite igual")
        void aliasEnInglesSePermite() {
            assertThat(ejecutar(HttpMethod.PUT, "/profiles/" + PROPIO, user(PROPIO)).seEnruto())
                    .as("el servicio sirve las dos rutas; la regla debe cubrir las dos")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("Un USER sobre el perfil de otro")
    class PerfilAjeno {

        @Test
        @DisplayName("PUT /perfiles/{ajeno} responde 403 y no se enruta")
        void putAjenoSeRechaza() {
            Resultado r = ejecutar(HttpMethod.PUT, "/perfiles/" + AJENO, user(PROPIO));

            assertThat(r.codigo())
                    .as("403: la identidad es válida, el permiso no")
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(r.seEnruto())
                    .as("la petición no debe llegar al microservicio: es la única barrera")
                    .isFalse();
        }

        @Test
        @DisplayName("PATCH /perfiles/{ajeno} responde 403")
        void patchAjenoSeRechaza() {
            assertThat(ejecutar(HttpMethod.PATCH, "/perfiles/" + AJENO, user(PROPIO)).codigo())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("403 y no 401: el token era válido")
        void noSeConfundeConFaltaDeAutenticacion() {
            assertThat(ejecutar(HttpMethod.PUT, "/perfiles/" + AJENO, user(PROPIO)).codigo())
                    .isNotEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("LEER el perfil de otro sí se permite: la restricción es de escritura")
        void lecturaAjenaSePermite() {
            assertThat(ejecutar(HttpMethod.GET, "/perfiles/" + AJENO, user(PROPIO)).seEnruto())
                    .as("el rol USER tiene lectura sobre todo el ecosistema")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("Casos borde de la ruta")
    class CasosBorde {

        @Test
        @DisplayName("un subrecurso del propio perfil NO está autorizado")
        void subrecursoSeRechaza() {
            // /perfiles/E601/foto no es "su perfil": es otro recurso bajo él, y
            // la excepción concedida al rol USER es sobre el perfil, no sobre
            // todo lo que cuelgue de esa ruta. Conceder el subárbol completo
            // autorizaría de antemano endpoints que todavía no existen.
            Resultado r = ejecutar(HttpMethod.PUT, "/perfiles/" + PROPIO + "/foto", user(PROPIO));
            assertThat(r.codigo()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(r.seEnruto()).isFalse();
        }

        @Test
        @DisplayName("PUT /perfiles sin identificador se rechaza")
        void sinIdentificadorSeRechaza() {
            // Sin id no hay dueño que comparar, así que no se puede afirmar que
            // el recurso sea suyo. Ante la duda, se deniega.
            assertThat(ejecutar(HttpMethod.PUT, "/perfiles", user(PROPIO)).codigo())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("un USER no puede escribir en otro recurso aunque el id coincida")
        void otroRecursoConSuIdSeRechaza() {
            // La excepción es solo para /perfiles. Que el id de la ruta sea el
            // suyo no autoriza a modificar un empleado o un departamento.
            assertThat(ejecutar(HttpMethod.PUT, "/empleados/" + PROPIO, user(PROPIO)).codigo())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Nested
    @DisplayName("El rol ADMIN")
    class Administrador {

        @Test
        @DisplayName("puede escribir el perfil de cualquiera")
        void escribePerfilAjeno() {
            assertThat(ejecutar(HttpMethod.PUT, "/perfiles/" + AJENO, admin()).seEnruto()).isTrue();
        }

        @Test
        @DisplayName("no está sujeto a la regla de propiedad en ninguna ruta")
        void noEstaSujetoALaRegla() {
            assertThat(ejecutar(HttpMethod.DELETE, "/empleados/" + AJENO, admin()).seEnruto())
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("Sin identidad en el intercambio")
    class SinIdentidad {

        @Test
        @DisplayName("se responde 401 y no se enruta")
        void sinUsuarioSeRechazaCon401() {
            // No debería ocurrir —el filtro de autenticación corre antes y
            // corta— pero si ocurriera, denegar es la única respuesta segura.
            Resultado r = ejecutar(HttpMethod.PUT, "/perfiles/" + PROPIO, null);
            assertThat(r.codigo()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(r.seEnruto()).isFalse();
        }

        @Test
        @DisplayName("una ruta pública no necesita identidad")
        void rutaPublicaPasaSinUsuario() {
            assertThat(ejecutar(HttpMethod.POST, "/auth/login", null).seEnruto())
                    .as("/auth/login debe ser alcanzable sin token: es cómo se obtiene uno")
                    .isTrue();
        }
    }
}
