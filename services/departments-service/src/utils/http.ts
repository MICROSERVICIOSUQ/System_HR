import type { NextFunction, Request, Response } from 'express';
import { z } from 'zod';

export const validateBody = <T>(schema: z.ZodSchema<T>) => {
  return (req: Request, _res: Response, next: NextFunction) => {
    const result = schema.safeParse(req.body);

    if (!result.success) {
      const error = new Error('Datos inválidos');
      (error as Error & { statusCode?: number; details?: unknown }).statusCode = 400;
      (error as Error & { details?: unknown }).details = result.error.issues;
      return next(error);
    }

    req.body = result.data;
    return next();
  };
};

export const validateParams = <T>(schema: z.ZodSchema<T>) => {
  return (req: Request, _res: Response, next: NextFunction) => {
    const result = schema.safeParse(req.params);

    if (!result.success) {
      const error = new Error('Parámetros inválidos');
      (error as Error & { statusCode?: number; details?: unknown }).statusCode = 400;
      (error as Error & { details?: unknown }).details = result.error.issues;
      return next(error);
    }

    req.params = result.data as typeof req.params;
    return next();
  };
};
