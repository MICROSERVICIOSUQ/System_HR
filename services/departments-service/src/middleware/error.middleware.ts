import type { NextFunction, Request, Response } from 'express';
import { DatabaseUnavailableError } from '../utils/database-retry.ts';
import { EmployeeServiceUnavailableError } from '../clients/employee.client.ts';

/** Detalle de un campo que no superó la validación. */
export interface FieldError {
  field: string;
  message: string;
}

/**
 * Error de dominio con código HTTP explícito. Es el único tipo que el
 * errorHandler traduce a su status: todo lo que no sea AppError, error de
 * base de datos o error conocido de Prisma se responde como 500.
 */
export class AppError extends Error {
  constructor(
    public statusCode: number,
    public message: string,
    public errors?: FieldError[],
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
  req: Request,
  res: Response,
  _next: NextFunction,
) => {
  if (err instanceof AppError) {
    return res.status(err.statusCode).json({
      status: 'error',
      message: err.message,
      ...(err.errors && { errors: err.errors }),
    });
  }

  // De aquí en adelante son fallos no previstos: sí conviene dejar rastro.
  console.error(err);

  if (err instanceof DatabaseUnavailableError) {
    return res.status(503).json({
      status: 'error',
      message: err.message,
    });
  }

  if (err instanceof EmployeeServiceUnavailableError) {
    return res.status(503).json({
      status: 'error',
      message: err.message,
    });
  }

  if (err.name === 'PrismaClientKnownRequestError') {
    const code = (err as Error & { code?: string }).code;

    if (code === 'P2002') {
      return res.status(409).json({
        status: 'error',
        message: 'Ya existe un departamento con ese identificador.',
      });
    }

    if (code === 'P2025') {
      const id = Array.isArray(req.params.id) ? req.params.id[0] : req.params.id;
      return res.status(404).json({
        status: 'error',
        message: `El departamento con id ${id} no existe`,
      });
    }

    if (code === 'P2003') {
      return res.status(409).json({
        status: 'error',
        message: 'No se puede eliminar el departamento porque tiene empleados asociados.',
      });
    }
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
