package com.microservicios.auth.exception;

import com.microservicios.auth.exception.AuthExceptions.CredencialesInvalidasException;
import com.microservicios.auth.exception.AuthExceptions.CuentaNoEncontradaException;
import com.microservicios.auth.exception.AuthExceptions.CuentaNoHabilitadaException;
import com.microservicios.auth.exception.AuthExceptions.IdentidadAusenteException;
import com.microservicios.auth.exception.AuthExceptions.PasswordDebilException;
import com.microservicios.auth.exception.ErrorResponse.InvalidField;
import com.microservicios.auth.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
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

    /** 401 — credenciales incorrectas, token inválido o cuenta no habilitada. */
    @ExceptionHandler({
            CredencialesInvalidasException.class,
            CuentaNoHabilitadaException.class,
            IdentidadAusenteException.class,
            JwtService.TokenInvalidoException.class
    })
    public ResponseEntity<ErrorResponse> noAutenticado(RuntimeException ex,
                                                       HttpServletRequest request) {
        return construir(HttpStatus.UNAUTHORIZED, ex.getMessage(), request, null);
    }

    /** 404 — no hay cuenta para el identificador indicado. */
    @ExceptionHandler(CuentaNoEncontradaException.class)
    public ResponseEntity<ErrorResponse> cuentaNoEncontrada(CuentaNoEncontradaException ex,
                                                            HttpServletRequest request) {
        return construir(HttpStatus.NOT_FOUND, ex.getMessage(), request, null);
    }

    /** 400 — la contraseña propuesta no cumple la política. */
    @ExceptionHandler(PasswordDebilException.class)
    public ResponseEntity<ErrorResponse> passwordDebil(PasswordDebilException ex,
                                                       HttpServletRequest request) {
        return construir(HttpStatus.BAD_REQUEST, ex.getMessage(), request, null);
    }

    /** 400 — faltan campos obligatorios o alguno tiene formato inválido. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> camposInvalidos(MethodArgumentNotValidException ex,
                                                         HttpServletRequest request) {
        List<InvalidField> errores = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new InvalidField(fe.getField(), fe.getDefaultMessage()))
                .sorted(Comparator.comparing(InvalidField::campo))
                .toList();
        return construir(HttpStatus.BAD_REQUEST,
                "La solicitud contiene campos inválidos", request, errores);
    }

    /** 400 — el cuerpo no es un JSON interpretable. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> cuerpoIlegible(HttpMessageNotReadableException ex,
                                                        HttpServletRequest request) {
        return construir(HttpStatus.BAD_REQUEST,
                "El cuerpo de la petición no es un JSON válido o contiene valores con formato incorrecto",
                request, null);
    }

    /** 404 — ruta inexistente o método no soportado. */
    @ExceptionHandler({
            NoHandlerFoundException.class,
            NoResourceFoundException.class,
            HttpRequestMethodNotSupportedException.class
    })
    public ResponseEntity<ErrorResponse> recursoNoEncontrado(HttpServletRequest request) {
        return construir(HttpStatus.NOT_FOUND, "Recurso no encontrado", request, null);
    }

    /** 503 — la base de datos no responde. */
    @ExceptionHandler({
            DataAccessResourceFailureException.class,
            CannotCreateTransactionException.class,
            QueryTimeoutException.class
    })
    public ResponseEntity<ErrorResponse> baseDeDatosNoDisponible(RuntimeException ex,
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
