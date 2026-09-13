import prisma from '../config/db.ts';
import type { Prisma } from '../generated/prisma/client.ts';
import { withDatabaseRetry } from '../utils/database-retry.ts';

export class DepartmentRepository {
  async findAll() {
    return withDatabaseRetry(() =>
      prisma.departamento.findMany({
        orderBy: { createdAt: 'desc' },
      }),
    );
  }

  async findById(id: string) {
    return withDatabaseRetry(() =>
      prisma.departamento.findUnique({
        where: { id },
      }),
    );
  }

  async create(data: Partial<Prisma.DepartamentoCreateInput> & { nombre: string; descripcion: string }) {
    let id = data.id;

    if (!id) {
      const [generated] = await withDatabaseRetry(() =>
        prisma.$queryRaw<{ id: string }[]>`
          SELECT 'D' || LPAD(nextval('departments_id_seq')::text, 3, '0') AS id
        `,
      );
      id = generated.id;
    }

    return withDatabaseRetry(() =>
      prisma.departamento.create({
        data: { ...data, id },
      }),
    );
  }

  async update(id: string, data: Prisma.DepartamentoUpdateInput) {
    return withDatabaseRetry(() =>
      prisma.departamento.update({
        where: { id },
        data,
      }),
    );
  }

  async remove(id: string) {
    return withDatabaseRetry(() =>
      prisma.departamento.delete({
        where: { id },
      }),
    );
  }
}
