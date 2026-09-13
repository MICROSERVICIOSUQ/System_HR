package com.microservicios.employees.controller;

import com.microservicios.employees.exception.ErrorResponse;
import com.microservicios.employees.model.Employee;
import com.microservicios.employees.service.EmployeeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/empleados")
@Tag(name = "Empleados", description = "Endpoints para la gestión, consulta y registro de empleados")
public class EmployeeController {

    private final EmployeeService service;

    public EmployeeController(EmployeeService service) {
        this.service = service;
    }

    @GetMapping("/health")
    @Operation(summary = "Verificar estado del servicio", description = "Retorna el estado de disponibilidad del microservicio para health checks.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Servicio operativo",
                    content = @Content(schema = @Schema(example = "{\"status\":\"ok\",\"service\":\"empleados\"}")))
    })
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "ok", "service", "empleados"));
    }

    @PostMapping
    @Operation(summary = "Registrar un nuevo empleado",
            description = "Registra un empleado validando formato, unicidad de campos y verificando que el departamento exista en departments-service.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Empleado registrado exitosamente",
                    content = @Content(schema = @Schema(implementation = Employee.class))),
            @ApiResponse(responseCode = "400", description = "Datos de entrada inválidos o departamento inexistente",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Conflicto por duplicidad de id, email o número de empleado",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "Base de datos o servicio de departamentos no disponible",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Employee> register(@Valid @RequestBody Employee employee) {
        return ResponseEntity.status(201).body(service.register(employee));
    }

    @GetMapping
    @Operation(summary = "Listar todos los empleados", description = "Obtiene la lista completa de empleados registrados en el sistema.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lista de empleados obtenida exitosamente",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = Employee.class)))),
            @ApiResponse(responseCode = "503", description = "Base de datos no disponible",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<List<Employee>> getAll() {
        return ResponseEntity.ok(service.findAll());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consultar empleado por identificador", description = "Retorna los datos del empleado asociado al identificador único.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Empleado encontrado exitosamente",
                    content = @Content(schema = @Schema(implementation = Employee.class))),
            @ApiResponse(responseCode = "404", description = "Empleado no encontrado",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "Base de datos no disponible",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Employee> getById(
            @Parameter(description = "Identificador único del empleado (ej: E001)", required = true)
            @PathVariable String id) {
        return ResponseEntity.ok(service.findById(id));
    }
}
