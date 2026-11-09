import { Router } from 'express';
import { ProfileController } from '../controllers/profile.controller.ts';

const router = Router();
const controller = new ProfileController();

// GET /profiles          → Todos los perfiles (sin filtro)
// GET /profiles?estado=ACTIVO    → Solo activos
// GET /profiles?estado=RETIRADO  → Solo retirados
router.get('/', controller.getAll);
router.post('/', controller.create);
router.put('/:empleadoId', controller.update);
router.patch('/:empleadoId', controller.update);
router.delete('/:empleadoId', controller.retire);

// GET /profiles/:empleadoId
router.get('/:empleadoId', controller.getByEmpleadoId);

export default router;
