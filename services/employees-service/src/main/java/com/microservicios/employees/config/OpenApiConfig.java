package com.microservicios.employees.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Microservicio de Empleados")
                        .version("1.0.0")
                        .description("API REST para la gestión de empleados del sistema de Recursos Humanos. " +
                                "Permite registrar empleados validando la existencia de departamentos en tiempo real, " +
                                "consultar por identificador y listar el personal registrado.")
                        .contact(new Contact().name("Equipo de Arquitectura y RRHH")));
    }
}
