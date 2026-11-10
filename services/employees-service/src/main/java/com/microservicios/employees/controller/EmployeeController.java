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
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/empleados")
@Tag(name = "Empleados", description = "Endpoints para la gestión, consulta, registro, actualización y retiro de empleados")
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
            description = "Registra un empleado validando formato, unicidad de campos y verificando que el departamento exista en departments-service. Publica evento 'empleado.creado'.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Empleado registrado exitosamente",
                    content = @Content(schema = @Schema(implementation = Employee.class))),
            @ApiResponse(responseCode = "400",
                    description = "Datos de entrada inválidos, departamento inexistente, o id/email/número de empleado ya registrados",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "Base de datos o servicio de departamentos no disponible",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Employee> register(
            @Validated(Employee.OnCreate.class) @RequestBody Employee employee) {
        return ResponseEntity.status(201).body(service.register(employee));
    }

    @GetMapping
    @Operation(summary = "Listar empleados y consultar la auditoría de retiros",
            description = "Obtiene la lista de empleados con filtros opcionales por departamento, estado, "
                    + "cargo y paginación. Si se envían 'desde' y 'hasta', la consulta se convierte en la "
                    + "auditoría de retiros: devuelve únicamente empleados con estado RETIRADO cuya "
                    + "fechaRetiro esté dentro del rango (ambos límites inclusivos, día completo). "
                    + "Esta es la ruta canónica de la auditoría; GET /empleados/auditoria delega aquí.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lista de empleados obtenida exitosamente",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = Employee.class)))),
            @ApiResponse(responseCode = "503", description = "Base de datos no disponible",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> getAll(
            @Parameter(description = "Filtrar por identificador de departamento", example = "D001")
            @RequestParam(required = false) String departamentoId,
            @Parameter(description = "Filtrar por estado del empleado (ACTIVO o RETIRADO)", example = "ACTIVO")
            @RequestParam(required = false) String estado,
            @Parameter(description = "Filtrar por cargo (búsqueda parcial)", example = "Desarrollador")
            @RequestParam(required = false) String cargo,
            @Parameter(description = "Auditoría: fecha inicial del rango de retiro (yyyy-MM-dd, inclusiva). "
                    + "Si se envía, también es obligatoria 'hasta' y el estado se fuerza a RETIRADO.",
                    example = "2026-01-01")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate desde,
            @Parameter(description = "Auditoría: fecha final del rango de retiro (yyyy-MM-dd, inclusiva). "
                    + "Se expande al final del día, de modo que un retiro de esa misma fecha a cualquier "
                    + "hora queda incluido.",
                    example = "2026-12-31")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate hasta,
            @Parameter(description = "Número de página (base 1)", example = "1")
            @RequestParam(required = false) Integer page,
            @Parameter(description = "Tamaño de página", example = "10")
            @RequestParam(required = false) Integer size,
            @Parameter(description = "Alias compatible para size", hidden = true)
            @RequestParam(required = false) Integer limit,
            @Parameter(description = "Campo permitido para ordenar", example = "id")
            @RequestParam(required = false) String sortBy,
            @Parameter(description = "Dirección de ordenamiento: ASC o DESC", example = "DESC")
            @RequestParam(required = false) String order) {
        Integer effectiveSize = (size != null) ? size : limit;
        com.microservicios.employees.model.EmployeeStatus statusEnum = parseStatus(estado);
        return ResponseEntity.ok(service.findAll(
                departamentoId, statusEnum, cargo, desde, hasta, page, effectiveSize, sortBy, order));
    }

    private com.microservicios.employees.model.EmployeeStatus parseStatus(String estado) {
        if (estado == null || estado.isBlank()) return null;
        String trimmed = estado.trim();
        for (com.microservicios.employees.model.EmployeeStatus s : com.microservicios.employees.model.EmployeeStatus.values()) {
            if (s.name().equalsIgnoreCase(trimmed) || s.jsonValue().equalsIgnoreCase(trimmed)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Estado de empleado no válido: " + estado);
    }

    @GetMapping("/auditoria")
    @Operation(summary = "Auditoría de empleados retirados (alias de GET /empleados?desde=&hasta=)",
            description = "Consulta únicamente empleados con estado RETIRADO cuya fechaRetiro esté dentro "
                    + "del rango especificado (desde y hasta inclusivos, día completo). "
                    + "La implementación está unificada en GET /empleados; esta ruta delega en ella y se "
                    + "conserva por compatibilidad con los clientes existentes.")
    @Deprecated(since = "Auditoría unificada en GET /empleados")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lista de empleados retirados obtenida exitosamente",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = Employee.class)))),
            @ApiResponse(responseCode = "400", description = "Fechas inválidas, parámetros faltantes o fecha desde posterior a hasta",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "Base de datos no disponible",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> getAuditoria(
            @Parameter(description = "Fecha inicial del rango (formato yyyy-MM-dd)", example = "2026-01-01")
            @RequestParam(name = "desde", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate desde,

            @Parameter(description = "Fecha final del rango (formato yyyy-MM-dd)", example = "2026-12-31")
            @RequestParam(name = "hasta", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate hasta,

            @Parameter(hidden = true)
            @RequestParam(name = "fechaInicio", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate fechaInicio,

            @Parameter(hidden = true)
            @RequestParam(name = "fechaFin", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate fechaFin,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String order) {

        LocalDate fechaDesde = (desde != null) ? desde : fechaInicio;
        LocalDate fechaHasta = (hasta != null) ? hasta : fechaFin;

        return ResponseEntity.ok(service.findAuditoria(fechaDesde, fechaHasta, page, limit, sortBy, order));
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

    @RequestMapping(value = "/{id}", method = {RequestMethod.PUT, RequestMethod.PATCH})
    @Operation(summary = "Actualizar datos de un empleado (PUT o PATCH)",
            description = "Actualiza la información del empleado y publica el evento de dominio 'empleado.actualizado'. Soporta tanto PUT como PATCH.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Empleado actualizado exitosamente",
                    content = @Content(schema = @Schema(implementation = Employee.class))),
            @ApiResponse(responseCode = "400", description = "Datos de entrada inválidos o conflicto de datos",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Empleado no encontrado",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "Base de datos no disponible",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Employee> update(
            @Parameter(description = "Identificador único del empleado", required = true)
            @PathVariable String id,
            @Valid @RequestBody Employee employee) {
        return ResponseEntity.ok(service.update(id, employee));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Retirar lógicamente un empleado",
            description = "Realiza el retiro lógico del empleado cambiando su estado a RETIRADO y publica 'empleado.retirado'.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Empleado retirado exitosamente",
                    content = @Content(schema = @Schema(implementation = Employee.class))),
            @ApiResponse(responseCode = "400", description = "El empleado ya está retirado",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Empleado no encontrado",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "Base de datos no disponible",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Employee> delete(
            @Parameter(description = "Identificador único del empleado", required = true)
            @PathVariable String id) {
        return ResponseEntity.ok(service.retire(id));
    }
}
