import type { NextFunction, Request, Response } from 'express';
import { AppError } from '../middleware/error.middleware.ts';
import { DepartmentService } from '../services/department.service.ts';

export class DepartmentController {
  constructor(private readonly service = new DepartmentService()) {}

  getAll = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const hasPagination = ['page', 'limit', 'sortBy', 'order'].some(
        (key) => req.query[key] !== undefined,
      );
      const pagination = hasPagination
        ? {
            page: this.parsePositiveInteger(req.query.page, 'page', 1),
            limit: this.parseLimit(req.query.limit),
            sortBy: this.parseSortBy(req.query.sortBy),
            order: this.parseOrder(req.query.order),
          }
        : undefined;
      const departamentos = await this.service.getAll(pagination);
      return res.json(departamentos);
    } catch (error) {
      return next(error);
    }
  };

  private parsePositiveInteger(value: unknown, name: string, defaultValue: number): number {
    if (value === undefined) return defaultValue;
    const parsed = Number(Array.isArray(value) ? value[0] : value);
    if (!Number.isSafeInteger(parsed) || parsed < 1) {
      throw new AppError(400, `${name} debe ser un entero positivo`);
    }
    return parsed;
  }

  private parseLimit(value: unknown): number {
    const limit = this.parsePositiveInteger(value, 'limit', 10);
    if (limit > 100) throw new AppError(400, 'limit no puede ser mayor que 100');
    return limit;
  }

  private parseSortBy(value: unknown): 'id' | 'nombre' | 'descripcion' | 'createdAt' | 'updatedAt' {
    const selected = Array.isArray(value) ? value[0] : value;
    const allowed = ['id', 'nombre', 'descripcion', 'createdAt', 'updatedAt'] as const;
    if (selected === undefined) return 'createdAt';
    if (!allowed.includes(String(selected) as typeof allowed[number])) {
      throw new AppError(400, `sortBy no permitido. Valores: ${allowed.join(', ')}`);
    }
    return String(selected) as typeof allowed[number];
  }

  private parseOrder(value: unknown): 'asc' | 'desc' {
    const selected = String(Array.isArray(value) ? value[0] : value ?? 'DESC').toUpperCase();
    if (selected !== 'ASC' && selected !== 'DESC') {
      throw new AppError(400, 'order debe ser ASC o DESC');
    }
    return selected.toLowerCase() as 'asc' | 'desc';
  }

  getById = async (req: Request, res: Response, next: NextFunction) => {
    const id = Array.isArray(req.params.id) ? req.params.id[0] : req.params.id;

    try {
      const departamento = await this.service.getById(id);

      if (!departamento) {
        throw new AppError(404, `El departamento con id ${id} no existe`);
      }

      return res.json(departamento);
    } catch (error) {
      return next(error);
    }
  };

  create = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const departamento = await this.service.create(req.body);
      return res.status(201).json(departamento);
    } catch (error) {
      return next(error);
    }
  };

  update = async (req: Request, res: Response, next: NextFunction) => {
    const id = Array.isArray(req.params.id) ? req.params.id[0] : req.params.id;

    try {
      const departamento = await this.service.update(id, req.body);
      return res.json(departamento);
    } catch (error) {
      return next(error);
    }
  };

  remove = async (req: Request, res: Response, next: NextFunction) => {
    const id = Array.isArray(req.params.id) ? req.params.id[0] : req.params.id;

    try {
      await this.service.delete(id);
      return res.status(204).send();
    } catch (error) {
      return next(error);
    }
  };
}
