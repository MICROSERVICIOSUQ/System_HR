package com.microservicios.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Contratos de entrada y salida de los cuatro endpoints de autenticación.
 * Se agrupan aquí porque solo tienen sentido como pareja petición/respuesta.
 */
public final class AuthDtos {

    private AuthDtos() {}

    @Schema(description = "Credenciales de acceso")
    public record LoginRequest(
            @NotBlank(message = "El usuario es obligatorio")
            @Schema(description = "Email o identificador de empleado", example = "juan.perez@empresa.com")
            String usuario,

            @NotBlank(message = "La contraseña es obligatoria")
            @Schema(description = "Contraseña en claro; solo viaja en esta petición", example = "Secreta123")
            String password
    ) {}

    @Schema(description = "Token de acceso emitido tras un login correcto")
    public record LoginResponse(
            @Schema(description = "Access JWT firmado con HS256") String token,
            @Schema(description = "Esquema de autorización a usar en la cabecera", example = "Bearer") String tipo,
            @Schema(description = "Segundos de vigencia del token", example = "3600") long expiraEnSegundos,
            @Schema(description = "Claim sub del token", example = "E001") String empleadoId,
            @Schema(description = "Claim role del token", example = "USER") String rol
    ) {
        public static LoginResponse de(String token, long ttl, String empleadoId, String rol) {
            return new LoginResponse(token, "Bearer", ttl, empleadoId, rol);
        }
    }

    @Schema(description = "Solicitud de recuperación de contraseña")
    public record RecoverPasswordRequest(
            @NotBlank(message = "El email es obligatorio")
            @Email(message = "El email no tiene un formato válido")
            @Schema(example = "juan.perez@empresa.com")
            String email
    ) {}

    @Schema(description = "Establecimiento de contraseña con token de activación o recuperación")
    public record ResetPasswordRequest(
            @NotBlank(message = "El token es obligatorio")
            @Schema(description = "Token recibido por correo (claim type=RESET_PASSWORD)")
            String token,

            @NotBlank(message = "La nueva contraseña es obligatoria")
            @Schema(description = "Mínimo 8 caracteres, con al menos una letra y un dígito", example = "Secreta123")
            String nuevaPassword
    ) {}

    @Schema(description = "Cambio de contraseña de un usuario autenticado")
    public record ChangePasswordRequest(
            @NotBlank(message = "La contraseña actual es obligatoria")
            @Schema(example = "Secreta123")
            String passwordActual,

            @NotBlank(message = "La nueva contraseña es obligatoria")
            @Schema(description = "Mínimo 8 caracteres, con al menos una letra y un dígito", example = "Nueva456")
            String nuevaPassword
    ) {}

    @Schema(description = "Confirmación de una operación que no devuelve recurso")
    public record MensajeResponse(
            @Schema(example = "Contraseña establecida correctamente") String mensaje
    ) {
        public static MensajeResponse de(String mensaje) {
            return new MensajeResponse(mensaje);
        }
    }
}
