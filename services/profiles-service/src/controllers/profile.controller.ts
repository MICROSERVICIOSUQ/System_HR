import type { Request, Response, NextFunction } from 'express';
import { ProfileService } from '../services/profile.service.ts';

export class ProfileController {
  constructor(private readonly service = new ProfileService()) {}

  getHealth = (_req: Request, res: Response) => {
    return res.json({ status: 'ok', service: 'profiles' });
  };

  /**
   * GET /profiles
   * GET /profiles?estado=ACTIVO
   * GET /profiles?estado=RETIRADO
   */
  getAll = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const estado = Array.isArray(req.query.estado)
        ? String(req.query.estado[0])
        : req.query.estado
        ? String(req.query.estado)
        : undefined;

      const profiles = await this.service.getAll(estado);
      return res.json(profiles);
    } catch (error) {
      return next(error);
    }
  };

  /**
   * GET /profiles/:employeeId
   */
  getByEmployeeId = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const employeeId = Array.isArray(req.params.employeeId)
        ? req.params.employeeId[0]
        : req.params.employeeId;

      const profile = await this.service.getByEmployeeId(employeeId);
      if (!profile) {
        return res.status(404).json({
          status: 404,
          error: 'Not Found',
          message: `Perfil con employeeId '${employeeId}' no encontrado`,
        });
      }

      return res.json(profile);
    } catch (error) {
      return next(error);
    }
  };
}
