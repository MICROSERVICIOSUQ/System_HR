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
        // La fecha del retiro sale de la entidad, no del reloj.
        //
        // Antes era LocalDate.now(), lo que tenía dos problemas. El evidente:
        // ignoraba el valor almacenado, así que si el evento se volvía a
        // publicar —una reentrega, una reconstrucción del read model— informaba
        // de la fecha de ese momento y no de la del retiro. El segundo: con
        // precisión de día se perdía la hora exacta, que es justamente lo que el
        // cambio a LocalDateTime vino a conservar, y los consumidores la
        // recibían a medianoche.
        String fechaRetiro = (employee.getStatus() == EmployeeStatus.RETIRED
                && employee.getFechaRetiro() != null)
                ? employee.getFechaRetiro().toString()
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
