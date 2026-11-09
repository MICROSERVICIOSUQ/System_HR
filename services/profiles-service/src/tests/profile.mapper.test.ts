import { describe, test } from 'node:test';
import assert from 'node:assert/strict';

import {
  presentarPerfil,
  presentarPerfiles,
  type PerfilPersistido,
} from '../mappers/profile.mapper.ts';

/** Perfil completo, con los cinco campos canónicos rellenos. */
function perfil(cambios: Partial<PerfilPersistido> = {}): PerfilPersistido {
  return {
    id: '11111111-2222-3333-4444-555555555555',
    empleadoId: 'E601',
    nombre: 'Laura',
    apellido: 'Méndez',
    email: 'laura.mendez@empresa.com',
    cargo: 'Desarrolladora',
    departamentoId: 'D001',
    fechaIngreso: new Date('2026-04-15T00:00:00.000Z'),
    fechaRetiro: null,
    estado: 'ACTIVO',
    telefono: '+57 300 000 0000',
    direccion: 'Calle 10 # 5-20',
    ciudad: 'Armenia',
    biografia: 'Desarrolladora backend.',
    fechaCreacion: new Date('2026-04-16T10:00:00.000Z'),
    archivado: false,
    lastEventId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
    updatedAt: new Date('2026-04-16T10:00:00.000Z'),
    ...cambios,
  };
}

describe('presentación del perfil en la frontera HTTP', () => {
  test('empleadoId y employeeId llevan el mismo valor', () => {
    const salida = presentarPerfil(perfil());

    // El alias es la decisión del ADR-013: la columna se renombró a empleadoId
    // y la respuesta sigue llevando el nombre antiguo para no romper clientes.
    assert.equal(salida.empleadoId, 'E601');
    assert.equal(salida.employeeId, 'E601');
  });

  test('los cinco campos canónicos salen aunque estén vacíos', () => {
    const salida = presentarPerfil(
      perfil({ telefono: null, direccion: null, ciudad: null, biografia: null }),
    );

    // Se devuelven siempre: un cliente que dibuje un formulario necesita saber
    // que el campo existe aunque no tenga valor. Un esquema variable obligaría a
    // comprobar la existencia de cada clave.
    for (const campo of ['telefono', 'direccion', 'ciudad', 'biografia', 'fechaCreacion']) {
      assert.ok(campo in salida, `faltaba ${campo} en la respuesta`);
    }
    assert.equal(salida.telefono, null);
    assert.equal(salida.biografia, null);
  });

  test('fechaCreacion y fechaIngreso son datos distintos', () => {
    const salida = presentarPerfil(
      perfil({
        fechaIngreso: new Date('2026-01-10T00:00:00.000Z'),
        fechaCreacion: new Date('2026-04-16T10:00:00.000Z'),
      }),
    );

    // fechaIngreso es cuándo entró el empleado a la empresa; fechaCreacion,
    // cuándo nació su perfil en el read model. Pueden diferir en meses si el
    // evento se procesó tarde, y confundirlas haría mentir a un listado por
    // antigüedad.
    assert.notDeepEqual(salida.fechaIngreso, salida.fechaCreacion);
  });

  test('la marca de archivado viaja en la respuesta', () => {
    assert.equal(presentarPerfil(perfil({ archivado: true })).archivado, true);
    assert.equal(presentarPerfil(perfil()).archivado, false);
  });

  test('no se filtran campos que no estén en el modelo publicado', () => {
    const conExtra = { ...perfil(), passwordHash: 'no-debe-salir' } as PerfilPersistido;
    const salida = presentarPerfil(conExtra) as Record<string, unknown>;

    // El mapeador construye la respuesta campo a campo en lugar de propagar el
    // objeto con spread: así, una columna nueva en la base no aparece en la API
    // por accidente.
    assert.equal('passwordHash' in salida, false);
  });

  test('presentarPerfiles aplica la misma forma a toda la lista', () => {
    const salida = presentarPerfiles([perfil(), perfil({ empleadoId: 'E602' })]);

    assert.equal(salida.length, 2);
    assert.deepEqual(
      salida.map((p) => p.employeeId),
      ['E601', 'E602'],
    );
  });
});
