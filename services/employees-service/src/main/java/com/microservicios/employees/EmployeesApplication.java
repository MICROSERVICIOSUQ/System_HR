package com.microservicios.employees;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.retry.annotation.EnableRetry;

// @EnableScheduling activa el publicador periódico del outbox. Sin esta
// anotación el @Scheduled de PublicadorDeOutbox no se registra y los eventos se
// quedan en la tabla: la mitad del patrón funcionaría —se guardan— y la otra no.
@EnableScheduling
@SpringBootApplication
@EnableRetry
public class EmployeesApplication {
    public static void main(String[] args) {
        SpringApplication.run(EmployeesApplication.class, args);
    }
}
