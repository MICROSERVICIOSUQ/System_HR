package com.sistema.gateway;

import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * Enrutado, propagación y errores del gateway.
 *
 * <p>Las peticiones van firmadas porque desde el Reto 5 el gateway exige un JWT
 * en toda ruta que no sea pública. Estas pruebas son del Reto 3 y nunca se
 * actualizaron: proxificaban sin token y esperaban 202, así que desde entonces
 * respondían 401 y fallaban en cualquier equipo capaz de ejecutarlas. Aquí no se
 * podían ni ejecutar —Netty no puede crear su event loop sin sockets en
 * loopback— y por eso el fallo pasaba desapercibido.
 *
 * <p>El token se firma con el secreto de desarrollo que trae
 * {@code application.yml} por omisión, el mismo que valida
 * {@link com.sistema.gateway.security.JwtTokenValidator}. Firmarlo aquí, en lugar
 * de declarar las rutas públicas en la configuración de la prueba, mantiene la
 * cadena de seguridad dentro de lo que se prueba: si alguien rompe la validación
 * del JWT, estas pruebas también lo notan.
 */
@org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability(tracing = false)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayTest {

    @Test
    void metricsEsPublicoYConservaElFormatoPrometheus() {
        client.get().uri("/metrics").exchange().expectStatus().isOk()
                .expectBody(String.class).value(body ->
                        org.assertj.core.api.Assertions.assertThat(body).contains("# HELP"));
    }

    /** El mismo valor por omisión de jwt.secret en application.yml. */
    private static final String SECRETO =
            "cambie-este-secreto-de-desarrollo-por-uno-de-32-bytes-o-mas";

    private static final String TOKEN_ADMIN = Jwts.builder()
            .header().type("JWT").and()
            .issuer("system-hr-auth")
            .subject("admin")
            .claim("role", "ADMIN")
            .issuedAt(Date.from(Instant.now()))
            .expiration(Date.from(Instant.now().plusSeconds(3600)))
            .signWith(new SecretKeySpec(SECRETO.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
            .compact();
    static final DisposableServer upstream = HttpServer.create().port(0).handle((request, response) -> {
        if (request.uri().contains("lento")) return Mono.never();
        if (request.uri().contains("rechazado")) {
            return response.status(400).header("Content-Type", "application/json")
                    .sendString(Mono.just("{\"error\":\"rechazado\"}"));
        }
        return request.receive().aggregate().asString().defaultIfEmpty("").flatMap(body ->
                response.status(202).header("Content-Type", "text/plain")
                        .header("X-Trace", request.requestHeaders().get("X-Trace", "none"))
                        .header("X-Upstream-Uri", request.uri())
                        .sendString(Mono.just(body.isEmpty() ? "ok" : body)).then());
    }).bindNow();
    // Reserve then release an ephemeral port, so the failure is a real refused connection.
    static final int unavailablePort = closedPort();
    static int closedPort() {
        var server = HttpServer.create().port(0).bindNow();
        int port = server.port();
        server.disposeNow();
        return port;
    }

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        registry.add("EMPLEADOS_URL", () -> "http://localhost:" + upstream.port());
        registry.add("DEPARTAMENTOS_URL", () -> "http://localhost:" + unavailablePort);
        // 2s y no 200ms. El timeout se baja para que la prueba del upstream
        // lento no tarde, pero 200ms es menos de lo que tarda una petición
        // normal con un JVM recién arrancado y la máquina cargada: en el
        // pipeline, forwardsBodyHeadersAndStatus y preservesDownstreamBusinessError
        // recibían 503 por timeout en lugar de la respuesta del upstream. Con 2s
        // la prueba del lento sigue siendo determinista —su handler devuelve
        // Mono.never(), así que no responde nunca— y las demás dejan de ser
        // sensibles a la carga.
        registry.add("spring.cloud.gateway.httpclient.response-timeout", () -> "2s");
    }

    @Autowired WebTestClient client;
    @AfterAll static void close() { upstream.disposeNow(); }

    @Test void ownHealth() {
        client.get().uri("/health").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.service").isEqualTo("api-gateway");
    }
    @Test void forwardsRootAndNestedPathsAndQuery() {
        for (String path : new String[]{"/empleados", "/empleados/E1?detalle=true"}) {
            client.get().uri(path).header("Authorization", "Bearer " + TOKEN_ADMIN)
                    .exchange().expectStatus().isAccepted()
                    .expectHeader().valueEquals("X-Upstream-Uri", path)
                    .expectBody(String.class).isEqualTo("ok");
        }
    }

    @Test void requiresTokenToProxy() {
        // La otra cara de la prueba anterior: la misma ruta, sin token, no llega
        // al microservicio. Es lo que el Reto 5 añadió y lo que estas pruebas
        // daban por inexistente.
        client.get().uri("/empleados").exchange().expectStatus().isUnauthorized();
    }
    @Test void forwardsBodyHeadersAndStatus() {
        client.post().uri("/empleados").header("X-Trace", "trace-123")
                .header("Authorization", "Bearer " + TOKEN_ADMIN)
                .header("Content-Type", "application/json").bodyValue("{\"nombre\":\"Ana\"}")
                .exchange().expectStatus().isAccepted()
                .expectHeader().valueEquals("X-Trace", "trace-123")
                .expectBody(String.class).isEqualTo("{\"nombre\":\"Ana\"}");
    }
    @Test void preservesDownstreamBusinessError() {
        client.get().uri("/empleados/rechazado")
                .header("Authorization", "Bearer " + TOKEN_ADMIN).exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("rechazado");
    }
    @Test void unavailableDepartmentReturnsDescriptive503() {
        client.get().uri("/departamentos")
                .header("Authorization", "Bearer " + TOKEN_ADMIN).exchange().expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.message").isEqualTo("El servicio de destino no está disponible temporalmente");
    }
    @Test void timeoutReturns503() {
        client.mutate().responseTimeout(Duration.ofSeconds(8)).build()
                .get().uri("/empleados/lento")
                .header("Authorization", "Bearer " + TOKEN_ADMIN).exchange().expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.status").isEqualTo(503);
    }
}
