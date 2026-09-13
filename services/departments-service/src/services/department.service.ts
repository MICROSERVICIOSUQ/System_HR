import { DepartmentRepository } from '../repositories/department.repository.ts';

export class DepartmentService {
  constructor(private readonly repository = new DepartmentRepository()) {}

  async getAll() {
    return this.repository.findAll();
  }

  async getById(id: string) {
    return this.repository.findById(id);
  }

  async create(data: { nombre: string; descripcion: string }) {
    return this.repository.create({
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
    return this.repository.remove(id);
  }
}
