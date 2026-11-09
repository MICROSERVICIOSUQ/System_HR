package com.sistema.gateway.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import static net.logstash.logback.argument.StructuredArguments.kv;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;

/**
 * Paso 2 del borde: <strong>autorización</strong>.
 *
 * <p>Aplica las reglas del enunciado sobre la identidad que dejó el filtro de
 * autenticación:
 *
 * <pre>
 *   rol == ADMIN                                        → permitir
 *   rol == USER y método de lectura                     → permitir
 *   rol == USER y POST /auth/change-password            → permitir (su propia contraseña)
 *   rol == USER y PUT|PATCH /perfiles/{id} con id==sub  → permitir (su propio perfil)
 *   cualquier otro caso                                 → 403 Forbidden
 * </pre>
 *
 * <p>Las dos últimas reglas son autorización por <em>propiedad del recurso</em>
 * (ABAC): el permiso no depende solo del rol sino de quién es dueño del dato. Es
 * lo que permite que cada empleado gestione su perfil sin darle permisos de
 * administrador.
 *
 * <p>La distinción entre 401 y 403 es deliberada: 401 significa «no sé quién eres»
 * y lo emite el filtro anterior; 403 significa «sé quién eres y no puedes».
 *
 * <p>Es un {@link WebFilter} por el mismo motivo que
 * {@link JwtAuthenticationFilter}: las reglas tienen que aplicarse también a los
 * controladores locales del gateway, no solo a las peticiones que se enrutan.
 */
@Component
public class AuthorizationFilter implements WebFilter, Ordered {

    /** Justo después de la autenticación, antes del enrutado. */
    public static final int ORDEN = JwtAuthenticationFilter.ORDEN + 1;

    private static final Logger log = LoggerFactory.getLogger(AuthorizationFilter.class);

    /** Métodos que no modifican estado. */
    private static final Set<HttpMethod> LECTURA =
            Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS);

    /** Prefijos del recurso «perfil», que acepta alias en español e inglés. */
    private static final List<String> PREFIJOS_PERFIL = List.of("/perfiles/", "/profiles/");

    private static final String RUTA_CAMBIO_PASSWORD = "/auth/change-password";

    private final GatewaySecurityProperties properties;
    private final SecurityResponseWriter responseWriter;

    public AuthorizationFilter(GatewaySecurityProperties properties,
                               SecurityResponseWriter responseWriter) {
        this.properties = properties;
        this.responseWriter = responseWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (properties.esPublica(path)) {
            return chain.filter(exchange);
        }

        AuthenticatedUser usuario =
                exchange.getAttribute(AuthenticatedUser.ATRIBUTO);
        if (usuario == null) {
            // No debería ocurrir: el filtro de autenticación corre antes y corta.
            // Si ocurriera, denegar es la única respuesta segura.
            log.error("[GATEWAY] Sin identidad en el exchange para una ruta protegida | {}", path,
                    kv("ruta", path), kv("resultado", "401"));
            return responseWriter.escribir(exchange, HttpStatus.UNAUTHORIZED,
                    "Se requiere un token de acceso válido");
        }

        HttpMethod metodo = exchange.getRequest().getMethod();

        if (estaPermitido(usuario, metodo, path)) {
            return chain.filter(exchange);
        }

        // Los kv van después de los argumentos de los {}: SLF4J ignora los
        // sobrantes al formatear, así que el mensaje no cambia y el dato queda
        // además dentro de context, donde Loki puede filtrarlo.
        log.warn("[GATEWAY] 403 | empleado='{}' | rol='{}' | {} {}",
                usuario.empleadoId(), usuario.rol(), metodo, path,
                kv("empleadoId", usuario.empleadoId()), kv("rol", usuario.rol()),
                kv("metodo", String.valueOf(metodo)), kv("ruta", path),
                kv("resultado", "403"));
        return responseWriter.escribir(exchange, HttpStatus.FORBIDDEN,
                "El rol " + usuario.rol() + " no tiene permiso para " + metodo + " " + path);
    }

    private boolean estaPermitido(AuthenticatedUser usuario, HttpMethod metodo, String path) {
        if (usuario.esAdmin()) {
            return true;
        }
        if (LECTURA.contains(metodo)) {
            return true;
        }
        if (HttpMethod.POST.equals(metodo) && RUTA_CAMBIO_PASSWORD.equals(path)) {
            return true;
        }
        if (HttpMethod.PUT.equals(metodo) || HttpMethod.PATCH.equals(metodo)) {
            String duenoDelRecurso = empleadoIdDeRutaDePerfil(path);
            return duenoDelRecurso != null && usuario.esDuenoDe(duenoDelRecurso);
        }
        return false;
    }

    /**
     * Extrae el {@code empleadoId} de una ruta de perfil.
     *
     * @return el identificador, o {@code null} si la ruta no es de un perfil
     *         concreto (por ejemplo {@code /perfiles} sin id, o un subrecurso
     *         más profundo como {@code /perfiles/E001/foto}, que no está entre
     *         las excepciones concedidas al rol USER)
     */
    private static String empleadoIdDeRutaDePerfil(String path) {
        for (String prefijo : PREFIJOS_PERFIL) {
            if (path.startsWith(prefijo)) {
                String resto = path.substring(prefijo.length());
                if (resto.isEmpty() || resto.contains("/")) {
                    return null;
                }
                return resto;
            }
        }
        return null;
    }

    @Override
    public int getOrder() {
        return ORDEN;
    }
}
