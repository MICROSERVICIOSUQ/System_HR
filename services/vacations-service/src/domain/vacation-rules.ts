/** Las fechas representan días UTC. fechaFin es el día de regreso. */
export const ESTADOS_ACTIVOS = ['PROGRAMADA', 'EN_CURSO'] as const;
export interface RangoDePeriodo { fechaInicio: Date; fechaFin: Date; }

export function aDiaUtc(fecha: Date): Date {
  const dia = new Date(fecha.getTime());
  dia.setUTCHours(0, 0, 0, 0);
  return dia;
}

export function finNoEsPosteriorAInicio(inicio: Date, fin: Date): boolean {
  return aDiaUtc(fin).getTime() <= aDiaUtc(inicio).getTime();
}

export function inicioEsPasado(inicio: Date, hoy: Date): boolean {
  return aDiaUtc(inicio).getTime() < aDiaUtc(hoy).getTime();
}

// Rangos [inicio, regreso): compartir el día de regreso no es solapamiento.
export function rangosSeSolapan(aInicio: Date, aFin: Date, bInicio: Date, bFin: Date): boolean {
  const ai = aDiaUtc(aInicio).getTime(), af = aDiaUtc(aFin).getTime();
  const bi = aDiaUtc(bInicio).getTime(), bf = aDiaUtc(bFin).getTime();
  return ai < af && bi < bf && ai < bf && af > bi;
}

export function periodosSolapados<T extends RangoDePeriodo>(
  inicio: Date, fin: Date, existentes: readonly T[]
): T[] {
  return existentes.filter(p => rangosSeSolapan(inicio, fin, p.fechaInicio, p.fechaFin));
}
