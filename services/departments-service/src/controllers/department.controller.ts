import type { NextFunction, Request, Response } from 'express';
import { AppError } from '../middleware/error.middleware.ts';
import { DepartmentService } from '../services/department.service.ts';

export class DepartmentController {
  constructor(private readonly service = new DepartmentService()) {}

  getAll = async (_req: Request, res: Response, next: NextFunction) => {
    try {
      const departamentos = await this.service.getAll();
      return res.json(departamentos);
    } catch (error) {
      return next(error);
    }
  };

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
