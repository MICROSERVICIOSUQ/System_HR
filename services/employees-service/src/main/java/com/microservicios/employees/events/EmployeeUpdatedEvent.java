package com.microservicios.employees.events;

import com.microservicios.employees.model.Employee;

public final class EmployeeUpdatedEvent {
    public static final String TYPE = "empleado.actualizado";

    private EmployeeUpdatedEvent() {}

    public static EventEnvelope<EmployeeEventData> create(Employee employee) {
        return EventEnvelope.of(TYPE, EmployeeEventData.from(employee));
    }
}
