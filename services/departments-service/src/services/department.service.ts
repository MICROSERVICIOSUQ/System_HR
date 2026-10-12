import { DepartmentRepository } from '../repositories/department.repository.ts';
import { EmployeeClient } from '../clients/employee.client.ts';
import { AppError } from '../middleware/error.middleware.ts';

export class DepartmentService {
  constructor(
    private readonly repository = new DepartmentRepository(),
    private readonly employeeClient = new EmployeeClient(),
  ) {}

  async getAll(pagination?: {
    page: number;
    limit: number;
    sortBy: 'id' | 'nombre' | 'descripcion' | 'createdAt' | 'updatedAt';
    order: 'asc' | 'desc';
  }) {
    const result = await this.repository.findAll(pagination);
    if (!pagination) return result;

    const totalPages = Math.ceil(result.total / pagination.limit);
    return {
      data: result.data,
      meta: {
        total: result.total,
        page: pagination.page,
        limit: pagination.limit,
        totalPages,
      },
    };
  }

  async getById(id: string) {
    return this.repository.findById(id);
  }

  async create(data: { id?: string; nombre: string; descripcion: string }) {
    return this.repository.create({
      ...(data.id !== undefined && { id: data.id }),
      nombre: data.nombre,
      descripcion: data.descripcion,
    });
  }

  async update(id: string, data: { nombre?: string; descripcion?: string }) {
    return this.repository.update(id, {
      ...(data.nombre !== undefined && { nombre: data.nombre }),
      ...(data.descripcion !== undefined && { descripcion: data.descripcion }),
    });
  }

  async delete(id: string) {
    if (await this.employeeClient.hasEmployeesForDepartment(id)) {
      throw new AppError(
        409,
        `No se puede eliminar el departamento ${id} porque tiene empleados asociados`,
      );
    }

    return this.repository.remove(id);
  }
}
