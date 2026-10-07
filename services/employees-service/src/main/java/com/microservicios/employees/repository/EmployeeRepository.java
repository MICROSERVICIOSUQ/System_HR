package com.microservicios.employees.repository;

import com.microservicios.employees.model.Employee;
import com.microservicios.employees.model.EmployeeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface EmployeeRepository extends JpaRepository<Employee, String> {
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
}
