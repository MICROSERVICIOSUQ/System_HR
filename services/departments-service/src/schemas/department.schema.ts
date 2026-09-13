import { z } from 'zod';

export const departmentIdSchema = z.object({
  id: z.string().min(1, 'El identificador es obligatorio'),
});

export const createDepartmentSchema = z.object({
  nombre: z.string().min(1, 'El nombre es obligatorio'),
  descripcion: z.string().min(1, 'La descripcion es obligatoria'),
});

export const updateDepartmentSchema = z.object({
  nombre: z.string().min(1, 'El nombre es obligatorio').optional(),
  descripcion: z.string().min(1, 'La descripcion no puede estar vacia').optional(),
});
