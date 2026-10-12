import { after, before, describe, test } from 'node:test';
import assert from 'node:assert/strict';
import type { Server } from 'node:http';

describe('profiles-service HTTP validation', () => {
  let server: Server;
  let baseUrl: string;

  before(async () => {
    process.env.DATABASE_URL ??= 'postgresql://placeholder:5432/placeholder';
    const { default: app } = await import('../app.ts');
    await new Promise<void>((resolve) => {
      server = app.listen(0, resolve);
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
      body: JSON.stringify({ employeeId: 'EMP-TEST' }),
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
});
