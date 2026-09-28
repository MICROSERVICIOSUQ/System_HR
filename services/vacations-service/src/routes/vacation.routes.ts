import { Router } from 'express';
import { VacationController } from '../controllers/vacation.controller.ts';

const router = Router();
const controller = new VacationController();

// GET /vacations                  → Lista completa
// GET /vacations?estado=ACTIVO    → Solo empleados activos
// GET /vacations?estado=RETIRADO  → Solo empleados retirados
router.get('/', controller.getAll);

// GET /vacations/:employeeId      → Buscar saldo de un empleado por ID
router.get('/:employeeId', controller.getByEmployeeId);

export default router;
