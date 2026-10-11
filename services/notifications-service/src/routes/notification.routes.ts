import { Router } from 'express';
import { NotificationController } from '../controllers/notification.controller.ts';

const router = Router();
const controller = new NotificationController();

router.get('/', controller.getAll);
router.get('/:messageId', controller.getByMessageId);

const immutableHistoryResponse = (_req: import('express').Request, res: import('express').Response) =>
  res.status(405).json({
    status: 405,
    error: 'Method Not Allowed',
    message: 'NotificationHistory es un historial inmutable; solo admite consultas HTTP',
  });

router.post('/', immutableHistoryResponse);
router.put('/:messageId', immutableHistoryResponse);
router.patch('/:messageId', immutableHistoryResponse);
router.delete('/:messageId', immutableHistoryResponse);

export default router;
