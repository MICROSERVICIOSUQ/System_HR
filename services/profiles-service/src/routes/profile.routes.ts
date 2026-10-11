import { Router } from 'express';
import { ProfileController } from '../controllers/profile.controller.ts';

const router = Router();
const controller = new ProfileController();

// GET /profiles          → Todos los perfiles (sin filtro)
// GET /profiles?estado=ACTIVO    → Solo activos
// GET /profiles?estado=RETIRADO  → Solo retirados
router.get('/', controller.getAll);
router.post('/', controller.create);
router.put('/:employeeId', controller.update);
router.patch('/:employeeId', controller.update);
router.delete('/:employeeId', controller.retire);

// GET /profiles/:employeeId
router.get('/:employeeId', controller.getByEmployeeId);

export default router;
