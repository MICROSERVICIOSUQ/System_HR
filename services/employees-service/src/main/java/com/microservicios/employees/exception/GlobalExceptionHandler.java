package com.microservicios.employees.exception;

import com.microservicios.employees.exception.ErrorResponse.InvalidField;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 404 — el empleado solicitado no está registrado. */
        @ExceptionHandler(EmployeeNotFoundException.class)
        public ResponseEntity<ErrorResponse> employeeNotFound(EmployeeNotFoundException ex,
                                                              HttpServletRequest request) {
        return construir(HttpStatus.NOT_FOUND, ex.getMessage(), request, null);
    }

    /**
     * 400 — los datos enviados chocan con información que ya existe en el sistema.
     * Se evaluó 409 Conflict por semántica HTTP; se usa 400 para alinear con la
     * especificación del Reto 2.
     */
    @ExceptionHandler({
            IdAlreadyRegisteredException.class,
            EmailAlreadyRegisteredException.class,
            EmployeeNumberAlreadyRegisteredException.class
    })
    public ResponseEntity<ErrorResponse> datosDuplicados(RuntimeException ex,
                                                         HttpServletRequest request) {
        return construir(HttpStatus.BAD_REQUEST, ex.getMessage(), request, null);
    }

    /**
     * 400 — la restricción UNIQUE de la base de datos rechazó un duplicado que superó
     * la consulta previa (condición de carrera entre dos registros simultáneos).
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> duplicadoEnBaseDeDatos(DataIntegrityViolationException ex,
                                                                HttpServletRequest request) {
        return construir(HttpStatus.BAD_REQUEST,
                "El email o número de empleado ya está registrado", request, null);
    }

    /** 400 - el empleado debe estar asociado a un departamento existente. */
    @ExceptionHandler(DepartmentNotFoundException.class)
    public ResponseEntity<ErrorResponse> departmentNotFound(DepartmentNotFoundException ex,
                                                            HttpServletRequest request) {
        return construir(HttpStatus.BAD_REQUEST, ex.getMessage(), request, null);
    }

    /** 400 — faltan campos obligatorios o alguno tiene formato inválido. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
        public ResponseEntity<ErrorResponse> invalidFields(MethodArgumentNotValidException ex,
                                                         HttpServletRequest request) {
        List<InvalidField> errores = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new InvalidField(fe.getField(), fe.getDefaultMessage()))
                .sorted(Comparator.comparing(InvalidField::campo))
                .toList();
        return construir(HttpStatus.BAD_REQUEST,
                "La solicitud contiene campos inválidos", request, errores);
    }

    /** 400 — el JSON no se puede interpretar (sintaxis rota, enum o fecha inválidos). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
        public ResponseEntity<ErrorResponse> unreadableBody(HttpMessageNotReadableException ex,
                                                        HttpServletRequest request) {
        return construir(HttpStatus.BAD_REQUEST,
                "El cuerpo de la petición no es un JSON válido o contiene valores con formato incorrecto",
                request, null);
    }

    /** 404 — ruta inexistente o método no soportado en una ruta existente. */
    @ExceptionHandler({
            NoHandlerFoundException.class,
            NoResourceFoundException.class,
            HttpRequestMethodNotSupportedException.class
        })
        public ResponseEntity<ErrorResponse> resourceNotFound(HttpServletRequest request) {
        return construir(HttpStatus.NOT_FOUND, "Recurso no encontrado", request, null);
    }

    /** 503 - la dependencia de departamentos no esta disponible tras los reintentos. */
    @ExceptionHandler({
            ResourceAccessException.class,
            HttpServerErrorException.class
    })
    public ResponseEntity<ErrorResponse> departmentServiceUnavailable(RuntimeException ex,
                                                                      HttpServletRequest request) {
        return construir(HttpStatus.SERVICE_UNAVAILABLE,
                "El servicio de departamentos no está disponible temporalmente", request, null);
    }

    /** 503 - la dependencia de persistencia no esta disponible tras los reintentos. */
    @ExceptionHandler({
            DataAccessResourceFailureException.class,
            CannotCreateTransactionException.class,
            QueryTimeoutException.class,
            JpaSystemException.class
    })
    public ResponseEntity<ErrorResponse> databaseUnavailable(RuntimeException ex,
                                                              HttpServletRequest request) {
        return construir(HttpStatus.SERVICE_UNAVAILABLE,
                "La base de datos no está disponible temporalmente", request, null);
    }

    private ResponseEntity<ErrorResponse> construir(HttpStatus status, String mensaje,
                                                    HttpServletRequest request,
                                                    List<InvalidField> errores) {
        ErrorResponse cuerpo = new ErrorResponse(
                OffsetDateTime.now(),
                status.value(),
                status.getReasonPhrase(),
                mensaje,
                request.getRequestURI(),
                errores
        );
        return ResponseEntity.status(status).body(cuerpo);
    }
}
