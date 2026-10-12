import type { Request, Response, NextFunction } from 'express';
import { NotificationService } from '../services/notification.service.ts';
import { NotificationError } from '../errors/notification.errors.ts';

export class NotificationController {
  constructor(private readonly service = new NotificationService()) {}

  getHealth = (_req: Request, res: Response) => {
    return res.json({ status: 'ok', service: 'notifications' });
  };

  getAll = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const hasPagination = ['page', 'limit', 'size', 'sortBy', 'order'].some(
        (key) => req.query[key] !== undefined,
      );
      const pagination = hasPagination
        ? {
            page: this.parsePositiveInteger(req.query.page, 'page', 1),
            limit: this.parseLimit(req.query.limit ?? req.query.size),
            sortBy: this.parseSortBy(req.query.sortBy),
            order: this.parseOrder(req.query.order),
          }
        : undefined;

      const history = await this.service.getAll(pagination);
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

  private parsePositiveInteger(
    value: unknown,
    name: string,
    defaultValue: number,
  ): number {
    if (value === undefined) return defaultValue;
    const parsed = Number(value);
    if (!Number.isInteger(parsed) || parsed < 1) {
      throw new NotificationError(400, `${name} debe ser un entero positivo`);
    }
    return parsed;
  }

  private parseLimit(value: unknown): number {
    const limit = this.parsePositiveInteger(value, 'limit', 10);
    if (limit > 100) throw new NotificationError(400, 'limit no puede ser mayor que 100');
    return limit;
  }

  private parseSortBy(value: unknown): 'messageId' | 'eventType' | 'producer' | 'employeeId' | 'employeeName' | 'email' | 'processedAt' {
    const selected = Array.isArray(value) ? value[0] : value;
    const allowed = ['messageId', 'eventType', 'producer', 'employeeId', 'employeeName', 'email', 'processedAt'] as const;
    if (selected === undefined) return 'processedAt';
    if (!allowed.includes(String(selected) as typeof allowed[number])) {
      throw new NotificationError(400, `sortBy no permitido. Valores: ${allowed.join(', ')}`);
    }
    return String(selected) as typeof allowed[number];
  }

  private parseOrder(value: unknown): 'asc' | 'desc' {
    const selected = String(Array.isArray(value) ? value[0] : value ?? 'DESC').toUpperCase();
    if (selected !== 'ASC' && selected !== 'DESC') {
      throw new NotificationError(400, 'order debe ser ASC o DESC');
    }
    return selected.toLowerCase() as 'asc' | 'desc';
  }
}
