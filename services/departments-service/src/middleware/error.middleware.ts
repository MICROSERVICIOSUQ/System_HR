import type { NextFunction, Request, Response } from 'express';
import { DatabaseUnavailableError } from '../utils/database-retry.ts';

export class AppError extends Error {
  constructor(
    public statusCode: number,
    public message: string,
  ) {
    super(message);
    this.name = 'AppError';
  }
}

export const notFoundHandler = (req: Request, res: Response) => {
  res.status(404).json({
    status: 'error',
    message: `Ruta no encontrada: ${req.method} ${req.originalUrl}`,
  });
};

export const errorHandler = (
  err: Error,
  _req: Request,
  res: Response,
  _next: NextFunction,
) => {
  console.error(err);

  if (err instanceof AppError) {
    return res.status(err.statusCode).json({
      status: 'error',
      message: err.message,
    });
  }

  if (err instanceof DatabaseUnavailableError) {
    return res.status(503).json({
      status: 'error',
      message: err.message,
    });
  }

  if (err.name === 'PrismaClientValidationError') {
    return res.status(400).json({
      status: 'error',
      message: 'Datos inválidos para la operación en base de datos.',
    });
  }

  if (err.name === 'PrismaClientKnownRequestError') {
    return res.status(400).json({
      status: 'error',
      message: 'Error de validación o persistencia en la base de datos.',
    });
  }

  return res.status(500).json({
    status: 'error',
    message: 'Error interno del servidor.',
  });
};
