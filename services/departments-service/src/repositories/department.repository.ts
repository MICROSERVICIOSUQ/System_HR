import prisma from '../config/db.ts';
import type { Prisma } from '../generated/prisma/client.ts';
import { withDatabaseRetry } from '../utils/database-retry.ts';

export class DepartmentRepository {
  async findAll(pagination?: {
    page: number;
    limit: number;
    sortBy: 'id' | 'nombre' | 'descripcion' | 'createdAt' | 'updatedAt';
    order: 'asc' | 'desc';
  }) {
    return withDatabaseRetry(() => {
      if (!pagination) {
        return prisma.departamento.findMany({
          orderBy: { createdAt: 'desc' },
        });
      }

      const orderBy = { [pagination.sortBy]: pagination.order };
      return prisma.$transaction([
        prisma.departamento.count(),
        prisma.departamento.findMany({
          skip: (pagination.page - 1) * pagination.limit,
          take: pagination.limit,
          orderBy,
        }),
      ]).then(([total, data]) => ({ total, data }));
    });
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
