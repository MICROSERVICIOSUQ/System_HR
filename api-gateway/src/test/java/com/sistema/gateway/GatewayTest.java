package com.sistema.gateway;

import java.time.Duration;
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

@org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayTest {
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
        registry.add("spring.cloud.gateway.httpclient.response-timeout", () -> "200ms");
    }

    @Autowired WebTestClient client;
    @AfterAll static void close() { upstream.disposeNow(); }

    @Test void ownHealth() {
        client.get().uri("/health").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.service").isEqualTo("api-gateway");
    }
    @Test void forwardsRootAndNestedPathsAndQuery() {
        for (String path : new String[]{"/empleados", "/empleados/E1?detalle=true"}) {
            client.get().uri(path).exchange().expectStatus().isAccepted()
                    .expectHeader().valueEquals("X-Upstream-Uri", path)
                    .expectBody(String.class).isEqualTo("ok");
        }
    }
    @Test void forwardsBodyHeadersAndStatus() {
        client.post().uri("/empleados").header("X-Trace", "trace-123")
                .header("Content-Type", "application/json").bodyValue("{\"nombre\":\"Ana\"}")
                .exchange().expectStatus().isAccepted()
                .expectHeader().valueEquals("X-Trace", "trace-123")
                .expectBody(String.class).isEqualTo("{\"nombre\":\"Ana\"}");
    }
    @Test void preservesDownstreamBusinessError() {
        client.get().uri("/empleados/rechazado").exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("rechazado");
    }
    @Test void unavailableDepartmentReturnsDescriptive503() {
        client.get().uri("/departamentos").exchange().expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.message").isEqualTo("El servicio de destino no está disponible temporalmente");
    }
    @Test void timeoutReturns503() {
        client.mutate().responseTimeout(Duration.ofSeconds(3)).build()
                .get().uri("/empleados/lento").exchange().expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.status").isEqualTo(503);
    }
}
