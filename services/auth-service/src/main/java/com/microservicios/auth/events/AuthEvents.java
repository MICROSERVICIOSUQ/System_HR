package com.microservicios.auth.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Nombres y cargas útiles de los eventos del auth-service, según el Catálogo de
 * Eventos (secciones 3.4 a 3.7).
 *
 * <p>El campo {@code id} del payload es el {@code empleadoId}, siguiendo la misma
 * convención que {@code EmployeeEventData.id} del Reto 4: es la clave de negocio
 * sobre la que el notificaciones-service indexa cada mensaje.
 *
 * <p>Ningún payload transporta contraseñas: los eventos de alta y recuperación
 * llevan un token de restablecimiento de vida corta, nunca una credencial.
 */
public final class AuthEvents {

    private AuthEvents() {}

    // ── Eventos que publica este servicio ────────────────────────────────────
    public static final String USUARIO_CREADO = "usuario.creado";
    public static final String USUARIO_RECUPERACION = "usuario.recuperacion";
    public static final String CUENTA_ACTIVADA = "cuenta.activada";
    public static final String CUENTA_DESACTIVADA = "cuenta.desactivada";

    // ── Eventos que consume este servicio ────────────────────────────────────
    public static final String EMPLEADO_CREADO = "empleado.creado";
    public static final String EMPLEADO_RETIRADO = "empleado.retirado";
    public static final String VACACIONES_INICIADAS = "vacaciones.iniciadas";
    public static final String VACACIONES_FINALIZADAS = "vacaciones.finalizadas";

    // ── Motivos de activación / desactivación ────────────────────────────────
    public static final String MOTIVO_RETIRO = "RETIRO";
    public static final String MOTIVO_VACACIONES = "VACACIONES";
    public static final String MOTIVO_FIN_VACACIONES = "FIN_VACACIONES";
    public static final String MOTIVO_ACTIVACION_INICIAL = "ACTIVACION_INICIAL";

    /**
     * Payload de {@code usuario.creado} (3.4) y {@code usuario.recuperacion} (3.5):
     * email del destinatario y token para establecer la contraseña.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record UsuarioTokenData(
            @JsonProperty("id") String id,
            @JsonProperty("email") String email,
            @JsonProperty("token") String token
    ) {}

    /**
     * Payload de {@code cuenta.activada} (3.6): email y motivo de la reactivación.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CuentaActivadaData(
            @JsonProperty("id") String id,
            @JsonProperty("email") String email,
            @JsonProperty("motivo") String motivo
    ) {}

    /**
     * Payload de {@code cuenta.desactivada} (3.7): email, motivo y si la baja es
     * permanente. {@code permanente=false} para vacaciones, {@code true} para retiro.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CuentaDesactivadaData(
            @JsonProperty("id") String id,
            @JsonProperty("email") String email,
            @JsonProperty("motivo") String motivo,
            @JsonProperty("permanente") boolean permanente
    ) {}
}
