package com.microservicios.employees.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

@Entity
@Table(name = "employees")
public class Employee {

    @Id
    @Column(nullable = false, updatable = false)
    @NotBlank(message = "El id es obligatorio")
    private String id;

    @NotBlank(message = "El nombre es obligatorio")
    private String nombre;

    @NotBlank(message = "El apellido es obligatorio")
    private String apellido;

    @Column(nullable = false, unique = true)
    @NotBlank(message = "El email es obligatorio")
    @Email(message = "El email no tiene un formato válido")
    private String email;

    @Column(name = "employee_number", nullable = false, unique = true)
    @NotBlank(message = "El numeroEmpleado es obligatorio")
    private String numeroEmpleado;

    @NotBlank(message = "El cargo es obligatorio")
    private String cargo;

    @NotBlank(message = "El area es obligatoria")
    private String area;

    @NotBlank(message = "El departamentoId es obligatorio")
    private String departamentoId;

    @NotNull(message = "La fechaIngreso es obligatoria")
    private LocalDate fechaIngreso;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @NotNull(message = "El estado es obligatorio")
    private EmployeeStatus estado;

    public Employee() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

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

    @JsonProperty("estado")
    public EmployeeStatus getStatus() { return estado; }
    @JsonProperty("estado")
    public void setStatus(EmployeeStatus status) { this.estado = status; }
}
