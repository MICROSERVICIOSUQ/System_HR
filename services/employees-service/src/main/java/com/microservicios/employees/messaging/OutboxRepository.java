package com.microservicios.employees.messaging;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Acceso a los eventos pendientes de publicar. */
public interface OutboxRepository extends JpaRepository<OutboxEvento, String> {

    /**
     * Pendientes, del más antiguo al más reciente y con un tope.
     *
     * <p>El orden por fecha de creación conserva la secuencia en la que
     * ocurrieron los hechos, que es lo que un consumidor espera: el
     * {@code empleado.creado} de un empleado antes que su
     * {@code empleado.retirado}.
     *
     * <p>El tope evita que, tras una caída larga del broker, un solo ciclo
     * intente enviar miles de eventos y se quede sin tiempo: se vacía en varios
     * ciclos y el servicio sigue atendiendo peticiones mientras tanto.
     */
    List<OutboxEvento> findByPublicadoEnIsNullOrderByCreadoEnAsc(Limit limite);

    long countByPublicadoEnIsNull();
}
