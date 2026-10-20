package com.microservicios.auth.model;

/**
 * Rol de la cuenta, viaja en el claim {@code role} del Access JWT y es la base del
 * RBAC que aplica el api-gateway.
 */
public enum Role {

    /** Acceso total: crear, modificar y eliminar en cualquier servicio. */
    ADMIN,

    /**
     * Solo lectura, con dos excepciones sobre sus propios datos:
     * {@code POST /auth/change-password} y {@code PUT /perfiles/{suEmpleadoId}}.
     */
    USER
}
