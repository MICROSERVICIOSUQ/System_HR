import prisma from '../config/db.ts';

export interface CreateNotificationDto {
  messageId: string;
  eventType: string;
  producer: string;
  employeeId: string;
  employeeName: string;
  email: string;
  payload: any;
}

export class NotificationRepository {
  async findByMessageId(messageId: string) {
    return prisma.notificationHistory.findUnique({
      where: { messageId },
    });
  }

  async findAll() {
    return prisma.notificationHistory.findMany({
      orderBy: { processedAt: 'desc' },
    });
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
