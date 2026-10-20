package com.sistema.gateway.security;

/**
 * Identidad extraída de un Access JWT ya verificado.
 *
 * @param empleadoId claim {@code sub}; es el identificador contra el que se
 *                   compara la propiedad del recurso
 * @param rol        claim {@code role}: {@code ADMIN} o {@code USER}
 */
public record AuthenticatedUser(String empleadoId, String rol) {

    public static final String ROL_ADMIN = "ADMIN";
    public static final String ROL_USER = "USER";

    /** Cabecera con la que el gateway propaga la identidad a los servicios. */
    public static final String HEADER_USUARIO = "X-Auth-User";
    public static final String HEADER_ROL = "X-Auth-Role";

    /** Atributo del exchange donde queda la identidad entre filtros. */
    public static final String ATRIBUTO = "gateway.authenticatedUser";

    public boolean esAdmin() {
        return ROL_ADMIN.equals(rol);
    }

    public boolean esDuenoDe(String empleadoIdDelRecurso) {
        return empleadoId != null && empleadoId.equals(empleadoIdDelRecurso);
    }
}
