package com.microservicios.employees.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    public static final String BEARER_AUTH = "BearerAuth";

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Microservicio de Empleados")
                        .version("1.0.0")
                        .description("API REST para la gestión de empleados del sistema de Recursos Humanos. " +
                                "Permite registrar empleados validando la existencia de departamentos en tiempo real, " +
                                "consultar por identificador y listar el personal registrado.\n\n" +
                                "Todos los endpoints salvo el de salud exigen un Access JWT. El token lo emite " +
                                "`POST /auth/login` del auth-service y lo valida el api-gateway: pulse «Authorize» " +
                                "y pegue el token. Las operaciones de escritura (POST, DELETE) requieren rol ADMIN; " +
                                "el rol USER solo tiene acceso de lectura.")
                        .contact(new Contact().name("Equipo de Arquitectura y RRHH")))
                .components(new Components().addSecuritySchemes(BEARER_AUTH,
                        new SecurityScheme()
                                .name(BEARER_AUTH)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Access JWT obtenido en POST /auth/login")))
                // Requisito global: toda operación documentada espera el Bearer.
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }
}
