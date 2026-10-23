package com.microservicios.auth.security;

import com.microservicios.auth.model.Role;
import com.microservicios.auth.model.UserAccount;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * Emisión y verificación de los dos tipos de token del sistema.
 *
 * <ul>
 *   <li><strong>Access JWT</strong> — se devuelve en {@code POST /auth/login}.
 *       Claims: {@code sub} (empleadoId), {@code role}, {@code iat}, {@code exp}.</li>
 *   <li><strong>Reset JWT</strong> — se genera al crear un empleado o al pedir
 *       recuperación. Claims: {@code sub}, {@code type: "RESET_PASSWORD"},
 *       {@code iat}, {@code exp}. Opción A del enunciado (stateless): no se guarda
 *       en base de datos, su autenticidad y vigencia se verifican con la firma.</li>
 * </ul>
 *
 * <p>Firma simétrica HMAC-SHA256 con el secreto compartido {@code JWT_SECRET}, el
 * mismo que usa el api-gateway para validar. La contraseña nunca entra en el token.
 */
@Component
public class JwtService {

    /** Distingue un token de restablecimiento de un token de acceso. */
    public static final String CLAIM_TYPE = "type";
    public static final String TYPE_RESET_PASSWORD = "RESET_PASSWORD";
    public static final String CLAIM_ROLE = "role";

    /**
     * Parámetro {@code typ} de la cabecera JOSE.
     *
     * <p>Declara el tipo del objeto, no su propósito: el RFC 7519 y la
     * documentación de seguridad del curso coinciden en que, cuando está
     * presente, debe valer exactamente {@code JWT}. Sirve para que un receptor
     * que maneje varios objetos JOSE sepa que esto es un JWT antes de intentar
     * interpretarlo.
     *
     * <p>Que el propósito (acceso o restablecimiento) viaje en el claim
     * {@code type} y no aquí es deliberado: son dos preguntas distintas —«qué
     * es esto» y «para qué sirve»— y mezclarlas en {@code typ} rompería la
     * interoperabilidad con cualquier librería que espere el valor estándar.
     */
    public static final String HEADER_TYP = "typ";
    public static final String TYP_JWT = "JWT";

    private final SecretKey key;
    private final long accessTtlSeconds;
    private final long resetTtlSeconds;
    private final String issuer;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-ttl-seconds}") long accessTtlSeconds,
            @Value("${jwt.reset-token-ttl-seconds}") long resetTtlSeconds,
            @Value("${jwt.issuer}") String issuer) {

        byte[] material = secret.getBytes(StandardCharsets.UTF_8);
        if (material.length < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET debe tener al menos 32 bytes para firmar con HS256; recibidos: "
                            + material.length);
        }
        this.key = Keys.hmacShaKeyFor(material);
        this.accessTtlSeconds = accessTtlSeconds;
        this.resetTtlSeconds = resetTtlSeconds;
        this.issuer = issuer;
    }

    /** Access token emitido tras un login correcto. */
    public String generarAccessToken(UserAccount cuenta) {
        Instant ahora = Instant.now();
        return Jwts.builder()
                .header().add(HEADER_TYP, TYP_JWT).and()
                .issuer(issuer)
                .subject(cuenta.getEmpleadoId())
                .claim(CLAIM_ROLE, cuenta.getRole().name())
                .issuedAt(Date.from(ahora))
                .expiration(Date.from(ahora.plusSeconds(accessTtlSeconds)))
                // HS256 explícito. Sin fijarlo, jjwt elige el algoritmo según la
                // longitud de la clave (una de 48 bytes daría HS384), y el header
                // del token dejaría de coincidir con lo documentado.
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** Token de activación o recuperación de contraseña, de vida corta. */
    public String generarResetToken(UserAccount cuenta) {
        Instant ahora = Instant.now();
        return Jwts.builder()
                .header().add(HEADER_TYP, TYP_JWT).and()
                .issuer(issuer)
                .subject(cuenta.getEmpleadoId())
                .claim(CLAIM_TYPE, TYPE_RESET_PASSWORD)
                .issuedAt(Date.from(ahora))
                .expiration(Date.from(ahora.plusSeconds(resetTtlSeconds)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Verifica un token de restablecimiento y devuelve el {@code sub}.
     *
     * @throws TokenInvalidoException si la firma no cuadra, el token expiró o no es
     *                                de tipo {@code RESET_PASSWORD} (un access token
     *                                no sirve para cambiar una contraseña).
     */
    public String validarResetTokenYObtenerSujeto(String token) {
        Claims claims = parsear(token);
        if (!TYPE_RESET_PASSWORD.equals(claims.get(CLAIM_TYPE, String.class))) {
            throw new TokenInvalidoException(
                    "El token no es de restablecimiento de contraseña");
        }
        return claims.getSubject();
    }

    public long getAccessTtlSeconds() {
        return accessTtlSeconds;
    }

    public long getResetTtlSeconds() {
        return resetTtlSeconds;
    }

    private Claims parsear(String token) {
        Jws<Claims> jws;
        try {
            jws = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new TokenInvalidoException("El token es inválido o ha expirado");
        }

        // La cabecera entra en el cálculo de la firma, así que no se puede
        // manipular sin invalidar el token: exigir el typ aquí es una
        // comprobación barata y fiable de que esto es un JWT nuestro.
        if (!TYP_JWT.equals(jws.getHeader().getType())) {
            throw new TokenInvalidoException(
                    "La cabecera del token no declara typ=JWT");
        }
        return jws.getPayload();
    }

    /** El token presentado no se puede usar: firma, vigencia o tipo incorrectos. */
    public static class TokenInvalidoException extends RuntimeException {
        public TokenInvalidoException(String mensaje) {
            super(mensaje);
        }
    }

    public static Role rolPorDefecto() {
        return Role.USER;
    }
}
