package com.microservicios.employees.events;

import com.microservicios.employees.model.Employee;

public final class EmployeeCreatedEvent {
    public static final String TYPE = "empleado.creado";

    private EmployeeCreatedEvent() {}

    public static EventEnvelope<EmployeeEventData> create(Employee employee) {
        return EventEnvelope.of(TYPE, EmployeeEventData.from(employee));
    }
}
