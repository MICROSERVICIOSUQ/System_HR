package com.microservicios.auth.controller;

import com.microservicios.auth.dto.AuthDtos.ChangePasswordRequest;
import com.microservicios.auth.dto.AuthDtos.LoginRequest;
import com.microservicios.auth.dto.AuthDtos.LoginResponse;
import com.microservicios.auth.dto.AuthDtos.MensajeResponse;
import com.microservicios.auth.dto.AuthDtos.RecoverPasswordRequest;
import com.microservicios.auth.dto.AuthDtos.ResetPasswordRequest;
import com.microservicios.auth.dto.AuthDtos.TokenDeRestablecimientoRequest;
import com.microservicios.auth.dto.AuthDtos.TokenDeRestablecimientoResponse;
import com.microservicios.auth.exception.AuthExceptions.IdentidadAusenteException;
import com.microservicios.auth.exception.ErrorResponse;
import com.microservicios.auth.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/auth")
@Tag(name = "Autenticación", description = "Emisión de tokens y gestión de credenciales")
public class AuthController {

    /**
     * Cabecera con la que el api-gateway propaga el claim {@code sub} del JWT ya
     * validado. Los servicios de negocio no vuelven a verificar la firma: confían
     * en el gateway porque solo él está publicado al host (ver ADR-008).
     */
    public static final String HEADER_USUARIO = "X-Auth-User";

    private final AuthService service;

    public AuthController(AuthService service) {
        this.service = service;
    }

    @PostMapping("/login")
    @Operation(summary = "Autenticar y obtener un Access JWT",
            description = "Verifica las credenciales y devuelve un JWT firmado con HS256 cuyo "
                    + "payload incluye sub (empleadoId), role, iat y exp. El campo 'usuario' "
                    + "acepta el email o el identificador de empleado. Endpoint público.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Credenciales válidas",
                    content = @Content(schema = @Schema(implementation = LoginResponse.class))),
            @ApiResponse(responseCode = "400", description = "Faltan campos obligatorios",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401",
                    description = "Credenciales incorrectas, o cuenta pendiente de activación, "
                            + "suspendida por vacaciones o retirada",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest peticion) {
        return ResponseEntity.ok(service.login(peticion.usuario(), peticion.password()));
    }

    @PostMapping("/recover-password")
    @Operation(summary = "Solicitar un token de recuperación",
            description = "Genera un token de vida corta y publica usuario.recuperacion para que "
                    + "notificaciones-service simule el envío del correo. Endpoint público.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Proceso iniciado; revise los logs de notificaciones",
                    content = @Content(schema = @Schema(implementation = MensajeResponse.class))),
            @ApiResponse(responseCode = "400", description = "Email ausente o con formato inválido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "La cuenta tiene baja permanente",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No existe cuenta con ese email",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<MensajeResponse> recoverPassword(
            @Valid @RequestBody RecoverPasswordRequest peticion) {
        service.recuperarPassword(peticion.email());
        return ResponseEntity.accepted().body(MensajeResponse.de(
                "Se envió un token de recuperación al correo registrado"));
    }

    @PostMapping("/reset-password")
    @Operation(summary = "Establecer la contraseña con un token",
            description = "Acepta el token de activación (alta del empleado) o de recuperación y "
                    + "fija la nueva contraseña. En la activación inicial deja la cuenta ACTIVA y "
                    + "publica cuenta.activada. Endpoint público: la autorización la da el token del cuerpo.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Contraseña establecida",
                    content = @Content(schema = @Schema(implementation = MensajeResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "Campos ausentes o contraseña que no cumple la política",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401",
                    description = "Token inválido, expirado, de tipo incorrecto, o cuenta con baja permanente",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "La cuenta del token no existe",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<MensajeResponse> resetPassword(
            @Valid @RequestBody ResetPasswordRequest peticion) {
        service.resetPassword(peticion.token(), peticion.nuevaPassword());
        return ResponseEntity.ok(MensajeResponse.de("Contraseña establecida correctamente"));
    }

    @PostMapping("/change-password")
    @Operation(summary = "Cambiar la propia contraseña",
            description = "Requiere un Access JWT válido. El usuario solo puede cambiar su propia "
                    + "contraseña: la identidad se toma del claim sub del token, nunca del cuerpo. "
                    + "Es una de las dos excepciones de escritura permitidas al rol USER.",
            security = @SecurityRequirement(name = "BearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Contraseña cambiada",
                    content = @Content(schema = @Schema(implementation = MensajeResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "Campos ausentes o contraseña que no cumple la política",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401",
                    description = "Sin token válido, o la contraseña actual no coincide",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "La cuenta no existe",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<MensajeResponse> changePassword(
            @RequestHeader(value = HEADER_USUARIO, required = false) String empleadoId,
            @Valid @RequestBody ChangePasswordRequest peticion) {

        if (empleadoId == null || empleadoId.isBlank()) {
            throw new IdentidadAusenteException();
        }
        service.cambiarPassword(empleadoId, peticion.passwordActual(), peticion.nuevaPassword());
        return ResponseEntity.ok(MensajeResponse.de("Contraseña cambiada correctamente"));
    }

    @PostMapping("/internal/tokens/restablecimiento")
    @Operation(summary = "Emitir un token de restablecimiento (solo ADMIN)",
            description = "Devuelve un token con type=RESET_PASSWORD para la cuenta indicada, "
                    + "utilizable en POST /auth/reset-password. "
                    + "Existe porque el token de activación viaja en el evento usuario.creado y "
                    + "notifications-service ya no lo conserva en su historial: guardarlo en texto "
                    + "plano era el hallazgo de seguridad que la auditoría pedía cerrar. Las pruebas "
                    + "automatizadas y la activación asistida necesitan un canal legítimo, y este lo "
                    + "es: el token lo entrega quien lo firma. "
                    + "Es una operación privilegiada —equivale a poder tomar el control de una "
                    + "cuenta— así que el gateway la reserva al rol ADMIN y cada emisión queda "
                    + "registrada con el administrador que la solicitó.",
            security = @SecurityRequirement(name = "BearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Token emitido",
                    content = @Content(schema = @Schema(implementation = TokenDeRestablecimientoResponse.class))),
            @ApiResponse(responseCode = "400", description = "Falta el empleadoId",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401",
                    description = "Sin token válido, o la cuenta tiene baja permanente",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "El rol USER no puede emitir tokens de otras cuentas",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "La cuenta no existe",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<TokenDeRestablecimientoResponse> emitirTokenDeRestablecimiento(
            @RequestHeader(value = HEADER_USUARIO, required = false) String solicitante,
            @Valid @RequestBody TokenDeRestablecimientoRequest peticion) {

        // La identidad del solicitante no autoriza nada aquí —de eso se encarga
        // el gateway— pero sí se exige para que la emisión quede atribuida. Un
        // token privilegiado emitido por "alguien" no sirve para auditar.
        if (solicitante == null || solicitante.isBlank()) {
            throw new IdentidadAusenteException();
        }

        var emitido = service.emitirTokenDeRestablecimiento(peticion.empleadoId(), solicitante);
        return ResponseEntity.ok(TokenDeRestablecimientoResponse.de(
                emitido.empleadoId(), emitido.token(), emitido.expiraEnSegundos()));
    }

    @io.swagger.v3.oas.annotations.Hidden
    @org.springframework.web.bind.annotation.GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "ok", "service", "auth"));
    }
}
