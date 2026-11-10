package com.microservicios.employees.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.groups.Default;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "employees")
@Schema(description = "Modelo canónico de datos del Empleado")
public class Employee {

    public interface OnCreate extends Default {}

    @Id
    @Column(nullable = false, updatable = false)
    @NotBlank(message = "El id es obligatorio", groups = OnCreate.class)
    @Pattern(regexp = "(?s).*\\S.*", message = "El id no puede estar vacío")
    @Schema(description = "Identificador único asignado al empleado", example = "E001")
    private String id;

    @NotBlank(message = "El nombre es obligatorio", groups = OnCreate.class)
    @Pattern(regexp = "(?s).*\\S.*", message = "El nombre no puede estar vacío")
    @Schema(description = "Nombre del empleado", example = "Juan")
    private String nombre;

    @NotBlank(message = "El apellido es obligatorio", groups = OnCreate.class)
    @Pattern(regexp = "(?s).*\\S.*", message = "El apellido no puede estar vacío")
    @Schema(description = "Apellido del empleado", example = "Pérez")
    private String apellido;

    @Column(nullable = false, unique = true)
    @NotBlank(message = "El email es obligatorio", groups = OnCreate.class)
    @Pattern(regexp = "(?s).*\\S.*", message = "El email no puede estar vacío")
    @Email(message = "El email no tiene un formato válido")
    @Schema(description = "Correo electrónico institucional único", example = "juan.perez@empresa.com")
    private String email;

    @Column(name = "employee_number", nullable = false, unique = true)
    @NotBlank(message = "El numeroEmpleado es obligatorio", groups = OnCreate.class)
    @Pattern(regexp = "(?s).*\\S.*", message = "El numeroEmpleado no puede estar vacío")
    @Schema(description = "Número o código único de empleado", example = "EMP-2026-001")
    private String numeroEmpleado;

    @NotBlank(message = "El cargo es obligatorio", groups = OnCreate.class)
    @Pattern(regexp = "(?s).*\\S.*", message = "El cargo no puede estar vacío")
    @Schema(description = "Cargo o puesto asignado", example = "Desarrollador Senior")
    private String cargo;

    @NotBlank(message = "El area es obligatoria", groups = OnCreate.class)
    @Pattern(regexp = "(?s).*\\S.*", message = "El area no puede estar vacía")
    @Schema(description = "Área funcional dentro de la organización", example = "Tecnología")
    private String area;

    @NotBlank(message = "El departamentoId es obligatorio", groups = OnCreate.class)
    @Pattern(regexp = "(?s).*\\S.*", message = "El departamentoId no puede estar vacío")
    @Schema(description = "Identificador del departamento al que pertenece", example = "D001")
    private String departamentoId;

    @NotNull(message = "La fechaIngreso es obligatoria", groups = OnCreate.class)
    @Schema(description = "Fecha de ingreso (formato yyyy-MM-dd)", example = "2026-02-10")
    private LocalDate fechaIngreso;

    @Column(name = "fecha_retiro")
    @Schema(description = "Fecha y hora del retiro lógico (ISO-8601). Solo presente cuando estado=RETIRADO.",
            example = "2026-10-07T14:32:18.455", nullable = true)
    // LocalDateTime y no LocalDate: el retiro es un hecho con hora, y esa hora
    // importa. Dos empleados retirados el mismo día no son indistinguibles —
    // para una auditoría laboral el orden en que ocurrieron puede ser relevante,
    // y con precisión de día esa información se perdía. Además alinea el tipo
    // con profiles-service, que ya almacenaba fechaRetiro como TIMESTAMP(3).
    private LocalDateTime fechaRetiro;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Schema(description = "Estado del empleado (inicialmente siempre ACTIVO)", example = "ACTIVO")
    private EmployeeStatus estado;

    public Employee() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    @JsonProperty("employeeId")
    @Schema(description = "Identificador único del empleado (alias de id)", example = "E001")
    public String getEmployeeId() { return id; }

    @JsonProperty("employeeId")
    public void setEmployeeId(String employeeId) {
        if (this.id == null || this.id.isBlank()) {
            this.id = employeeId;
        }
    }

    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }

    public String getApellido() { return apellido; }
    public void setApellido(String apellido) { this.apellido = apellido; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    @JsonProperty("numeroEmpleado")
    public String getEmployeeNumber() { return numeroEmpleado; }
    @JsonProperty("numeroEmpleado")
    public void setEmployeeNumber(String employeeNumber) { this.numeroEmpleado = employeeNumber; }

    public String getCargo() { return cargo; }
    public void setCargo(String cargo) { this.cargo = cargo; }

    public String getArea() { return area; }
    public void setArea(String area) { this.area = area; }

    public String getDepartamentoId() { return departamentoId; }
    public void setDepartamentoId(String departamentoId) { this.departamentoId = departamentoId; }

    public LocalDate getFechaIngreso() { return fechaIngreso; }
    public void setFechaIngreso(LocalDate fechaIngreso) { this.fechaIngreso = fechaIngreso; }

    @JsonProperty("fechaRetiro")
    public LocalDateTime getFechaRetiro() { return fechaRetiro; }
    @JsonProperty("fechaRetiro")
    public void setFechaRetiro(LocalDateTime fechaRetiro) { this.fechaRetiro = fechaRetiro; }

    @JsonProperty("estado")
    public EmployeeStatus getStatus() { return estado; }
    @JsonProperty("estado")
    public void setStatus(EmployeeStatus status) { this.estado = status; }
}
