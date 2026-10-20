package com.microservicios.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Cuenta de acceso de un empleado.
 *
 * <p>La clave primaria es el {@code empleadoId} porque es el valor que viaja en el
 * claim {@code sub} del JWT y contra el que se compara la propiedad del recurso
 * ({@code recurso.empleadoId == token.sub}). El {@code email} es la credencial con
 * la que el usuario inicia sesión y el destinatario de las notificaciones.
 *
 * <p>La contraseña se almacena siempre como hash BCrypt y es {@code null} mientras
 * la cuenta esté en {@link AccountStatus#PENDIENTE_ACTIVACION}.
 */
@Entity
@Table(name = "user_accounts")
public class UserAccount {

    @Id
    @Column(name = "empleado_id", nullable = false, updatable = false)
    private String empleadoId;

    @Column(nullable = false, unique = true)
    private String email;

    /** Hash BCrypt. Nunca la contraseña en claro. Nulo hasta la activación. */
    @Column(name = "password_hash")
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AccountStatus estado;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected UserAccount() {}

    private UserAccount(String empleadoId, String email, String passwordHash,
                        Role role, AccountStatus estado) {
        OffsetDateTime ahora = OffsetDateTime.now();
        this.empleadoId = empleadoId;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.estado = estado;
        this.createdAt = ahora;
        this.updatedAt = ahora;
    }

    /** Cuenta creada al consumir {@code empleado.creado}: rol USER y sin contraseña. */
    public static UserAccount pendienteDeActivacion(String empleadoId, String email) {
        return new UserAccount(empleadoId, email, null, Role.USER, AccountStatus.PENDIENTE_ACTIVACION);
    }

    /** Administrador semilla creado al arrancar el servicio desde variables de entorno. */
    public static UserAccount administradorSemilla(String empleadoId, String email, String passwordHash) {
        return new UserAccount(empleadoId, email, passwordHash, Role.ADMIN, AccountStatus.ACTIVA);
    }

    /** Establece la contraseña y deja la cuenta operativa. */
    public void establecerPassword(String nuevoHash) {
        this.passwordHash = nuevoHash;
        this.estado = AccountStatus.ACTIVA;
        this.updatedAt = OffsetDateTime.now();
    }

    public void suspenderTemporalmente() {
        this.estado = AccountStatus.SUSPENDIDA_TEMPORAL;
        this.updatedAt = OffsetDateTime.now();
    }

    public void desactivarPermanentemente() {
        this.estado = AccountStatus.DESACTIVADA_PERMANENTE;
        this.updatedAt = OffsetDateTime.now();
    }

    public void reactivar() {
        this.estado = AccountStatus.ACTIVA;
        this.updatedAt = OffsetDateTime.now();
    }

    /** Hay credencial utilizable y el estado permite autenticarse. */
    public boolean puedeAutenticarse() {
        return passwordHash != null && estado.permiteLogin();
    }

    public String getEmpleadoId() { return empleadoId; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    public AccountStatus getEstado() { return estado; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setEmail(String email) {
        this.email = email;
        this.updatedAt = OffsetDateTime.now();
    }
}
