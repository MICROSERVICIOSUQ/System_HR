package com.sistema.gateway.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.ArrayList;
import java.util.List;

/**
 * Rutas que no exigen token.
 *
 * <p>Son las imprescindibles para poder conseguir uno ({@code /auth/login},
 * {@code /auth/recover-password}, {@code /auth/reset-password}), las de salud que
 * Docker necesita para el healthcheck, y las de documentación.
 *
 * <p>{@code /auth/reset-password} es pública a propósito: su autorización no viene
 * de una sesión previa sino del token de un solo propósito que llega en el cuerpo.
 * Exigir un Access JWT ahí haría imposible la activación inicial, que es
 * precisamente el momento en el que el usuario todavía no puede iniciar sesión.
 */
@Component
@ConfigurationProperties(prefix = "gateway.security")
public class GatewaySecurityProperties {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private List<String> publicPaths = new ArrayList<>(List.of(
            "/auth/login",
            "/auth/recover-password",
            "/auth/reset-password",
            "/health",
            "/actuator/**",
            "/metrics",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/webjars/**",
            "/v3/api-docs/**"
    ));

    public List<String> getPublicPaths() {
        return publicPaths;
    }

    public void setPublicPaths(List<String> publicPaths) {
        this.publicPaths = publicPaths;
    }

    public boolean esPublica(String path) {
        return publicPaths.stream().anyMatch(patron -> MATCHER.match(patron, path));
    }
}
