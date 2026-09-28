import { Router } from 'express';
import { NotificationController } from '../controllers/notification.controller.ts';

const router = Router();
const controller = new NotificationController();

router.get('/', controller.getAll);
router.get('/:messageId', controller.getByMessageId);

export default router;
