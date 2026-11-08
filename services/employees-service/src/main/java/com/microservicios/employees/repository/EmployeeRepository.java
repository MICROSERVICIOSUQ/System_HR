package com.microservicios.employees.repository;

import com.microservicios.employees.model.Employee;
import com.microservicios.employees.model.EmployeeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface EmployeeRepository extends JpaRepository<Employee, String>, JpaSpecificationExecutor<Employee> {
    boolean existsByEmailIgnoreCase(String email);

    boolean existsByNumeroEmpleado(String numeroEmpleado);

    /**
     * Auditoría: empleados retirados cuya fechaRetiro esté dentro del rango.
     *
     * <p>Los límites son {@link java.time.LocalDateTime} desde que
     * {@code fecha_retiro} es un TIMESTAMP (migración V3). Quien llama debe
     * expandir las fechas del día completo: {@code desde.atStartOfDay()} y
     * {@code hasta.atTime(LocalTime.MAX)}. Si se pasara
     * {@code hasta.atStartOfDay()}, un retiro de las 10 de la mañana del último
     * día del rango quedaría fuera del informe — el fallo clásico al comparar un
     * instante contra un día.
     */
    List<Employee> findByEstadoAndFechaRetiroBetween(
            EmployeeStatus estado,
            LocalDateTime desde,
            LocalDateTime hasta
    );

    Page<Employee> findByEstadoAndFechaRetiroBetween(
            EmployeeStatus estado,
            LocalDateTime desde,
            LocalDateTime hasta,
            Pageable pageable
    );
}
