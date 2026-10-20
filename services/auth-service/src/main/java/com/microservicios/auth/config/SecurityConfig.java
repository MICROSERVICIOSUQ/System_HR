package com.microservicios.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Criptografía de credenciales.
 *
 * <p>Solo se usa {@code spring-security-crypto}, no el starter completo: la
 * validación del JWT en las peticiones entrantes la hace el api-gateway una única
 * vez (ADR-008), así que este servicio no necesita cadena de filtros propia.
 *
 * <p>BCrypt con factor de coste 10 (el predeterminado): el hash incluye su propia
 * sal, de modo que dos empleados con la misma contraseña tienen hashes distintos.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
