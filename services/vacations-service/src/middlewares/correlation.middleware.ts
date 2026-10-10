import { randomUUID } from 'node:crypto';
import type { NextFunction, Request, Response } from 'express';

export const correlationMiddleware = (
  req: Request,
  res: Response,
  next: NextFunction
): void => {
  const header = req.headers['x-correlation-id'];
  const providedId = Array.isArray(header) ? header[0] : header;
  const correlationId = providedId?.trim() || randomUUID();

  res.locals.correlationId = correlationId;
  res.setHeader('X-Correlation-ID', correlationId);
  next();
};
