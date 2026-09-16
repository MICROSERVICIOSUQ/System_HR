import type { NextFunction, Request, Response } from 'express';
import { z } from 'zod';
import { AppError, type FieldError } from '../middleware/error.middleware.ts';

const toFieldErrors = (error: z.ZodError): FieldError[] =>
  error.issues.map((issue) => ({
    field: issue.path.join('.') || '(body)',
    message: issue.message,
  }));

export const validateBody = <T>(schema: z.ZodSchema<T>) => {
  return (req: Request, _res: Response, next: NextFunction) => {
    const result = schema.safeParse(req.body);

    if (!result.success) {
      return next(new AppError(400, 'Datos inválidos', toFieldErrors(result.error)));
    }

    req.body = result.data;
    return next();
  };
};

export const validateParams = <T>(schema: z.ZodSchema<T>) => {
  return (req: Request, _res: Response, next: NextFunction) => {
    const result = schema.safeParse(req.params);

    if (!result.success) {
      return next(new AppError(400, 'Parámetros inválidos', toFieldErrors(result.error)));
    }

    req.params = result.data as typeof req.params;
    return next();
  };
};
