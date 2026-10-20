package com.sistema.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * Verificación de la firma y del contenido de los Access JWT.
 *
 * <p>Usa el mismo secreto simétrico HS256 que el auth-service emplea para firmar.
 * En un entorno real con clave asimétrica el gateway solo necesitaría la pública;
 * por simplicidad académica el enunciado admite HMAC compartido, inyectado por
 * variable de entorno a los dos contenedores.
 *
 * <p>Rechaza explícitamente los tokens de restablecimiento ({@code type =
 * RESET_PASSWORD}): están firmados con el mismo secreto, así que sin esta
 * comprobación un token de recuperación de correo serviría como credencial de
 * acceso a toda la API.
 */
@Component
public class JwtTokenValidator {

    private final SecretKey key;
    private final String issuer;

    public JwtTokenValidator(@Value("${jwt.secret}") String secret,
                             @Value("${jwt.issuer}") String issuer) {
        byte[] material = secret.getBytes(StandardCharsets.UTF_8);
        if (material.length < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET debe tener al menos 32 bytes para HS256; recibidos: "
                            + material.length);
        }
        this.key = Keys.hmacShaKeyFor(material);
        this.issuer = issuer;
    }

    /**
     * @return la identidad del token
     * @throws TokenRechazadoException si la firma no cuadra, el token expiró, le
     *                                 falta el rol o es un token de reset
     */
    public AuthenticatedUser validar(String token) {
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException ex) {
            throw new TokenRechazadoException("Token inválido o expirado");
        }

        String tipo = claims.get("type", String.class);
        if ("RESET_PASSWORD".equals(tipo)) {
            throw new TokenRechazadoException(
                    "Un token de restablecimiento no es válido como token de acceso");
        }

        String empleadoId = claims.getSubject();
        String rol = claims.get("role", String.class);

        if (empleadoId == null || empleadoId.isBlank() || rol == null || rol.isBlank()) {
            throw new TokenRechazadoException("El token no contiene sub y role");
        }
        if (!AuthenticatedUser.ROL_ADMIN.equals(rol) && !AuthenticatedUser.ROL_USER.equals(rol)) {
            throw new TokenRechazadoException("El rol '" + rol + "' no es reconocido");
        }

        return new AuthenticatedUser(empleadoId, rol);
    }

    /** El token presentado no autentica al portador. Se traduce a 401. */
    public static class TokenRechazadoException extends RuntimeException {
        public TokenRechazadoException(String mensaje) {
            super(mensaje);
        }
    }
}
