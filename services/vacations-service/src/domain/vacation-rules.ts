/**
 * Reglas de dominio de los períodos de vacaciones, como funciones puras.
 *
 * Están aquí, fuera del servicio y del repositorio, por una razón concreta: son
 * lógica sobre rangos de fechas con muchos casos borde, y probarlas por HTTP o
 * contra PostgreSQL sería lento y poco preciso. Al ser puras se verifican con una
 * tabla de casos en milisegundos y sin Docker.
 *
 * Son la ÚNICA fuente de verdad de estas reglas: el servicio y el repositorio las
 * invocan en lugar de reimplementar las comparaciones.
 */

/** Estados en los que un período ocupa el calendario del empleado. */
export const ESTADOS_ACTIVOS = ['PROGRAMADA', 'EN_CURSO'] as const;

/** Lo mínimo que necesita una regla para decidir sobre un período existente. */
export interface RangoDePeriodo {
  fechaInicio: Date;
  fechaFin: Date;
}

/**
 * Normaliza una fecha al inicio de su día en UTC.
 *
 * Las fechas de un período son días completos, no instantes. Sin normalizar, dos
 * valores del mismo día con horas distintas se compararían como diferentes y el
 * solapamiento o la validación de fecha pasada darían resultados erráticos según
 * la hora a la que se ejecute.
 */
export function aDiaUtc(fecha: Date): Date {
  const d = new Date(fecha.getTime());
  d.setUTCHours(0, 0, 0, 0);
  return d;
}

/**
 * ¿La fecha de fin es anterior a la de inicio?
 *
 * Que coincidan es válido: un período de un solo día es legítimo.
 */
export function finEsAnteriorAInicio(inicio: Date, fin: Date): boolean {
  return aDiaUtc(fin).getTime() < aDiaUtc(inicio).getTime();
}

/** ¿La fecha de inicio quedó en el pasado respecto al día de referencia? */
export function inicioEsPasado(inicio: Date, hoy: Date): boolean {
  return aDiaUtc(inicio).getTime() < aDiaUtc(hoy).getTime();
}

/**
 * ¿Se solapan dos rangos de días cerrados [aInicio, aFin] y [bInicio, bFin]?
 *
 * Predicado clásico de intersección de intervalos: dos rangos NO se solapan solo
 * si uno termina antes de que el otro empiece. Ambos extremos son inclusivos,
 * así que compartir un único día ya cuenta como solapamiento: un empleado no
 * puede estar en dos períodos de vacaciones el mismo día.
 */
export function rangosSeSolapan(
  aInicio: Date,
  aFin: Date,
  bInicio: Date,
  bFin: Date
): boolean {
  const ai = aDiaUtc(aInicio).getTime();
  const af = aDiaUtc(aFin).getTime();
  const bi = aDiaUtc(bInicio).getTime();
  const bf = aDiaUtc(bFin).getTime();

  return ai <= bf && af >= bi;
}

/**
 * De una lista de períodos existentes, los que choquen con el rango propuesto.
 *
 * Devuelve todos los coincidentes y no solo el primero para que quien llame pueda
 * informar del conflicto más relevante o de todos.
 */
export function periodosSolapados<T extends RangoDePeriodo>(
  nuevoInicio: Date,
  nuevoFin: Date,
  existentes: readonly T[]
): T[] {
  return existentes.filter((p) =>
    rangosSeSolapan(nuevoInicio, nuevoFin, p.fechaInicio, p.fechaFin)
  );
}
