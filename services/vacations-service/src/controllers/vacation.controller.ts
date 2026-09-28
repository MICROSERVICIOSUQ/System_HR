import type { Request, Response, NextFunction } from 'express';
import { VacationService } from '../services/vacation.service.ts';

export class VacationController {
  constructor(private readonly service = new VacationService()) {}

  getHealth = (_req: Request, res: Response) => {
    return res.json({ status: 'ok', service: 'vacations' });
  };

  /**
   * GET /vacations
   * GET /vacations?estado=ACTIVO
   * GET /vacations?estado=RETIRADO
   */
  getAll = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const estado = Array.isArray(req.query.estado)
        ? String(req.query.estado[0])
        : req.query.estado
        ? String(req.query.estado)
        : undefined;

      const balances = await this.service.getAll(estado);
      return res.json(balances);
    } catch (error) {
      return next(error);
    }
  };

  /**
   * GET /vacations/:employeeId
   */
  getByEmployeeId = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const employeeId = Array.isArray(req.params.employeeId)
        ? req.params.employeeId[0]
        : req.params.employeeId;

      const balance = await this.service.getByEmployeeId(employeeId);
      if (!balance) {
        return res.status(404).json({
          status: 404,
          error: 'Not Found',
          message: `Saldo de vacaciones para employeeId '${employeeId}' no encontrado`,
        });
      }

      return res.json(balance);
    } catch (error) {
      return next(error);
    }
  };
}
