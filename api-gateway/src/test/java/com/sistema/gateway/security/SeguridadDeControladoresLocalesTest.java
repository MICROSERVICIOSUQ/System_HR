package com.sistema.gateway.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.channels.Selector;

/**
 * Verifica que los controladores <em>locales</em> del gateway pasan por los
 * filtros de seguridad.
 *
 * <p>Es la prueba del agujero que tenía {@code GET /empleados/{id}/completo}.
 * Mientras {@link JwtAuthenticationFilter} y {@link AuthorizationFilter} fueron
 * {@code GlobalFilter}, solo se ejecutaban dentro de la cadena de rutas de Spring
 * Cloud Gateway. Ese endpoint no es una ruta: lo atiende un {@code @RestController}
 * del propio gateway y, como en WebFlux el {@code RequestMappingHandlerMapping}
 * (orden 0) gana al {@code RoutePredicateHandlerMapping} (orden 1), la petición
 * se resolvía localmente y los filtros no la veían. El expediente completo de un
 * empleado se servía sin token.
 *
 * <p>Se usa el entorno {@code MOCK}: el {@link WebTestClient} se enlaza al
 * contexto con {@code WebHttpHandlerBuilder}, que <strong>sí aplica los
 * WebFilter</strong> y no arranca un servidor. El 401 que comprueba es el del
 * filtro real, no el de un doble.
 *
 * <p><strong>Se omite en equipos sin sockets locales.</strong> Aunque no haya
 * servidor, el contexto crea el {@code ClientHttpConnector} de reactor-netty, y
 * eso necesita un {@code Selector}, que en Windows se construye con un par de
 * sockets en loopback. Donde el sistema lo impide —el mismo motivo por el que
 * fallan {@code GatewayTest} y las pruebas de caché de employees-service— la
 * clase se salta en lugar de fallar, y se ejecuta en el pipeline, que corre en
 * Linux. El resto de la seguridad sí se verifica sin contexto en
 * {@link AuthorizationFilterTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@EnabledIf("elEntornoPermiteSocketsLocales")
class SeguridadDeControladoresLocalesTest {

    @Autowired
    private WebTestClient client;

    static boolean elEntornoPermiteSocketsLocales() {
        try (Selector sonda = Selector.open()) {
            return sonda.isOpen();
        } catch (Throwable sinSockets) {
            return false;
        }
    }

    @Test
    @DisplayName("el endpoint agregado sin token responde 401, no 200")
    void endpointAgregadoSinTokenDevuelve401() {
        client.get().uri("/empleados/E601/completo")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.status").isEqualTo(401);
    }

    @Test
    @DisplayName("un token malformado tampoco abre el endpoint agregado")
    void endpointAgregadoConTokenInvalidoDevuelve401() {
        client.get().uri("/empleados/E601/completo")
                .header("Authorization", "Bearer esto-no-es-un-jwt")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("las cabeceras de identidad del cliente no abren el endpoint agregado")
    void cabecerasDeIdentidadFalsificadasNoBastan() {
        // El filtro borra X-Auth-User y X-Auth-Role antes de validar. Sin eso,
        // bastaría enviarlas para que el gateway creyera la identidad.
        client.get().uri("/empleados/E601/completo")
                .header(AuthenticatedUser.HEADER_USUARIO, "E601")
                .header(AuthenticatedUser.HEADER_ROL, AuthenticatedUser.ROL_ADMIN)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("la salud del gateway sigue siendo pública")
    void saludSigueSiendoPublica() {
        // Al pasar los filtros a WebFilter se aplican a todo, así que hay que
        // comprobar que la lista de rutas públicas sigue dejando entrar al
        // healthcheck de Docker: si no, el contenedor nunca estaría healthy.
        client.get().uri("/health")
                .exchange()
                .expectBody()
                .jsonPath("$.service").isEqualTo("api-gateway");
    }
}
