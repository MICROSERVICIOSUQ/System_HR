package com.microservicios.employees.events;

import com.microservicios.employees.model.Employee;

public final class EmployeeRetiredEvent {
    public static final String TYPE = "empleado.retirado";

    private EmployeeRetiredEvent() {}

    public static EventEnvelope<EmployeeEventData> create(Employee employee) {
        return EventEnvelope.of(TYPE, EmployeeEventData.from(employee));
    }
}
