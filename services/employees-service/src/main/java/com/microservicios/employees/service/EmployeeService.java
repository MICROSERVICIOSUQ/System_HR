package com.microservicios.employees.service;

import com.microservicios.employees.client.DepartmentClient;
import com.microservicios.employees.exception.DepartmentNotFoundException;
import com.microservicios.employees.exception.EmailAlreadyRegisteredException;
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
import org.springframework.transaction.CannotCreateTransactionException;

import java.time.LocalDate;
import java.util.List;

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

        try {
            eventPublisher.publishEmployeeCreated(saved);
        } catch (Exception ex) {
            // El error queda registrado en los logs por el publisher.
            // Para mantener la consistencia de la respuesta HTTP, no se altera la respuesta de persistencia.
        }

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
        }

        Employee saved = repository.save(existing);

        try {
            eventPublisher.publishEmployeeUpdated(saved);
        } catch (Exception ex) {
            // El error queda registrado en los logs por el publisher
        }

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
    public Employee retire(String id) {
        Employee existing = findById(id);
        existing.setStatus(EmployeeStatus.RETIRED);
        if (existing.getFechaRetiro() == null) {
            existing.setFechaRetiro(LocalDate.now());
        }
        Employee saved = repository.save(existing);

        try {
            eventPublisher.publishEmployeeRetired(saved);
        } catch (Exception ex) {
            // El error queda registrado en los logs por el publisher
        }

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
    public List<Employee> findAuditoria(LocalDate desde, LocalDate hasta) {
        if (desde == null) {
            throw new IllegalArgumentException("El parámetro de fecha inicial 'desde' es obligatorio");
        }
        if (hasta == null) {
            throw new IllegalArgumentException("El parámetro de fecha final 'hasta' es obligatorio");
        }
        if (desde.isAfter(hasta)) {
            throw new IllegalArgumentException("La fecha 'desde' (" + desde + ") no puede ser posterior a la fecha 'hasta' (" + hasta + ")");
        }
        return repository.findByEstadoAndFechaRetiroBetween(EmployeeStatus.RETIRED, desde, hasta);
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
}
