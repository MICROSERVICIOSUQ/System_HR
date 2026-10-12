import type { Request, RequestHandler, Response } from 'express';
import prisma from '../config/db.ts';
import { Prisma } from '../generated/prisma/client.ts';

const toJsonInput = (body: unknown): Prisma.InputJsonValue | typeof Prisma.JsonNull => {
  if (body === null || body === undefined) return Prisma.JsonNull;
  if (Buffer.isBuffer(body)) return body.toString('utf8');

  const serialized = JSON.stringify(body);
  if (serialized === undefined) {
    throw new Error('La respuesta no puede serializarse para idempotencia');
  }
  return JSON.parse(serialized) as Prisma.InputJsonValue;
};

const getIdempotencyKey = (req: Request): string | undefined => {
  const header = req.headers['idempotency-key'];
  const value = Array.isArray(header) ? header[0] : header;
  const key = value?.trim();
  return key || undefined;
};

const isMutation = (method: string): boolean =>
  method === 'POST' || method === 'PUT' || method === 'PATCH';

export const idempotencyMiddleware: RequestHandler = (req, res, next) => {
  const key = getIdempotencyKey(req);
  if (!key || !isMutation(req.method)) {
    next();
    return;
  }

  void prisma
    .$transaction(async (transaction) => {
      await transaction.$queryRaw`
        SELECT pg_advisory_xact_lock(hashtextextended(${key}, 0))
      `;

      const cached = await transaction.idempotencyRecord.findUnique({
        where: { key },
      });
      if (cached) {
        res.setHeader('X-Cache-Lookup', 'HIT');
        res.status(cached.statusCode).json(cached.responseBody);
        return;
      }

      await new Promise<void>((resolve, reject) => {
        const originalJson = res.json.bind(res);
        const originalSend = res.send.bind(res);
        let responseStarted = false;
        let settled = false;

        const finish = () => {
          if (settled) return;
          settled = true;
          resolve();
        };

        const sendWithRecord = (
          body: unknown,
          sendResponse: (body: unknown) => Response
        ): Response => {
          if (responseStarted) return sendResponse(body);
          responseStarted = true;

          if (res.statusCode < 200 || res.statusCode >= 300) {
            return sendResponse(body);
          }

          void transaction.idempotencyRecord
            .create({
              data: {
                key,
                method: req.method,
                path: req.originalUrl,
                statusCode: res.statusCode,
                responseBody: toJsonInput(body),
              },
            })
            .then(() => sendResponse(body))
            .catch(reject);

          return res;
        };

        res.json = ((body: unknown) =>
          sendWithRecord(body, (value) => originalJson(value))) as Response['json'];

        res.send = ((body?: unknown) => {
          if (responseStarted) return originalSend(body);
          if (body !== null && typeof body === 'object' && !Buffer.isBuffer(body)) {
            return res.json(body);
          }
          return sendWithRecord(body, (value) => originalSend(value));
        }) as Response['send'];

        res.once('finish', finish);
        res.once('close', finish);
        next();
      });
    })
    .catch(next);
};
