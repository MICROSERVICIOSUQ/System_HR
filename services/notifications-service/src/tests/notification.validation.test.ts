import { after, before, describe, test } from 'node:test';
import assert from 'node:assert/strict';
import type { Server } from 'node:http';

describe('notifications-service HTTP contract', () => {
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

  test('valida paginación antes de consultar la base de datos', async () => {
    const invalidPage = await fetch(`${baseUrl}/notifications?page=0&limit=10`);
    const invalidLimit = await fetch(`${baseUrl}/notifications?page=1&limit=101`);

    assert.equal(invalidPage.status, 400);
    assert.equal(invalidLimit.status, 400);
  });

  test('rechaza sortBy y order inválidos antes de consultar la base de datos', async () => {
    const invalidSort = await fetch(`${baseUrl}/notifications?page=1&sortBy=payload`);
    const invalidOrder = await fetch(`${baseUrl}/notifications?page=1&order=SIDEWAYS`);

    assert.equal(invalidSort.status, 400);
    assert.equal(invalidOrder.status, 400);
  });

  test('rechaza mutaciones HTTP porque el historial es inmutable', async () => {
    const response = await fetch(`${baseUrl}/notifications`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({}),
    });

    assert.equal(response.status, 405);
    assert.match((await response.json()).message, /inmutable/);
  });

  test('consulta individual exige messageId', async () => {
    const response = await fetch(`${baseUrl}/notifications/%20`);
    assert.equal(response.status, 400);
  });
});
