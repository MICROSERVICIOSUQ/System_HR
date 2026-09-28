import type { Request, Response, NextFunction } from 'express';
import { NotificationService } from '../services/notification.service.ts';

export class NotificationController {
  constructor(private readonly service = new NotificationService()) {}

  getHealth = (_req: Request, res: Response) => {
    return res.json({ status: 'ok', service: 'notifications' });
  };

  getAll = async (_req: Request, res: Response, next: NextFunction) => {
    try {
      const history = await this.service.getAll();
      return res.json(history);
    } catch (error) {
      return next(error);
    }
  };

  getByMessageId = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const messageId = Array.isArray(req.params.messageId)
        ? req.params.messageId[0]
        : req.params.messageId;

      const record = await this.service.getByMessageId(messageId);
      if (!record) {
        return res.status(404).json({
          status: 404,
          error: 'Not Found',
          message: `Notificación con messageId '${messageId}' no encontrada`,
        });
      }

      return res.json(record);
    } catch (error) {
      return next(error);
    }
  };
}
