package com.microservicios.employees.service;

import com.microservicios.employees.client.DepartmentClient;
import com.microservicios.employees.exception.DepartmentNotFoundException;
import com.microservicios.employees.exception.EmailAlreadyRegisteredException;
import com.microservicios.employees.exception.EmployeeNotFoundException;
import com.microservicios.employees.exception.IdAlreadyRegisteredException;
import com.microservicios.employees.exception.EmployeeNumberAlreadyRegisteredException;
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

import java.util.List;

@Service
public class EmployeeService {

    private final EmployeeRepository repository;
    private final DepartmentClient departmentClient;

    public EmployeeService(EmployeeRepository repository, DepartmentClient departmentClient) {
        this.repository = repository;
        this.departmentClient = departmentClient;
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
        return repository.save(employee);
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
