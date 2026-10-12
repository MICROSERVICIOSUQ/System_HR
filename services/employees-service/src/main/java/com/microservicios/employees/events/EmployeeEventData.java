package com.microservicios.employees.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.microservicios.employees.model.Employee;
import com.microservicios.employees.model.EmployeeStatus;
import java.time.LocalDate;

/**
 * Payload de datos para eventos del dominio de empleados.
 * Cumple estrictamente con shared-events/schemas/empleado.schema.json.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record EmployeeEventData(
        @JsonProperty("id") String id,
        @JsonProperty("nombre") String nombre,
        @JsonProperty("apellido") String apellido,
        @JsonProperty("email") String email,
        @JsonProperty("departamentoId") String departamentoId,
        @JsonProperty("cargo") String cargo,
        @JsonProperty("fechaIngreso") String fechaIngreso,
        @JsonProperty("fechaRetiro") String fechaRetiro
) {
    public static EmployeeEventData from(Employee employee) {
        String fechaRetiro = (employee.getStatus() == EmployeeStatus.RETIRED)
                ? LocalDate.now().toString()
                : null;

        return new EmployeeEventData(
                employee.getId(),
                employee.getNombre(),
                employee.getApellido(),
                employee.getEmail(),
                employee.getDepartamentoId(),
                employee.getCargo(),
                employee.getFechaIngreso() != null ? employee.getFechaIngreso().toString() : null,
                fechaRetiro
        );
    }
}
