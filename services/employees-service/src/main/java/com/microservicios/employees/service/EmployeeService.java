package com.microservicios.employees.service;

import com.microservicios.employees.client.DepartmentClient;
import com.microservicios.employees.exception.DepartmentNotFoundException;
import com.microservicios.employees.exception.EmailAlreadyRegisteredException;
import com.microservicios.employees.exception.EmployeeAlreadyRetiredException;
import com.microservicios.employees.exception.EmployeeNotFoundException;
import com.microservicios.employees.exception.EmployeeNumberAlreadyRegisteredException;
import com.microservicios.employees.exception.IdAlreadyRegisteredException;
import com.microservicios.employees.messaging.EmployeeEventPublisher;
import com.microservicios.employees.model.Employee;
import com.microservicios.employees.model.EmployeeStatus;
import com.microservicios.employees.repository.EmployeeRepository;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.CannotCreateTransactionException;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import jakarta.persistence.criteria.Predicate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class EmployeeService {

    private final EmployeeRepository repository;
    private final DepartmentClient departmentClient;
    private final EmployeeEventPublisher eventPublisher;

    public EmployeeService(
            EmployeeRepository repository,
            DepartmentClient departmentClient,
            EmployeeEventPublisher eventPublisher
    ) {
        this.repository = repository;
        this.departmentClient = departmentClient;
        this.eventPublisher = eventPublisher;
    }

    /**
     * La presencia y el formato de los campos ya los garantiza la validación
     * declarativa del modelo ({@code @Valid} en el controller). Aquí solo se
     * comprueban las reglas que dependen del estado del repositorio.
     *
     * Orden: email → numeroEmpleado → id → departamento. Las tres comprobaciones
     * locales van antes de la llamada HTTP a departments-service para no gastar
     * un round-trip (ni sus reintentos) en un registro que fallará igualmente
     * por duplicado. El orden visible para el cliente en las tres reglas del
     * contrato (email, numeroEmpleado, departamento) no cambia.
     */
    @Retryable(
            retryFor = {
                    DataAccessResourceFailureException.class,
                    CannotCreateTransactionException.class,
                    QueryTimeoutException.class,
                    JpaSystemException.class
            },
            maxAttempts = 3,
            backoff = @Backoff(delay = 250, multiplier = 2.0)
    )
    /**
     * @Transactional porque el evento se guarda en el outbox dentro de esta
     * misma transacción: o se confirman el cambio y su evento, o ninguno de los
     * dos. Es lo que convierte la publicación en fiable sin transacciones
     * distribuidas, porque la tabla del outbox vive en la misma base de datos.
     */
    @Transactional
    public Employee register(Employee employee) {
        if (repository.existsByEmailIgnoreCase(employee.getEmail())) {
            throw new EmailAlreadyRegisteredException(employee.getEmail());
        }
        if (repository.existsByNumeroEmpleado(employee.getEmployeeNumber())) {
            throw new EmployeeNumberAlreadyRegisteredException(employee.getEmployeeNumber());
        }
        if (repository.existsById(employee.getId())) {
            throw new IdAlreadyRegisteredException(employee.getId());
        }
        if (!departmentClient.existsById(employee.getDepartamentoId())) {
            throw new DepartmentNotFoundException(employee.getDepartamentoId());
        }
        employee.setStatus(EmployeeStatus.ACTIVE);
        Employee saved = repository.save(employee);

        // Sin try/catch: si esto falla, la transacción se deshace y el alta no
        // se confirma. Antes se tragaba la excepción para no alterar la
        // respuesta HTTP, y el precio era un empleado creado sin su evento.
        eventPublisher.publishEmployeeCreated(saved);

        return saved;
    }

    @Retryable(
            retryFor = {
                    DataAccessResourceFailureException.class,
                    CannotCreateTransactionException.class,
                    QueryTimeoutException.class,
                    JpaSystemException.class
            },
            maxAttempts = 3,
            backoff = @Backoff(delay = 250, multiplier = 2.0)
    )
    /** Transaccional por el outbox: ver {@link #register}. */
    @Transactional
    public Employee update(String id, Employee updatedData) {
        Employee existing = findById(id);

        if (updatedData.getEmail() != null && !updatedData.getEmail().equalsIgnoreCase(existing.getEmail())) {
            if (repository.existsByEmailIgnoreCase(updatedData.getEmail())) {
                throw new EmailAlreadyRegisteredException(updatedData.getEmail());
            }
            existing.setEmail(updatedData.getEmail());
        }

        if (updatedData.getEmployeeNumber() != null && !updatedData.getEmployeeNumber().equals(existing.getEmployeeNumber())) {
            if (repository.existsByNumeroEmpleado(updatedData.getEmployeeNumber())) {
                throw new EmployeeNumberAlreadyRegisteredException(updatedData.getEmployeeNumber());
            }
            existing.setEmployeeNumber(updatedData.getEmployeeNumber());
        }

        if (updatedData.getDepartamentoId() != null && !updatedData.getDepartamentoId().equals(existing.getDepartamentoId())) {
            if (!departmentClient.existsById(updatedData.getDepartamentoId())) {
                throw new DepartmentNotFoundException(updatedData.getDepartamentoId());
            }
            existing.setDepartamentoId(updatedData.getDepartamentoId());
        }

        if (updatedData.getNombre() != null) {
            existing.setNombre(updatedData.getNombre());
        }
        if (updatedData.getApellido() != null) {
            existing.setApellido(updatedData.getApellido());
        }
        if (updatedData.getCargo() != null) {
            existing.setCargo(updatedData.getCargo());
        }
        if (updatedData.getArea() != null) {
            existing.setArea(updatedData.getArea());
        }
        if (updatedData.getFechaIngreso() != null) {
            existing.setFechaIngreso(updatedData.getFechaIngreso());
        }
        if (updatedData.getFechaRetiro() != null) {
            existing.setFechaRetiro(updatedData.getFechaRetiro());
        }
        if (updatedData.getStatus() != null) {
            existing.setStatus(updatedData.getStatus());
            if (updatedData.getStatus() == EmployeeStatus.RETIRED && existing.getFechaRetiro() == null) {
                // Igual que en retire(): el momento del retiro se registra con
                // hora, no solo con día (migración V3).
                existing.setFechaRetiro(LocalDateTime.now());
            }
        }

        Employee saved = repository.save(existing);

        eventPublisher.publishEmployeeUpdated(saved);

        return saved;
    }

    @Retryable(
            retryFor = {
                    DataAccessResourceFailureException.class,
                    CannotCreateTransactionException.class,
                    QueryTimeoutException.class,
                    JpaSystemException.class
            },
            maxAttempts = 3,
            backoff = @Backoff(delay = 250, multiplier = 2.0)
    )
    /** Transaccional por el outbox: ver {@link #register}. */
    @Transactional
    public Employee retire(String id) {
        Employee existing = findById(id);
        if (existing.getStatus() == EmployeeStatus.RETIRED) {
            throw new EmployeeAlreadyRetiredException(id);
        }
        existing.setStatus(EmployeeStatus.RETIRED);
        if (existing.getFechaRetiro() == null) {
            // Fecha Y hora del retiro. Antes era LocalDate.now(), que perdía la
            // hora exacta del hecho (migración V3).
            existing.setFechaRetiro(LocalDateTime.now());
        }
        Employee saved = repository.save(existing);

        eventPublisher.publishEmployeeRetired(saved);

        return saved;
    }

    @Retryable(
            retryFor = {
                    DataAccessResourceFailureException.class,
                    CannotCreateTransactionException.class,
                    QueryTimeoutException.class,
                    JpaSystemException.class
            },
            maxAttempts = 3,
            backoff = @Backoff(delay = 250, multiplier = 2.0)
    )
    /**
     * Auditoría de empleados retirados en un rango de fechas.
     *
     * <p>Quedó como delegación: la implementación real vive en
     * {@link #findAll}, que es la ruta canónica
     * ({@code GET /empleados?desde=&hasta=}). Se conserva porque
     * {@code GET /empleados/auditoria} es un contrato publicado —hay pruebas y
     * colecciones de Postman que lo usan— y romperlo no aportaría nada: lo que
     * se quería eliminar era la consulta duplicada, no la ruta.
     */
    public Object findAuditoria(LocalDate desde, LocalDate hasta, Integer page, Integer size,
                                String sortBy, String order) {
        // La validación se repite aquí a propósito. findAll trata desde/hasta
        // como filtros opcionales —sin ellos devuelve la lista completa, que es
        // su comportamiento correcto—, pero en esta ruta son obligatorios: una
        // "auditoría" sin rango que devolviera todos los empleados sería una
        // respuesta engañosa. Sin esta línea, GET /empleados/auditoria sin
        // parámetros pasaba de 400 a 200 y devolvía el listado entero.
        validarRangoDeAuditoria(desde, hasta);
        return findAll(null, null, null, desde, hasta, page, size, sortBy, order);
    }

    /**
     * Valida el rango de la consulta de auditoría.
     *
     * <p>Los dos límites son obligatorios a propósito: una auditoría sin fecha
     * final devolvería "todos los retiros desde X", que con el tiempo crece sin
     * techo. Pedir el rango completo obliga a acotar la consulta.
     */
    private static void validarRangoDeAuditoria(LocalDate desde, LocalDate hasta) {
        if (desde == null) {
            throw new IllegalArgumentException("El parámetro de fecha inicial 'desde' es obligatorio");
        }
        if (hasta == null) {
            throw new IllegalArgumentException("El parámetro de fecha final 'hasta' es obligatorio");
        }
        if (desde.isAfter(hasta)) {
            throw new IllegalArgumentException("La fecha 'desde' (" + desde
                    + ") no puede ser posterior a la fecha 'hasta' (" + hasta + ")");
        }
    }

    @Retryable(
            retryFor = {
                    DataAccessResourceFailureException.class,
                    CannotCreateTransactionException.class,
                    QueryTimeoutException.class,
                    JpaSystemException.class
            },
            maxAttempts = 3,
            backoff = @Backoff(delay = 250, multiplier = 2.0)
    )
    public Employee findById(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new EmployeeNotFoundException(id));
    }

    @Retryable(
            retryFor = {
                    DataAccessResourceFailureException.class,
                    CannotCreateTransactionException.class,
                    QueryTimeoutException.class,
                    JpaSystemException.class
            },
            maxAttempts = 3,
            backoff = @Backoff(delay = 250, multiplier = 2.0)
    )
    public List<Employee> findAll() {
        return repository.findAll();
    }

    @Retryable(
            retryFor = {
                    DataAccessResourceFailureException.class,
                    CannotCreateTransactionException.class,
                    QueryTimeoutException.class,
                    JpaSystemException.class
            },
            maxAttempts = 3,
            backoff = @Backoff(delay = 250, multiplier = 2.0)
    )
    public Object findAll(String departamentoId, EmployeeStatus estado, String cargo,
                          LocalDate desde, LocalDate hasta,
                          Integer page, Integer size, String sortBy, String order) {

        // Consulta de auditoría unificada en este mismo método.
        //
        // Antes la auditoría vivía en su propio endpoint con su propia consulta,
        // de modo que había dos caminos para listar empleados y las reglas de
        // paginación y ordenamiento se mantenían por duplicado. Ahora
        // GET /empleados?desde=&hasta= es la ruta canónica y
        // GET /empleados/auditoria delega aquí.
        final EmployeeStatus estadoEfectivo;
        final LocalDateTime inicioRango;
        final LocalDateTime finRango;

        if (desde != null || hasta != null) {
            validarRangoDeAuditoria(desde, hasta);
            // La auditoría es, por definición, sobre empleados dados de baja: un
            // empleado activo no tiene fecha de retiro que filtrar.
            estadoEfectivo = EmployeeStatus.RETIRED;
            // Día completo en los dos extremos. fecha_retiro es un TIMESTAMP
            // desde la migración V3: si el límite superior fuera
            // hasta.atStartOfDay(), un retiro de las 10 de la mañana del último
            // día del rango quedaría fuera del informe.
            inicioRango = desde.atStartOfDay();
            finRango = hasta.atTime(LocalTime.MAX);
        } else {
            estadoEfectivo = estado;
            inicioRango = null;
            finRango = null;
        }

        Specification<Employee> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (departamentoId != null && !departamentoId.isBlank()) {
                predicates.add(cb.equal(root.get("departamentoId"), departamentoId.trim()));
            }
            if (estadoEfectivo != null) {
                predicates.add(cb.equal(root.get("estado"), estadoEfectivo));
            }
            if (cargo != null && !cargo.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("cargo")), "%" + cargo.trim().toLowerCase() + "%"));
            }
            if (inicioRango != null) {
                predicates.add(cb.between(root.get("fechaRetiro"), inicioRango, finRango));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        if (page != null || size != null || sortBy != null || order != null) {
            Pagination pagination = pagination(page, size, sortBy, order);
            var result = repository.findAll(spec, PageRequest.of(
                    pagination.page() - 1,
                    pagination.limit(),
                    pagination.sort()
            ));
            return paginated(result.getContent(), result.getTotalElements(), pagination);
        }

        return repository.findAll(spec, Sort.by("id").ascending());
    }

    private Pagination pagination(Integer page, Integer size, String sortBy, String order) {
        int effectivePage = page == null ? 1 : page;
        int effectiveLimit = size == null ? 10 : size;
        if (effectivePage < 1) throw new IllegalArgumentException("page debe ser un entero positivo");
        if (effectiveLimit < 1) throw new IllegalArgumentException("limit debe ser un entero positivo");
        if (effectiveLimit > 100) throw new IllegalArgumentException("limit no puede ser mayor que 100");

        String field = sortBy == null ? "id" : switch (sortBy) {
            case "id", "employeeNumber", "nombre", "apellido", "email", "cargo",
                    "area", "departamentoId", "fechaIngreso", "fechaRetiro", "status" -> sortBy;
            default -> throw new IllegalArgumentException("sortBy no permitido");
        };
        Sort.Direction direction;
        if (order == null || order.equalsIgnoreCase("ASC")) {
            direction = Sort.Direction.ASC;
        } else if (order.equalsIgnoreCase("DESC")) {
            direction = Sort.Direction.DESC;
        } else {
            throw new IllegalArgumentException("order debe ser ASC o DESC");
        }
        return new Pagination(effectivePage, effectiveLimit, Sort.by(direction, field));
    }

    private Map<String, Object> paginated(List<Employee> data, long total, Pagination pagination) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("total", total);
        meta.put("page", pagination.page());
        meta.put("limit", pagination.limit());
        meta.put("totalPages", total == 0 ? 0 : (int) Math.ceil((double) total / pagination.limit()));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("data", data);
        response.put("meta", meta);
        return response;
    }

    private record Pagination(int page, int limit, Sort sort) {}
}
