import { beforeEach, describe, test } from 'node:test';
import assert from 'node:assert/strict';

import { ProfileService, type EventEnvelope } from '../services/profile.service.ts';
import { ProfileError } from '../errors/profile.errors.ts';
import type {
  CreateProfileDto,
  EstadoFiltro,
  RetireProfileDto,
  UpdateProfileDto,
} from '../repositories/profile.repository.ts';

/**
 * Pruebas unitarias del servicio de perfiles.
 *
 * El servicio es donde vive todo lo que la auditoría pidió verificar —los cinco
 * campos canónicos, la marca de archivado, la idempotencia por eventId— y hasta
 * ahora no tenía ninguna prueba: las tres que existían comprobaban validación
 * HTTP a través del servidor. Aquí se prueba la lógica con un repositorio falso,
 * sin base de datos y sin red, siguiendo el ADR-010.
 *
 * El doble es un objeto en memoria y no un mock con expectativas: lo que importa
 * es el estado en el que queda el perfil, no la secuencia de llamadas.
 */

interface PerfilEnMemoria {
  id: string;
  empleadoId: string;
  nombre: string;
  apellido: string;
  email: string;
  cargo: string;
  departamentoId: string;
  fechaIngreso: Date;
  fechaRetiro: Date | null;
  estado: string;
  telefono: string | null;
  direccion: string | null;
  ciudad: string | null;
  biografia: string | null;
  fechaCreacion: Date;
  archivado: boolean;
  lastEventId: string;
  updatedAt: Date;
}

class RepositorioFake {
  perfiles = new Map<string, PerfilEnMemoria>();
  /** Fuerza un error de Prisma en la siguiente escritura. */
  errorEnLaProximaEscritura: { code: string } | null = null;

  async findByLastEventId(eventId: string) {
    for (const perfil of this.perfiles.values()) {
      if (perfil.lastEventId === eventId) return perfil;
    }
    return null;
  }

  async findByEmpleadoId(empleadoId: string) {
    return this.perfiles.get(empleadoId) ?? null;
  }

  async findAll(
    estado?: EstadoFiltro,
    pagination?: { page: number; limit: number; sortBy: string; order: 'asc' | 'desc' },
    archivado?: boolean,
  ) {
    let filtrados = [...this.perfiles.values()];
    if (estado !== undefined) filtrados = filtrados.filter((p) => p.estado === estado);
    if (archivado !== undefined) filtrados = filtrados.filter((p) => p.archivado === archivado);

    if (!pagination) return filtrados;

    const desde = (pagination.page - 1) * pagination.limit;
    return {
      total: filtrados.length,
      data: filtrados.slice(desde, desde + pagination.limit),
    };
  }

  async create(data: CreateProfileDto) {
    this.lanzarSiHayErrorProgramado();
    const perfil: PerfilEnMemoria = {
      id: `uuid-${data.empleadoId}`,
      empleadoId: data.empleadoId,
      nombre: data.nombre,
      apellido: data.apellido,
      email: data.email,
      cargo: data.cargo,
      departamentoId: data.departamentoId,
      fechaIngreso: data.fechaIngreso,
      fechaRetiro: null,
      estado: data.estado,
      telefono: null,
      direccion: null,
      ciudad: null,
      biografia: null,
      fechaCreacion: new Date('2026-04-16T10:00:00.000Z'),
      archivado: false,
      lastEventId: data.lastEventId,
      updatedAt: new Date('2026-04-16T10:00:00.000Z'),
    };
    this.perfiles.set(perfil.empleadoId, perfil);
    return perfil;
  }

  async update(empleadoId: string, data: UpdateProfileDto) {
    this.lanzarSiHayErrorProgramado();
    const actual = this.perfiles.get(empleadoId);
    if (!actual) throw Object.assign(new Error('no existe'), { code: 'P2025' });

    const siguiente: PerfilEnMemoria = { ...actual };
    for (const [clave, valor] of Object.entries(data)) {
      if (valor !== undefined) (siguiente as any)[clave] = valor;
    }
    this.perfiles.set(empleadoId, siguiente);
    return siguiente;
  }

  async retire(empleadoId: string, data: RetireProfileDto) {
    // Igual que el repositorio real: estado, fecha y archivado en una escritura.
    return this.update(empleadoId, {
      estado: 'RETIRADO',
      fechaRetiro: data.fechaRetiro,
      archivado: true,
      lastEventId: data.lastEventId,
    });
  }

  private lanzarSiHayErrorProgramado() {
    if (!this.errorEnLaProximaEscritura) return;
    const error = this.errorEnLaProximaEscritura;
    this.errorEnLaProximaEscritura = null;
    throw Object.assign(new Error('error de Prisma simulado'), error);
  }
}

class PublisherFake {
  publicados: unknown[] = [];
  fallar = false;

  async publishRetired(perfil: unknown) {
    if (this.fallar) throw new Error('RabbitMQ no disponible');
    this.publicados.push(perfil);
  }
}

let repositorio: RepositorioFake;
let publisher: PublisherFake;
let servicio: ProfileService;

function nuevoServicio() {
  repositorio = new RepositorioFake();
  publisher = new PublisherFake();
  return new ProfileService(repositorio as any, publisher as any);
}

const DATOS_VALIDOS = {
  empleadoId: 'E601',
  nombre: 'Laura',
  apellido: 'Méndez',
  email: 'laura.mendez@empresa.com',
  cargo: 'Desarrolladora',
  departamentoId: 'D001',
  fechaIngreso: '2026-04-15',
};

function evento(tipo: string, datos: Record<string, unknown> = {}, id = crypto.randomUUID()): EventEnvelope {
  return {
    id,
    type: tipo,
    version: '1.0',
    occurredAt: new Date().toISOString(),
    producer: 'employees-service',
    data: { id: 'E601', ...datos } as EventEnvelope['data'],
  };
}

beforeEach(() => {
  servicio = nuevoServicio();
});

// ── Consultas ───────────────────────────────────────────────────────────────

describe('listado de perfiles', () => {
  beforeEach(async () => {
    await servicio.create({ ...DATOS_VALIDOS, lastEventId: 'ev-1' });
    await servicio.create({ ...DATOS_VALIDOS, empleadoId: 'E602', lastEventId: 'ev-2' });
  });

  test('sin paginación devuelve un array', async () => {
    const resultado = await servicio.getAll();

    // La forma de la respuesta es parte del contrato: sin parámetros, array.
    assert.ok(Array.isArray(resultado));
    assert.equal(resultado.length, 2);
  });

  test('con paginación devuelve data y meta con totalPages', async () => {
    const resultado = (await servicio.getAll(undefined, {
      page: 1,
      limit: 1,
      sortBy: 'updatedAt',
      order: 'desc',
    })) as any;

    assert.equal(resultado.data.length, 1);
    assert.deepEqual(resultado.meta, { total: 2, page: 1, limit: 1, totalPages: 2 });
  });

  test('un estado que no existe se rechaza con 400', async () => {
    await assert.rejects(
      () => servicio.getAll('PAUSADO'),
      (error: ProfileError) => {
        assert.equal(error.statusCode, 400);
        assert.match(error.message, /Use ACTIVO o RETIRADO/);
        return true;
      },
    );
  });

  test('el filtro de archivado llega al repositorio', async () => {
    await servicio.retire('E602', 'ev-retiro');

    const vigentes = (await servicio.getAll(undefined, undefined, false)) as any[];
    const archivados = (await servicio.getAll(undefined, undefined, true)) as any[];

    assert.deepEqual(vigentes.map((p) => p.empleadoId), ['E601']);
    assert.deepEqual(archivados.map((p) => p.empleadoId), ['E602']);
  });

  test('consultar un perfil inexistente devuelve null, no un error', async () => {
    assert.equal(await servicio.getByEmpleadoId('NO-EXISTE'), null);
  });
});

// ── Creación ────────────────────────────────────────────────────────────────

describe('creación de un perfil', () => {
  test('crea el perfil con estado ACTIVO y sin archivar', async () => {
    const perfil: any = await servicio.create({ ...DATOS_VALIDOS, lastEventId: 'ev-1' });

    assert.equal(perfil.empleadoId, 'E601');
    assert.equal(perfil.estado, 'ACTIVO');
    assert.equal(perfil.archivado, false);
    assert.equal(perfil.fechaRetiro, null);
  });

  test('cada campo obligatorio que falte se rechaza con 400', async () => {
    for (const campo of Object.keys(DATOS_VALIDOS)) {
      const incompleto: any = { ...DATOS_VALIDOS };
      delete incompleto[campo];

      await assert.rejects(
        () => servicio.create(incompleto),
        (error: ProfileError) => {
          assert.equal(error.statusCode, 400);
          assert.match(error.message, new RegExp(`${campo} es obligatorio`));
          return true;
        },
        `no se rechazó la creación sin ${campo}`,
      );
    }
  });

  test('un email sin formato válido se rechaza con 400', async () => {
    await assert.rejects(
      () => servicio.create({ ...DATOS_VALIDOS, email: 'laura.arroba.empresa' }),
      (error: ProfileError) => error.statusCode === 400 && /email/.test(error.message),
    );
  });

  test('una fechaIngreso que no es fecha se rechaza con 400', async () => {
    await assert.rejects(
      () => servicio.create({ ...DATOS_VALIDOS, fechaIngreso: 'el-martes' }),
      (error: ProfileError) => error.statusCode === 400 && /fecha válida/.test(error.message),
    );
  });

  test('un segundo perfil para el mismo empleado se rechaza con 409', async () => {
    await servicio.create({ ...DATOS_VALIDOS, lastEventId: 'ev-1' });

    await assert.rejects(
      () => servicio.create({ ...DATOS_VALIDOS, lastEventId: 'ev-2' }),
      (error: ProfileError) => {
        assert.equal(error.statusCode, 409);
        assert.match(error.message, /Ya existe un perfil/);
        return true;
      },
    );
  });

  test('repetir el mismo lastEventId no crea un segundo perfil', async () => {
    const primero: any = await servicio.create({ ...DATOS_VALIDOS, lastEventId: 'ev-unico' });
    const repetido: any = await servicio.create({
      ...DATOS_VALIDOS,
      empleadoId: 'E999',
      lastEventId: 'ev-unico',
    });

    // Idempotencia: la segunda llamada devuelve el perfil de la primera en lugar
    // de crear otro. Es lo que permite que RabbitMQ reentregue sin duplicar.
    assert.equal(repetido.empleadoId, primero.empleadoId);
    assert.equal(repositorio.perfiles.size, 1);
  });

  test('un perfil nuevo no puede nacer RETIRADO', async () => {
    await assert.rejects(
      () => servicio.create({ ...DATOS_VALIDOS, estado: 'RETIRADO' }),
      (error: ProfileError) => {
        assert.equal(error.statusCode, 400);
        assert.match(error.message, /debe iniciar en estado ACTIVO/);
        return true;
      },
    );
  });
});

// ── Actualización ───────────────────────────────────────────────────────────

describe('actualización de un perfil', () => {
  beforeEach(async () => {
    await servicio.create({ ...DATOS_VALIDOS, lastEventId: 'ev-alta' });
  });

  test('los cinco campos canónicos se pueden editar', async () => {
    const perfil: any = await servicio.update('E601', {
      telefono: '+57 311 111 1111',
      direccion: 'Carrera 14 # 3-45',
      ciudad: 'Armenia',
      biografia: 'Backend y observabilidad.',
      lastEventId: 'ev-edicion',
    });

    assert.equal(perfil.telefono, '+57 311 111 1111');
    assert.equal(perfil.direccion, 'Carrera 14 # 3-45');
    assert.equal(perfil.ciudad, 'Armenia');
    assert.equal(perfil.biografia, 'Backend y observabilidad.');
  });

  test('una petición sin ningún campo se rechaza con 400', async () => {
    await assert.rejects(
      () => servicio.update('E601', {}),
      (error: ProfileError) => {
        assert.equal(error.statusCode, 400);
        assert.match(error.message, /al menos un campo/);
        return true;
      },
    );
  });

  test('un campo en blanco se rechaza con 400', async () => {
    await assert.rejects(
      () => servicio.update('E601', { ciudad: '   ' }),
      (error: ProfileError) => error.statusCode === 400 && /no puede estar vacío/.test(error.message),
    );
  });

  test('un email inválido se rechaza con 400', async () => {
    await assert.rejects(
      () => servicio.update('E601', { email: 'sin-arroba' }),
      (error: ProfileError) => error.statusCode === 400,
    );
  });

  test('actualizar un perfil que no existe devuelve 404', async () => {
    await assert.rejects(
      () => servicio.update('NO-EXISTE', { ciudad: 'Pereira' }),
      (error: ProfileError) => {
        assert.equal(error.statusCode, 404);
        return true;
      },
    );
  });

  test('repetir el lastEventId devuelve el perfil sin volver a escribir', async () => {
    await servicio.update('E601', { ciudad: 'Armenia', lastEventId: 'ev-ciudad' });
    const repetido: any = await servicio.update('E601', {
      ciudad: 'Cali',
      lastEventId: 'ev-ciudad',
    });

    assert.equal(repetido.ciudad, 'Armenia', 'la segunda escritura no debía aplicarse');
  });

  test('pasar a RETIRADO archiva el perfil y publica el evento', async () => {
    const perfil: any = await servicio.update('E601', {
      estado: 'RETIRADO',
      lastEventId: 'ev-retiro',
    });

    // El archivado va en la misma escritura que el cambio de estado: si fueran
    // dos, un fallo entre ellas dejaría un perfil retirado sin archivar y
    // ?archivado=false seguiría devolviéndolo.
    assert.equal(perfil.estado, 'RETIRADO');
    assert.equal(perfil.archivado, true);
    assert.ok(perfil.fechaRetiro instanceof Date);
    assert.equal(publisher.publicados.length, 1);
  });

  test('un perfil RETIRADO no puede volver a ACTIVO', async () => {
    await servicio.update('E601', { estado: 'RETIRADO', lastEventId: 'ev-retiro' });

    await assert.rejects(
      () => servicio.update('E601', { estado: 'ACTIVO', lastEventId: 'ev-revivir' }),
      (error: ProfileError) => {
        assert.equal(error.statusCode, 409);
        assert.match(error.message, /no puede volver a ACTIVO/);
        return true;
      },
    );
  });

  test('si el evento de retiro no se puede publicar, se responde 503', async () => {
    publisher.fallar = true;

    await assert.rejects(
      () => servicio.update('E601', { estado: 'RETIRADO', lastEventId: 'ev-retiro' }),
      (error: ProfileError) => {
        assert.equal(error.statusCode, 503);
        return true;
      },
    );

    // El perfil sí quedó retirado en la base: el 503 avisa de que el evento no
    // salió, no de que la escritura fallara. Es la limitación conocida de
    // publicar sin Outbox.
    assert.equal(repositorio.perfiles.get('E601')?.estado, 'RETIRADO');
  });

  test('retire() marca RETIRADO y archiva', async () => {
    const perfil: any = await servicio.retire('E601', 'ev-retiro');

    assert.equal(perfil.estado, 'RETIRADO');
    assert.equal(perfil.archivado, true);
  });
});

// ── Consumo de eventos ──────────────────────────────────────────────────────

describe('procesamiento de eventos de RabbitMQ', () => {
  test('un evento de otro dominio se ignora', async () => {
    assert.equal(await servicio.processEvent(evento('vacaciones.programadas')), 'IGNORED');
  });

  test('un evento sin id o sin data.id se rechaza con 400', async () => {
    const sinDataId = evento('empleado.creado');
    (sinDataId.data as any).id = '';

    await assert.rejects(
      () => servicio.processEvent(sinDataId),
      (error: ProfileError) => error.statusCode === 400,
    );
  });

  test('empleado.creado crea el perfil', async () => {
    const resultado = await servicio.processEvent(
      evento('empleado.creado', {
        nombre: 'Laura',
        apellido: 'Méndez',
        email: 'laura@empresa.com',
        cargo: 'Desarrolladora',
        departamentoId: 'D001',
        fechaIngreso: '2026-04-15',
      }),
    );

    assert.equal(resultado, 'CREATED');
    assert.equal(repositorio.perfiles.get('E601')?.estado, 'ACTIVO');
  });

  test('el mismo evento dos veces no se aplica dos veces', async () => {
    const mismoEvento = evento('empleado.creado', {
      nombre: 'Laura',
      apellido: 'Méndez',
      email: 'laura@empresa.com',
      cargo: 'Desarrolladora',
      departamentoId: 'D001',
    });

    assert.equal(await servicio.processEvent(mismoEvento), 'CREATED');
    assert.equal(await servicio.processEvent(mismoEvento), 'DUPLICATE');
    assert.equal(repositorio.perfiles.size, 1);
  });

  test('un empleado.creado sin los datos mínimos se rechaza con 400', async () => {
    await assert.rejects(
      () => servicio.processEvent(evento('empleado.creado', { nombre: 'Laura' })),
      (error: ProfileError) => error.statusCode === 400,
    );
  });

  test('empleado.actualizado modifica el perfil existente', async () => {
    await servicio.create({ ...DATOS_VALIDOS, lastEventId: 'ev-alta' });

    const resultado = await servicio.processEvent(
      evento('empleado.actualizado', { cargo: 'Líder técnica' }),
    );

    assert.equal(resultado, 'UPDATED');
    assert.equal(repositorio.perfiles.get('E601')?.cargo, 'Líder técnica');
  });

  test('empleado.actualizado sin perfil previo lo crea', async () => {
    // RabbitMQ no garantiza orden entre mensajes de colas distintas, así que el
    // actualizado puede llegar antes que el creado tras una reentrega.
    const resultado = await servicio.processEvent(
      evento('empleado.actualizado', {
        nombre: 'Laura',
        apellido: 'Méndez',
        email: 'laura@empresa.com',
        cargo: 'Desarrolladora',
        departamentoId: 'D001',
      }),
    );

    assert.equal(resultado, 'CREATED');
    assert.ok(repositorio.perfiles.has('E601'));
  });

  test('empleado.retirado archiva el perfil', async () => {
    await servicio.create({ ...DATOS_VALIDOS, lastEventId: 'ev-alta' });

    const resultado = await servicio.processEvent(
      evento('empleado.retirado', { fechaRetiro: '2026-11-09T16:30:00.000Z' }),
    );

    assert.equal(resultado, 'RETIRED');
    const perfil = repositorio.perfiles.get('E601');
    assert.equal(perfil?.estado, 'RETIRADO');
    assert.equal(perfil?.archivado, true);
    assert.equal(perfil?.fechaRetiro?.toISOString(), '2026-11-09T16:30:00.000Z');
  });

  test('empleado.retirado sin perfil se ignora en lugar de fallar', async () => {
    // Si fallara, el mensaje volvería a la cola indefinidamente por algo que no
    // tiene arreglo: no hay perfil que retirar.
    assert.equal(await servicio.processEvent(evento('empleado.retirado')), 'IGNORED');
  });

  test('un conflicto de UNIQUE entre réplicas se resuelve como duplicado', async () => {
    repositorio.errorEnLaProximaEscritura = { code: 'P2002' };

    const resultado = await servicio.processEvent(
      evento('empleado.creado', {
        nombre: 'Laura',
        apellido: 'Méndez',
        email: 'laura@empresa.com',
        cargo: 'Desarrolladora',
        departamentoId: 'D001',
      }),
    );

    assert.equal(resultado, 'DUPLICATE');
  });

  test('un error que no es de duplicado se propaga', async () => {
    repositorio.errorEnLaProximaEscritura = { code: 'P1001' };

    await assert.rejects(() =>
      servicio.processEvent(
        evento('empleado.creado', {
          nombre: 'Laura',
          apellido: 'Méndez',
          email: 'laura@empresa.com',
          cargo: 'Desarrolladora',
          departamentoId: 'D001',
        }),
      ),
    );
  });
});
