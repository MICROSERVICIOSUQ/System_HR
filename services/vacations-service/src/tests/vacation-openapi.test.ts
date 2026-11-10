import { after, before, describe, test } from 'node:test';
import assert from 'node:assert/strict';
import type { Server } from 'node:http';

describe('OpenAPI de vacations-service', () => {
  let server: Server;
  let baseUrl: string;

  before(async () => {
    process.env.DATABASE_URL ??= 'postgresql://placeholder:5432/placeholder';
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

  test('POST /vacaciones documenta 201 como única respuesta exitosa', async () => {
    const response = await fetch(`${baseUrl}/openapi.json`);
    assert.equal(response.status, 200);

    const contract = await response.json() as {
      paths: Record<string, { post?: { responses: Record<string, unknown> } }>;
    };
    const responses = contract.paths['/vacaciones']?.post?.responses;

    assert.ok(responses);
    assert.ok('201' in responses);
    assert.equal('200' in responses, false);
  });
});
