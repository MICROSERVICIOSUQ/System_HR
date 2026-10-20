package com.microservicios.auth.model;

/**
 * Estado del ciclo de vida de una cuenta de acceso.
 *
 * <p>El reto exige distinguir la desactivación temporal de la permanente con un
 * estado y no con un booleano, porque de esa distinción depende el caso borde del
 * retiro durante vacaciones: al llegar {@code vacaciones.finalizadas} sobre una
 * cuenta {@link #DESACTIVADA_PERMANENTE} la cuenta <strong>no</strong> se reactiva.
 *
 * <p>{@link #PENDIENTE_ACTIVACION} amplía la lista ilustrativa del enunciado
 * (ACTIVA | SUSPENDIDA_TEMPORAL | DESACTIVADA_PERMANENTE) para representar la cuenta
 * recién creada desde {@code empleado.creado}, que existe pero todavía no tiene
 * contraseña. Conflactarla con SUSPENDIDA_TEMPORAL haría que una cuenta nunca
 * activada se "reactivara" al terminar unas vacaciones.
 */
public enum AccountStatus {

    /** Creada por {@code empleado.creado}. Sin contraseña: el login siempre falla. */
    PENDIENTE_ACTIVACION,

    /** Operativa. Es el único estado en el que se permite el login. */
    ACTIVA,

    /** Suspendida por {@code vacaciones.iniciadas}. Reactivable. */
    SUSPENDIDA_TEMPORAL,

    /** Baja definitiva por {@code empleado.retirado}. No reactivable. */
    DESACTIVADA_PERMANENTE;

    /** Solo una cuenta activa puede autenticarse. */
    public boolean permiteLogin() {
        return this == ACTIVA;
    }

    /** Una baja permanente no se revierte por eventos de vacaciones. */
    public boolean esReactivablePorVacaciones() {
        return this == SUSPENDIDA_TEMPORAL;
    }
}
