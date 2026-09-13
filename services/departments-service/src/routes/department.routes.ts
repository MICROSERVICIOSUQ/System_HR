import { Router } from 'express';
import { DepartmentController } from '../controllers/department.controller.ts';
import { createDepartmentSchema, departmentIdSchema, updateDepartmentSchema } from '../schemas/department.schema.ts';
import { validateBody, validateParams } from '../utils/http.ts';

const router = Router();
const controller = new DepartmentController();

router.get('/', controller.getAll);
router.get('/:id', validateParams(departmentIdSchema), controller.getById);
router.post('/', validateBody(createDepartmentSchema), controller.create);
router.put('/:id', validateParams(departmentIdSchema), validateBody(updateDepartmentSchema), controller.update);
router.delete('/:id', validateParams(departmentIdSchema), controller.remove);

export default router;
