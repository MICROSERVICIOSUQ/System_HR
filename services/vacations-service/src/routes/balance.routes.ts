import { Router } from 'express';
import { VacationController } from '../controllers/vacation.controller.ts';

const balanceRouter = Router();
const controller = new VacationController();

// GET /vacations/balances                  → Lista completa de saldos
// GET /vacations/balances?estado=ACTIVO    → Solo empleados activos
// GET /vacations/balances?estado=RETIRADO  → Solo empleados retirados
balanceRouter.get('/', controller.getAllBalances);

// GET /vacations/balances/:employeeId  → Saldo de un empleado específico
balanceRouter.get('/:employeeId', controller.getBalanceByEmployeeId);

export default balanceRouter;
