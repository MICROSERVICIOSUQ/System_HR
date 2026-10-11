import prisma from '../config/db.ts';

export interface CreateNotificationDto {
  messageId: string;
  eventType: string;
  producer: string;
  employeeId: string;
  employeeName: string;
  email: string;
  payload: Record<string, unknown>;
}

export class NotificationRepository {
  async findByMessageId(messageId: string) {
    return prisma.notificationHistory.findUnique({
      where: { messageId },
    });
  }

  async findAll(pagination?: {
    page: number;
    limit: number;
    sortBy: 'messageId' | 'eventType' | 'producer' | 'employeeId' | 'employeeName' | 'email' | 'processedAt';
    order: 'asc' | 'desc';
  }) {
    if (!pagination) {
      return prisma.notificationHistory.findMany({
        orderBy: { processedAt: 'desc' },
      });
    }

    return prisma.$transaction([
      prisma.notificationHistory.count(),
      prisma.notificationHistory.findMany({
        skip: (pagination.page - 1) * pagination.limit,
        take: pagination.limit,
        orderBy: { [pagination.sortBy]: pagination.order },
      }),
    ]).then(([total, data]) => ({ total, data }));
  }

  async create(data: CreateNotificationDto) {
    return prisma.notificationHistory.create({
      data: {
        messageId: data.messageId,
        eventType: data.eventType,
        producer: data.producer,
        employeeId: data.employeeId,
        employeeName: data.employeeName,
        email: data.email,
        payload: data.payload,
      },
    });
  }
}
