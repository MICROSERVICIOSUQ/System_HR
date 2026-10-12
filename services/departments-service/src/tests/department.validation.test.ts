import { after, before, describe, test } from 'node:test';
import assert from 'node:assert/strict';
import type { Server } from 'node:http';

/**
 * Pruebas de la capa HTTP que no requieren base de datos: validación de
 * entrada y rutas no definidas se resuelven antes de tocar Prisma.
 *
 * DATABASE_URL debe existir para que config/db.ts no falle al importarse,
 * pero el pool de pg es perezoso: no se abre ninguna conexión.
 */
describe('POST /departamentos — validación de entrada', () => {
  let server: Server;
  let baseUrl: string;

  before(async () => {
    process.env.DATABASE_URL ??= 'postgresql://placeholder:placeholder@placeholder:5432/placeholder';
    const { default: app } = await import('../app.ts');

    await new Promise<void>((resolve) => {
      server = app.listen(0, () => resolve());
    });
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('No se pudo obtener el puerto del servidor de pruebas');
    }
    baseUrl = `http://127.0.0.1:${address.port}`;
  });

  after(async () => {
    await new Promise<void>((resolve, reject) => {
      server.close((err) => (err ? reject(err) : resolve()));
    });
  });

  test('body inválido responde 400 con el detalle por campo (no 500)', async () => {
    const res = await fetch(`${baseUrl}/departamentos`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ nombre: '' }),
    });
    const body = await res.json();

    assert.equal(res.status, 400);
    assert.equal(body.status, 'error');
    assert.equal(body.message, 'Datos inválidos');
    assert.ok(Array.isArray(body.errors), 'debe incluir el array errors');

    const campos = body.errors.map((e: { field: string }) => e.field).sort();
    assert.deepEqual(campos, ['descripcion', 'nombre']);
  });

  test('body vacío responde 400 listando todos los campos obligatorios', async () => {
    const res = await fetch(`${baseUrl}/departamentos`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{}',
    });
    const body = await res.json();

    assert.equal(res.status, 400);
    assert.deepEqual(
      body.errors.map((e: { field: string }) => e.field).sort(),
      ['descripcion', 'nombre'],
    );
  });

  test('ruta no definida responde 404 descriptivo', async () => {
    const res = await fetch(`${baseUrl}/ruta-invalida`);
    const body = await res.json();

    assert.equal(res.status, 404);
    assert.equal(body.message, 'Ruta no encontrada: GET /ruta-invalida');
  });

  test('PUT y PATCH con body vacío responden 400', async () => {
    for (const method of ['PUT', 'PATCH']) {
      const res = await fetch(`${baseUrl}/departamentos/RRHH`, {
        method,
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({}),
      });
      const body = await res.json();

      assert.equal(res.status, 400);
      assert.equal(body.status, 'error');
      assert.equal(body.message, 'Datos inválidos');
      assert.ok(body.errors.some((error: { message: string }) =>
        error.message.includes('al menos un campo'),
      ));
    }
  });

  test('rechaza paginación y ordenamiento inválidos antes de consultar la base de datos', async () => {
    const invalidLimit = await fetch(`${baseUrl}/departamentos?page=1&limit=101`);
    const invalidSort = await fetch(`${baseUrl}/departamentos?page=1&sortBy=secret`);
    const invalidOrder = await fetch(`${baseUrl}/departamentos?page=1&order=SIDEWAYS`);

    assert.equal(invalidLimit.status, 400);
    assert.equal(invalidSort.status, 400);
    assert.equal(invalidOrder.status, 400);
  });
});
