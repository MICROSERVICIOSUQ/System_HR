package com.microservicios.employees.repository;

import com.microservicios.employees.model.Employee;
import com.microservicios.employees.model.EmployeeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface EmployeeRepository extends JpaRepository<Employee, String>, JpaSpecificationExecutor<Employee> {
    boolean existsByEmailIgnoreCase(String email);

    boolean existsByNumeroEmpleado(String numeroEmpleado);

    /**
     * Auditoría: empleados retirados cuya fechaRetiro esté dentro del rango [desde, hasta].
     * Ambos límites son inclusivos. Spring Data JPA genera el query automáticamente.
     */
    List<Employee> findByEstadoAndFechaRetiroBetween(
            EmployeeStatus estado,
            LocalDate desde,
            LocalDate hasta
    );

    Page<Employee> findByEstadoAndFechaRetiroBetween(
            EmployeeStatus estado,
            LocalDate desde,
            LocalDate hasta,
            Pageable pageable
    );
}
