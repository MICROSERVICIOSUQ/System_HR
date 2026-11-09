import { describe, test } from 'node:test';
import assert from 'node:assert/strict';

import {
  especificacionOpenApi,
  CAMPOS_DOCUMENTADOS_DEL_PERFIL,
} from '../docs/openapi.ts';
import { presentarPerfil, type PerfilPersistido } from '../mappers/profile.mapper.ts';
import { CAMPOS_ORDENABLES } from '../repositories/profile.repository.ts';
import router from '../routes/profile.routes.ts';

/**
 * Pruebas de consistencia entre el contrato y el código.
 *
 * Son la razón por la que se puede defender un OpenAPI escrito a mano en este
 * servicio. El defecto que la auditoría encontró en el antiguo
 * departments-service no era escribirlo a mano: era que nadie comprobaba que
 * siguiera describiendo la realidad, y los dos se desincronizaron en silencio.
 *
 * Aquí la comprobación es automática. Si alguien añade una ruta, un campo a la
 * respuesta o un valor de ordenación y no toca el contrato, esto falla.
 */
describe('el contrato OpenAPI describe el servicio real', () => {
  const rutasDocumentadas = Object.keys(especificacionOpenApi.paths);

  test('las rutas de perfil documentadas son las que registra el router', () => {
    // Las rutas del router son relativas al punto de montaje (/perfiles).
    const registradas = new Set<string>();
    for (const capa of (router as any).stack ?? []) {
      const ruta = capa?.route;
      if (!ruta) continue;
      const metodos = Object.keys(ruta.methods).filter((m) => ruta.methods[m]);
      for (const metodo of metodos) {
        registradas.add(`${metodo.toUpperCase()} /perfiles${ruta.path === '/' ? '' : ruta.path}`);
      }
    }

    const documentadas = new Set<string>();
    for (const [ruta, operaciones] of Object.entries(especificacionOpenApi.paths)) {
      if (!ruta.startsWith('/perfiles')) continue;
      for (const metodo of Object.keys(operaciones as Record<string, unknown>)) {
        documentadas.add(`${metodo.toUpperCase()} ${ruta.replace('{empleadoId}', ':empleadoId')}`);
      }
    }

    assert.deepEqual(
      [...documentadas].sort(),
      [...registradas].sort(),
      'el contrato y el router no coinciden: documenta la ruta nueva o quítala del contrato',
    );
  });

  test('el esquema Perfil declara exactamente los campos que devuelve el mapeador', () => {
    const persistido: PerfilPersistido = {
      id: 'id',
      empleadoId: 'E601',
      nombre: 'Laura',
      apellido: 'Méndez',
      email: 'laura@empresa.com',
      cargo: 'Desarrolladora',
      departamentoId: 'D001',
      fechaIngreso: new Date(),
      fechaRetiro: null,
      estado: 'ACTIVO',
      telefono: null,
      direccion: null,
      ciudad: null,
      biografia: null,
      fechaCreacion: new Date(),
      archivado: false,
      lastEventId: 'evento',
      updatedAt: new Date(),
    };

    assert.deepEqual(
      CAMPOS_DOCUMENTADOS_DEL_PERFIL.sort(),
      Object.keys(presentarPerfil(persistido)).sort(),
      'añade el campo nuevo al esquema Perfil del contrato, o quítalo de presentarPerfil',
    );
  });

  test('el enum de sortBy sale de la lista blanca del repositorio', () => {
    const parametros = (especificacionOpenApi.paths['/perfiles'].get.parameters ??
      []) as readonly any[];
    const sortBy = parametros.find((p) => p.name === 'sortBy');

    assert.ok(sortBy, 'el contrato debe documentar sortBy');
    assert.deepEqual([...sortBy.schema.enum], [...CAMPOS_ORDENABLES]);
  });

  test('se documentan la salud y las métricas', () => {
    assert.ok(rutasDocumentadas.includes('/health'));
    assert.ok(rutasDocumentadas.includes('/metrics'));
  });

  test('las rutas públicas no exigen el token y el resto sí', () => {
    // El JWT lo valida el gateway (ADR-008), pero el contrato tiene que decirlo:
    // un cliente que lea el documento necesita saber qué le van a pedir.
    assert.deepEqual(especificacionOpenApi.security, [{ bearerAuth: [] }]);
    assert.deepEqual(especificacionOpenApi.paths['/health'].get.security, []);
    assert.deepEqual(especificacionOpenApi.paths['/metrics'].get.security, []);
    assert.ok(especificacionOpenApi.components.securitySchemes.bearerAuth);
  });

  test('el alias employeeId queda marcado como obsoleto', () => {
    const perfil = especificacionOpenApi.components.schemas.Perfil.properties as any;

    assert.equal(perfil.employeeId.deprecated, true);
    assert.notEqual(perfil.empleadoId.deprecated, true);
  });

  test('el DELETE se documenta como retiro con 200, no como borrado con 204', () => {
    const borrado = especificacionOpenApi.paths['/perfiles/{empleadoId}'].delete;

    // El perfil nunca se elimina: se marca RETIRADO y se archiva. Documentarlo
    // como 204 haría pensar que el recurso desaparece.
    assert.ok(borrado.responses['200']);
    assert.equal('204' in borrado.responses, false);
  });
});
