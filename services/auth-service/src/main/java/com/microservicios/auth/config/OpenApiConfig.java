package com.microservicios.auth.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Documentación OpenAPI del auth-service con el esquema de seguridad BearerAuth,
 * para poder pegar el JWT en el botón «Authorize» de Swagger UI y probar
 * {@code /auth/change-password} sin salir del navegador.
 */
@Configuration
public class OpenApiConfig {

    public static final String BEARER_AUTH = "BearerAuth";

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Microservicio de Autenticación")
                        .version("1.0.0")
                        .description("""
                                Proveedor de identidad del sistema de Recursos Humanos.

                                Verifica credenciales, emite Access JWT firmados con HS256 y gestiona el
                                ciclo de vida de las cuentas a partir de los eventos del ecosistema:
                                alta desde `empleado.creado`, suspensión por `vacaciones.iniciadas`,
                                reactivación por `vacaciones.finalizadas` y baja definitiva por
                                `empleado.retirado`.

                                Los endpoints `/auth/login`, `/auth/recover-password` y
                                `/auth/reset-password` son públicos. `/auth/change-password` exige un
                                Access JWT válido: pulse «Authorize» y pegue el token devuelto por el login.
                                """)
                        .contact(new Contact().name("Equipo de Arquitectura y RRHH")))
                .components(new Components().addSecuritySchemes(BEARER_AUTH,
                        new SecurityScheme()
                                .name(BEARER_AUTH)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Access JWT obtenido en POST /auth/login")));
    }
}
