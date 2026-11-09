package com.sistema.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas del cliente del endpoint agregado {@code GET /empleados/{id}/completo}.
 *
 * <p>Lo que se verifica es, sobre todo, <strong>la ruta exacta que se pide a cada
 * microservicio</strong>. Puede parecer una prueba trivial, pero es la que
 * faltaba: el cliente pedía {@code /perfiles/empleadoId/{id}} y
 * {@code /vacaciones/empleadoId/{id}}, rutas que no existen en ninguno de los dos
 * servicios. Respondían 404, el circuit breaker ejecutaba su fallback y el
 * endpoint devolvía 200 con {@code perfil: null} y {@code vacaciones: null}
 * <em>siempre</em>. El fallback tapaba el error, así que desde fuera parecía que
 * los servicios estaban caídos.
 *
 * <p>No hace falta red: se sustituye la {@code ExchangeFunction} del WebClient
 * por una que registra la ruta pedida y devuelve la respuesta que convenga. Es
 * también la razón de que estas pruebas sí se ejecuten en equipos donde
 * {@code GatewayTest} no puede abrir un socket.
 */
class EmpleadoCompletoClientTest {

    private static final String EMPLEADO = "E601";

    private final List<String> rutasPedidas = new ArrayList<>();

    /** Cliente cuyo WebClient responde siempre lo mismo, anotando qué se pidió. */
    private EmpleadoCompletoClient clienteQueResponde(ClientResponse respuesta) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(peticion -> {
            rutasPedidas.add(peticion.url().getPath());
            return Mono.just(respuesta);
        });
        return new EmpleadoCompletoClient(
                builder,
                "http://employees-service:8080",
                "http://profiles-service:8083",
                "http://vacations-service:8084");
    }

    private EmpleadoCompletoClient clienteOk() {
        return clienteQueResponde(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body("{\"empleadoId\":\"" + EMPLEADO + "\"}")
                .build());
    }

    private EmpleadoCompletoClient clienteConEstado(HttpStatus estado) {
        return clienteQueResponde(ClientResponse.create(estado)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body("{\"message\":\"sin datos\"}")
                .build());
    }

    // ── Las rutas ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("el empleado se consulta en /empleados/{id}")
    void rutaDelEmpleado() {
        clienteOk().getEmployee(EMPLEADO, "trace").join();
        assertThat(rutasPedidas).containsExactly("/empleados/" + EMPLEADO);
    }

    @Test
    @DisplayName("el perfil se consulta en /perfiles/{id}, de un solo segmento")
    void rutaDelPerfil() {
        clienteOk().getProfile(EMPLEADO, "trace").join();

        // profiles-service monta su router en /perfiles y resuelve el perfil con
        // router.get('/:empleadoId'). Un segmento extra no coincide: Express
        // responde 404.
        assertThat(rutasPedidas).containsExactly("/perfiles/" + EMPLEADO);
    }

    @Test
    @DisplayName("las vacaciones se consultan en /vacaciones/balances/{id}")
    void rutaDeLasVacaciones() {
        clienteOk().getVacations(EMPLEADO, "trace").join();

        // Es el recurso que vacations-service identifica por empleado y el único
        // que devuelve un objeto; /vacaciones/{id} es un período, no un empleado.
        assertThat(rutasPedidas).containsExactly("/vacaciones/balances/" + EMPLEADO);
    }

    // ── El 404 de una dependencia opcional no es un fallo ────────────────────

    @Test
    @DisplayName("un empleado sin perfil devuelve ausencia, no excepción")
    void perfilInexistenteEsAusencia() throws Exception {
        CompletableFuture<JsonNode> perfil =
                clienteConEstado(HttpStatus.NOT_FOUND).getProfile(EMPLEADO, "trace");

        // El read model se construye por eventos y puede ir por detrás: que
        // todavía no exista el perfil es una respuesta correcta del servicio. Si
        // se tratara como error, el circuit breaker se abriría por algo que
        // funciona.
        assertThat(perfil.get()).isNull();
        assertThat(perfil.isCompletedExceptionally()).isFalse();
    }

    @Test
    @DisplayName("un empleado sin saldo de vacaciones devuelve ausencia, no excepción")
    void saldoInexistenteEsAusencia() throws Exception {
        CompletableFuture<JsonNode> vacaciones =
                clienteConEstado(HttpStatus.NOT_FOUND).getVacations(EMPLEADO, "trace");

        assertThat(vacaciones.get()).isNull();
        assertThat(vacaciones.isCompletedExceptionally()).isFalse();
    }

    @Test
    @DisplayName("un 500 de perfiles sí es un fallo y llega al circuit breaker")
    void errorDelServidorSiEsFallo() {
        CompletableFuture<JsonNode> perfil = clienteConEstado(HttpStatus.INTERNAL_SERVER_ERROR)
                .getProfile(EMPLEADO, "trace");

        // La distinción es la que importa: el 404 se absorbe, el 5xx no. Si todo
        // se absorbiera, el circuito nunca se abriría y el endpoint seguiría
        // llamando a un servicio caído en cada petición.
        assertThatThrownBy(perfil::get)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(WebClientResponseException.InternalServerError.class);
    }

    @Test
    @DisplayName("un empleado inexistente propaga el 404: es obligatorio")
    void empleadoInexistentePropagaElError() {
        CompletableFuture<JsonNode> empleado = clienteConEstado(HttpStatus.NOT_FOUND)
                .getEmployee(EMPLEADO, "trace");

        // Sin empleado no hay nada que agregar, así que aquí el 404 no se
        // absorbe: el controlador lo traduce en el 404 de la respuesta.
        assertThatThrownBy(empleado::get)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(WebClientResponseException.NotFound.class);
    }
}
