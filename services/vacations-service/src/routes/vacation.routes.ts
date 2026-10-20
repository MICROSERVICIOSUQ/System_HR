import { Router } from 'express';
import { VacationController } from '../controllers/vacation.controller.ts';

const router = Router();
const controller = new VacationController();

// ── Períodos de vacaciones (Productor / Write Model) ──────────────────────────
// POST  /vacations                       → Programar período (+ publica vacaciones.programadas)
// GET   /vacations                       → Listar todos los períodos
// GET   /vacations?empleadoId=EMP-001    → Filtrar períodos por empleado
// GET   /vacations/:id                   → Obtener período por UUID
// DELETE /vacaciones/:id (alias /vacations/:id) → Cancelar período futuro por UUID

router.post('/', controller.createPeriod);
router.get('/', controller.getAllPeriods);

// ── Endpoints de DESARROLLO para demostrar el scheduler sin esperar fechas ────
// Se declaran antes de /:id para que no los capture esa ruta.
// Ambos exigen rol ADMIN; la comprobación la hace el api-gateway.
router.post('/:id/forzar-inicio', controller.forcePeriodStart);
router.post('/:id/forzar-fin', controller.forcePeriodFinish);

router.put('/:id', controller.updatePeriod);
router.patch('/:id', controller.updatePeriod);
router.get('/:id', controller.getPeriodById);
router.delete('/:id', controller.deletePeriod);

// ── Saldos de vacaciones (CQRS Read Model desde eventos RabbitMQ) ─────────────
// GET   /vacations/balances              → Listar saldos
// GET   /vacations/balances?estado=ACTIVO
// GET   /vacations/balances/:employeeId  → Saldo de un empleado
// Nota: /balances debe declararse ANTES de /:id para evitar conflicto de rutas

export default router;
