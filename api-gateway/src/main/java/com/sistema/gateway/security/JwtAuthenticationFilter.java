package com.sistema.gateway.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import static net.logstash.logback.argument.StructuredArguments.kv;
import reactor.core.publisher.Mono;

/**
 * Paso 1 del borde: <strong>autenticación</strong>.
 *
 * <p>Intercepta toda petición antes del enrutado, exige el token en
 * {@code Authorization: Bearer <jwt>}, verifica la firma y, si es válida, propaga
 * la identidad a los microservicios en las cabeceras {@code X-Auth-User} y
 * {@code X-Auth-Role}.
 *
 * <p>Escribir esto una sola vez en el borde es la razón de elegir el gateway
 * frente a interceptores por servicio: hay seis servicios y la alternativa
 * obligaría a repetir —y mantener sincronizada— la misma validación en cada uno.
 *
 * <p><strong>Las cabeceras de identidad que lleguen del cliente se eliminan</strong>
 * antes de validar. Sin eso, cualquiera podría enviar {@code X-Auth-Role: ADMIN} y
 * los servicios, que confían en el gateway, lo creerían.
 *
 * <p><strong>Es un {@link WebFilter} y no un {@code GlobalFilter}.</strong> Un
 * {@code GlobalFilter} solo se ejecuta dentro de la cadena de <em>rutas</em> de
 * Spring Cloud Gateway, así que no veía las peticiones que atiende un
 * {@code @RestController} del propio gateway: en WebFlux el
 * {@code RequestMappingHandlerMapping} (orden 0) gana al
 * {@code RoutePredicateHandlerMapping} (orden 1), de modo que
 * {@code GET /empleados/{id}/completo} se resolvía localmente y sin validar el
 * token. Un {@code WebFilter} envuelve a todos los manejadores, enrutados o no,
 * y cierra esa puerta también para cualquier controlador que se añada después.
 */
@Component
public class JwtAuthenticationFilter implements WebFilter, Ordered {

    /** Se ejecuta antes del filtro de autorización y del enrutado. */
    public static final int ORDEN = -100;

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String PREFIJO_BEARER = "Bearer ";

    private final JwtTokenValidator validator;
    private final GatewaySecurityProperties properties;
    private final SecurityResponseWriter responseWriter;

    public JwtAuthenticationFilter(JwtTokenValidator validator,
                                   GatewaySecurityProperties properties,
                                   SecurityResponseWriter responseWriter) {
        this.validator = validator;
        this.properties = properties;
        this.responseWriter = responseWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        // Nunca se confía en una identidad que venga del cliente.
        ServerHttpRequest limpia = exchange.getRequest().mutate()
                .headers(h -> {
                    h.remove(AuthenticatedUser.HEADER_USUARIO);
                    h.remove(AuthenticatedUser.HEADER_ROL);
                })
                .build();
        ServerWebExchange saneado = exchange.mutate().request(limpia).build();

        if (properties.esPublica(path)) {
            return chain.filter(saneado);
        }

        String cabecera = limpia.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (cabecera == null || !cabecera.startsWith(PREFIJO_BEARER)) {
            log.warn("[GATEWAY] 401 sin token | {} {}", limpia.getMethod(), path,
                    kv("metodo", String.valueOf(limpia.getMethod())), kv("ruta", path),
                    kv("resultado", "401"), kv("causa", "sin cabecera Authorization"));
            return responseWriter.escribir(saneado, HttpStatus.UNAUTHORIZED,
                    "Se requiere un token de acceso en la cabecera Authorization: Bearer <token>");
        }

        String token = cabecera.substring(PREFIJO_BEARER.length()).trim();

        AuthenticatedUser usuario;
        try {
            usuario = validator.validar(token);
        } catch (JwtTokenValidator.TokenRechazadoException ex) {
            log.warn("[GATEWAY] 401 token rechazado | {} {} | causa='{}'",
                    limpia.getMethod(), path, ex.getMessage(),
                    kv("metodo", String.valueOf(limpia.getMethod())), kv("ruta", path),
                    kv("resultado", "401"), kv("causa", ex.getMessage()));
            return responseWriter.escribir(saneado, HttpStatus.UNAUTHORIZED, ex.getMessage());
        }

        ServerHttpRequest conIdentidad = limpia.mutate()
                .header(AuthenticatedUser.HEADER_USUARIO, usuario.empleadoId())
                .header(AuthenticatedUser.HEADER_ROL, usuario.rol())
                .build();

        ServerWebExchange autenticado = saneado.mutate().request(conIdentidad).build();
        autenticado.getAttributes().put(AuthenticatedUser.ATRIBUTO, usuario);

        log.debug("[GATEWAY] Autenticado | empleado='{}' | rol='{}' | {} {}",
                usuario.empleadoId(), usuario.rol(), conIdentidad.getMethod(), path,
                kv("empleadoId", usuario.empleadoId()), kv("rol", usuario.rol()),
                kv("metodo", String.valueOf(conIdentidad.getMethod())), kv("ruta", path));

        return chain.filter(autenticado);
    }

    @Override
    public int getOrder() {
        return ORDEN;
    }
}
