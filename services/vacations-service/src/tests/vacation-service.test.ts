import { beforeEach, describe, test } from 'node:test';
import assert from 'node:assert/strict';
import { ValidationError, VacationService } from '../services/vacation.service.ts';
import { periodosSolapados } from '../domain/vacation-rules.ts';

/**
 * Pruebas unitarias de VacationService con test doubles.
 *
 * El repositorio y el publicador de eventos se sustituyen por dobles en memoria:
 * ninguna de estas pruebas necesita Docker, PostgreSQL ni RabbitMQ. Lo que se
 * verifica es la decisión del servicio —qué acepta, qué rechaza y qué publica—,
 * no la infraestructura.
 */

const d = (iso: string) => new Date(`${iso}T00:00:00.000Z`);
const hoyIso = () => new Date().toISOString().split('T')[0];
const enDias = (dias: number) => {
  const f = new Date();
  f.setUTCHours(0, 0, 0, 0);
  f.setUTCDate(f.getUTCDate() + dias);
  return f.toISOString().split('T')[0];
};

interface PeriodoFake {
  id: string;
  empleadoId: string;
  fechaInicio: Date;
  fechaFin: Date;
  estado: string;
  fechaCreacion: Date;
}

/**
 * Doble del repositorio: guarda todo en arrays.
 *
 * Reusa la regla de dominio real para el solapamiento, de modo que el doble no
 * reimplemente —ni contradiga— la lógica que se está protegiendo.
 */
class RepositorioFake {
  balances = new Map<string, { employeeId: string; estadoEmpleado: string }>();
  periodos: PeriodoFake[] = [];
  private secuencia = 0;

  conEmpleado(employeeId: string, estadoEmpleado = 'ACTIVO') {
    this.balances.set(employeeId, { employeeId, estadoEmpleado });
    return this;
  }

  conPeriodo(p: Partial<PeriodoFake> & { empleadoId: string; fechaInicio: Date; fechaFin: Date }) {
    this.secuencia += 1;
    this.periodos.push({
      id: p.id ?? `fake-${this.secuencia}`,
      estado: p.estado ?? 'PROGRAMADA',
      fechaCreacion: p.fechaCreacion ?? new Date(),
      ...p,
    } as PeriodoFake);
    return this;
  }

  async findByEmployeeId(employeeId: string) {
    return this.balances.get(employeeId) ?? null;
  }

  async findOverlappingPeriods(
    empleadoId: string,
    fechaInicio: Date,
    fechaFin: Date,
    excludePeriodId?: string
  ) {
    const activos = this.periodos.filter(
      (p) =>
        p.empleadoId === empleadoId &&
        ['PROGRAMADA', 'EN_CURSO'].includes(p.estado) &&
        p.id !== excludePeriodId
    );
    return periodosSolapados(fechaInicio, fechaFin, activos);
  }

  async createPeriod(data: { empleadoId: string; fechaInicio: Date; fechaFin: Date; estado?: string }) {
    this.secuencia += 1;
    const creado: PeriodoFake = {
      id: `fake-${this.secuencia}`,
      empleadoId: data.empleadoId,
      fechaInicio: data.fechaInicio,
      fechaFin: data.fechaFin,
      estado: data.estado ?? 'PROGRAMADA',
      fechaCreacion: new Date(),
    };
    this.periodos.push(creado);
    return creado;
  }

  async findPeriodById(id: string) {
    return this.periodos.find((p) => p.id === id) ?? null;
  }

  async findPeriodsToStart(hasta: Date) {
    return this.periodos.filter(
      (p) => p.estado === 'PROGRAMADA' && p.fechaInicio.getTime() <= hasta.getTime()
    );
  }

  async findPeriodsToFinish(inicioDelDia: Date) {
    return this.periodos.filter(
      (p) => p.estado === 'EN_CURSO' && p.fechaFin.getTime() < inicioDelDia.getTime()
    );
  }

  async transitionPeriodState(id: string, estadoEsperado: string, estadoNuevo: string) {
    const p = this.periodos.find((x) => x.id === id && x.estado === estadoEsperado);
    if (!p) return false;
    p.estado = estadoNuevo;
    return true;
  }
}

/** Doble del publicador: registra lo que se habría enviado al broker. */
class PublisherFake {
  publicados: Array<{ tipo: string; empleadoId: string; estado: string }> = [];

  async publishVacacionesProgramadas(data: any) {
    this.publicados.push({ tipo: 'vacaciones.programadas', empleadoId: data.empleadoId, estado: data.estado });
  }
  async publishVacacionesReprogramadas(data: any) {
    this.publicados.push({ tipo: 'vacaciones.reprogramadas', empleadoId: data.empleadoId, estado: data.estado });
  }
  async publishVacacionesIniciadas(data: any) {
    this.publicados.push({ tipo: 'vacaciones.iniciadas', empleadoId: data.empleadoId, estado: data.estado });
  }
  async publishVacacionesFinalizadas(data: any) {
    this.publicados.push({ tipo: 'vacaciones.finalizadas', empleadoId: data.empleadoId, estado: data.estado });
  }

  tipos() {
    return this.publicados.map((p) => p.tipo);
  }
}

let repo: RepositorioFake;
let publisher: PublisherFake;
let service: VacationService;

function construir() {
  repo = new RepositorioFake();
  publisher = new PublisherFake();
  service = new VacationService(repo as any, publisher as any);
}

/** Espera a que las publicaciones fire-and-forget del servicio se resuelvan. */
const dejarCorrerMicrotareas = () => new Promise((r) => setImmediate(r));

describe('VacationService.createPeriod — las cuatro validaciones', () => {
  beforeEach(construir);

  test('camino feliz: crea el período en PROGRAMADA y publica vacaciones.programadas', async () => {
    repo.conEmpleado('E001');

    const r = await service.createPeriod({
      empleadoId: 'E001',
      fechaInicio: enDias(5),
      fechaFin: enDias(10),
    } as any);

    assert.equal(r.estado, 'PROGRAMADA');
    assert.equal(r.empleadoId, 'E001');
    assert.equal(repo.periodos.length, 1);

    await dejarCorrerMicrotareas();
    assert.deepEqual(publisher.tipos(), ['vacaciones.programadas']);
  });

  test('validación 1: rechaza fechaFin anterior a fechaInicio', async () => {
    repo.conEmpleado('E001');

    await assert.rejects(
      () => service.createPeriod({ empleadoId: 'E001', fechaInicio: enDias(10), fechaFin: enDias(5) } as any),
      (e: ValidationError) => {
        assert.equal(e.name, 'ValidationError');
        assert.equal(e.statusCode, 400);
        assert.match(e.message, /no puede ser anterior a fechaInicio/);
        return true;
      }
    );
    assert.equal(repo.periodos.length, 0, 'no debe persistir nada');
  });

  test('validación 1: acepta un período de un solo día', async () => {
    repo.conEmpleado('E001');
    const r = await service.createPeriod({
      empleadoId: 'E001',
      fechaInicio: enDias(3),
      fechaFin: enDias(3),
    } as any);
    assert.equal(r.fechaInicio, r.fechaFin);
  });

  test('validación 2: rechaza fechaInicio en el pasado', async () => {
    repo.conEmpleado('E001');

    await assert.rejects(
      () => service.createPeriod({ empleadoId: 'E001', fechaInicio: enDias(-1), fechaFin: enDias(5) } as any),
      (e: ValidationError) => {
        assert.match(e.message, /no puede ser una fecha pasada/);
        return true;
      }
    );
  });

  test('validación 2: acepta que el período empiece hoy', async () => {
    repo.conEmpleado('E001');
    const r = await service.createPeriod({
      empleadoId: 'E001',
      fechaInicio: hoyIso(),
      fechaFin: hoyIso(),
    } as any);
    assert.equal(r.estado, 'PROGRAMADA');
  });

  test('validación 3: rechaza con 404 si el empleado no existe en el read model', async () => {
    // Sin repo.conEmpleado(...): el empleado no está replicado.
    await assert.rejects(
      () => service.createPeriod({ empleadoId: 'FANTASMA', fechaInicio: enDias(5), fechaFin: enDias(8) } as any),
      (e: ValidationError) => {
        assert.equal(e.statusCode, 404);
        assert.match(e.message, /no encontrado/);
        return true;
      }
    );
  });

  test('validación 4: rechaza el solapamiento con un período PROGRAMADA', async () => {
    repo.conEmpleado('E001').conPeriodo({
      empleadoId: 'E001',
      fechaInicio: d('2026-12-10'),
      fechaFin: d('2026-12-20'),
      estado: 'PROGRAMADA',
    });

    await assert.rejects(
      () => service.createPeriod({ empleadoId: 'E001', fechaInicio: '2026-12-15', fechaFin: '2026-12-25' } as any),
      (e: ValidationError) => {
        assert.match(e.message, /se solapa con uno existente/);
        assert.match(e.message, /PROGRAMADA/);
        return true;
      }
    );
    assert.equal(repo.periodos.length, 1, 'no debe añadir un segundo período');
  });

  test('validación 4: rechaza el solapamiento de un solo día compartido', async () => {
    repo.conEmpleado('E001').conPeriodo({
      empleadoId: 'E001',
      fechaInicio: d('2026-12-10'),
      fechaFin: d('2026-12-20'),
      estado: 'EN_CURSO',
    });

    await assert.rejects(
      () => service.createPeriod({ empleadoId: 'E001', fechaInicio: '2026-12-20', fechaFin: '2026-12-28' } as any),
      (e: ValidationError) => {
        assert.match(e.message, /se solapa/);
        return true;
      }
    );
  });

  test('validación 4: un período CANCELADA no bloquea las nuevas fechas', async () => {
    repo.conEmpleado('E001').conPeriodo({
      empleadoId: 'E001',
      fechaInicio: d('2026-12-10'),
      fechaFin: d('2026-12-20'),
      estado: 'CANCELADA',
    });

    const r = await service.createPeriod({
      empleadoId: 'E001',
      fechaInicio: '2026-12-15',
      fechaFin: '2026-12-25',
    } as any);
    assert.equal(r.estado, 'PROGRAMADA');
  });

  test('validación 4: el período de OTRO empleado no bloquea', async () => {
    repo.conEmpleado('E001').conPeriodo({
      empleadoId: 'E999',
      fechaInicio: d('2026-12-10'),
      fechaFin: d('2026-12-20'),
      estado: 'PROGRAMADA',
    });

    const r = await service.createPeriod({
      empleadoId: 'E001',
      fechaInicio: '2026-12-15',
      fechaFin: '2026-12-18',
    } as any);
    assert.equal(r.empleadoId, 'E001');
  });

  test('ninguna validación fallida publica eventos', async () => {
    repo.conEmpleado('E001');
    await assert.rejects(() =>
      service.createPeriod({ empleadoId: 'E001', fechaInicio: enDias(10), fechaFin: enDias(5) } as any)
    );
    await dejarCorrerMicrotareas();
    assert.deepEqual(publisher.publicados, []);
  });
});

describe('VacationService — scheduler: transiciones por paso del tiempo', () => {
  beforeEach(construir);

  test('un período PROGRAMADA cuya fecha llegó pasa a EN_CURSO y publica vacaciones.iniciadas', async () => {
    repo.conEmpleado('E001').conPeriodo({
      id: 'p1',
      empleadoId: 'E001',
      fechaInicio: d('2026-01-01'),
      fechaFin: d('2030-01-01'),
      estado: 'PROGRAMADA',
    });

    const r = await service.runSchedulerCycle();

    assert.equal(r.iniciadas, 1);
    assert.equal(repo.periodos[0].estado, 'EN_CURSO');
    assert.deepEqual(publisher.tipos(), ['vacaciones.iniciadas']);
  });

  test('un período EN_CURSO cuya fechaFin pasó queda FINALIZADA y publica vacaciones.finalizadas', async () => {
    repo.conEmpleado('E001').conPeriodo({
      id: 'p1',
      empleadoId: 'E001',
      fechaInicio: d('2026-01-01'),
      fechaFin: d('2026-01-05'),
      estado: 'EN_CURSO',
    });

    const r = await service.runSchedulerCycle();

    assert.equal(r.finalizadas, 1);
    assert.equal(repo.periodos[0].estado, 'FINALIZADA');
    assert.deepEqual(publisher.tipos(), ['vacaciones.finalizadas']);
  });

  test('un período futuro no se toca', async () => {
    repo.conEmpleado('E001').conPeriodo({
      id: 'p1',
      empleadoId: 'E001',
      fechaInicio: d('2030-06-01'),
      fechaFin: d('2030-06-10'),
      estado: 'PROGRAMADA',
    });

    const r = await service.runSchedulerCycle();

    assert.deepEqual(r, { iniciadas: 0, finalizadas: 0 });
    assert.equal(repo.periodos[0].estado, 'PROGRAMADA');
    assert.deepEqual(publisher.publicados, []);
  });

  test('la fechaFin es inclusiva: un período que termina hoy sigue EN_CURSO', async () => {
    const hoy = new Date();
    hoy.setUTCHours(0, 0, 0, 0);

    repo.conEmpleado('E001').conPeriodo({
      id: 'p1',
      empleadoId: 'E001',
      fechaInicio: hoy,
      fechaFin: hoy,
      estado: 'EN_CURSO',
    });

    const r = await service.runSchedulerCycle();

    assert.equal(r.finalizadas, 0);
    assert.equal(repo.periodos[0].estado, 'EN_CURSO');
  });

  test('dos ciclos seguidos no publican el evento dos veces (compare-and-set)', async () => {
    repo.conEmpleado('E001').conPeriodo({
      id: 'p1',
      empleadoId: 'E001',
      fechaInicio: d('2026-01-01'),
      fechaFin: d('2030-01-01'),
      estado: 'PROGRAMADA',
    });

    await service.runSchedulerCycle();
    const segundo = await service.runSchedulerCycle();

    assert.equal(segundo.iniciadas, 0);
    assert.deepEqual(publisher.tipos(), ['vacaciones.iniciadas']);
  });
});

describe('VacationService — endpoints de disparo manual', () => {
  beforeEach(construir);

  test('forcePeriodStart arranca un período futuro sin esperar la fecha', async () => {
    repo.conEmpleado('E001').conPeriodo({
      id: 'p1',
      empleadoId: 'E001',
      fechaInicio: d('2030-06-01'),
      fechaFin: d('2030-06-10'),
      estado: 'PROGRAMADA',
    });

    const r = await service.forcePeriodStart('p1');

    assert.equal(r.estado, 'EN_CURSO');
    assert.deepEqual(publisher.tipos(), ['vacaciones.iniciadas']);
  });

  test('forcePeriodStart rechaza un período que no está PROGRAMADA', async () => {
    repo.conEmpleado('E001').conPeriodo({
      id: 'p1',
      empleadoId: 'E001',
      fechaInicio: d('2030-06-01'),
      fechaFin: d('2030-06-10'),
      estado: 'EN_CURSO',
    });

    await assert.rejects(
      () => service.forcePeriodStart('p1'),
      (e: ValidationError) => {
        assert.match(e.message, /estado PROGRAMADA/);
        return true;
      }
    );
  });

  test('forcePeriodFinish cierra un período EN_CURSO y publica el evento', async () => {
    repo.conEmpleado('E001').conPeriodo({
      id: 'p1',
      empleadoId: 'E001',
      fechaInicio: d('2026-01-01'),
      fechaFin: d('2030-01-01'),
      estado: 'EN_CURSO',
    });

    const r = await service.forcePeriodFinish('p1');

    assert.equal(r.estado, 'FINALIZADA');
    assert.deepEqual(publisher.tipos(), ['vacaciones.finalizadas']);
  });

  test('forzar un período inexistente devuelve 404', async () => {
    await assert.rejects(
      () => service.forcePeriodStart('no-existe'),
      (e: ValidationError) => {
        assert.equal(e.statusCode, 404);
        return true;
      }
    );
  });
});
