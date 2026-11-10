import { after, before, describe, test } from 'node:test';
import assert from 'node:assert/strict';
import type { Server } from 'node:http';

describe('profiles-service HTTP validation', () => {
  let server: Server;
  let baseUrl: string;

  before(async () => {
    process.env.DATABASE_URL ??= 'postgresql://placeholder:5432/placeholder';
    // La anotación explícita hace falta porque el import dinámico de un módulo
    // con export default devuelve el namespace, y TypeScript no infiere de ahí
    // el tipo Express. Era otro de los errores de tipo que el servicio
    // arrastraba sin que nadie los viera, porque tsx no comprueba tipos.
    const { default: app } = (await import('../app.ts')) as unknown as {
      default: import('express').Express;
    };
    await new Promise<void>((resolve) => {
      server = app.listen(0, () => resolve());
    });
    const address = server.address();
    if (!address || typeof address === 'string') throw new Error('No se obtuvo el puerto');
    baseUrl = `http://127.0.0.1:${address.port}`;
  });

  after(async () => {
    await new Promise<void>((resolve, reject) => {
      server.close((error) => (error ? reject(error) : resolve()));
    });
  });

  test('rechaza creación sin campos obligatorios', async () => {
    const response = await fetch(`${baseUrl}/profiles`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ empleadoId: 'EMP-TEST' }),
    });
    const body = await response.json();

    assert.equal(response.status, 400);
    assert.match(body.message, /obligatorio/);
  });

  test('rechaza estados y paginación inválidos', async () => {
    const invalidState = await fetch(`${baseUrl}/profiles?estado=PAUSADO`);
    assert.equal(invalidState.status, 400);

    const invalidPage = await fetch(`${baseUrl}/profiles?page=0&limit=10`);
    assert.equal(invalidPage.status, 400);
  });

  test('rechaza ordenamiento fuera de la lista blanca', async () => {
    const invalidSort = await fetch(`${baseUrl}/profiles?page=1&sortBy=password`);
    const invalidOrder = await fetch(`${baseUrl}/profiles?page=1&order=SIDEWAYS`);

    assert.equal(invalidSort.status, 400);
    assert.equal(invalidOrder.status, 400);
  });

  test('PUT ignora los campos administrativos y no modifica ningún dato', async () => {
    const response = await fetch(`${baseUrl}/perfiles/EMP-TEST`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        archivado: true,
        cargo: 'Gerente',
        estado: 'RETIRADO',
        departamentoId: 'D999',
        empleadoId: 'EMP-OTRO',
      }),
    });
    const body = await response.json();

    // Al no incluir ningún campo editable, el filtro entrega una actualización
    // vacía y el servicio la rechaza antes de consultar o escribir en Prisma.
    assert.equal(response.status, 400);
    assert.match(body.message, /al menos un campo/);
    assert.equal('archivado' in body, false);
    assert.equal('cargo' in body, false);
  });

  test('rechaza modificar otro perfil según X-Auth-User', async () => {
    const response = await fetch(`${baseUrl}/perfiles/EMP-TEST`, {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json',
        'X-Auth-User': 'EMP-OTRO',
        'X-Auth-Role': 'USER',
      },
      body: JSON.stringify({ telefono: '+57 300 000 0000' }),
    });

    assert.equal(response.status, 403);
  });
});
