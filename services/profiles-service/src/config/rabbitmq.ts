export const rabbitConfig = {
  host: process.env.RABBITMQ_HOST || 'localhost',
  port: parseInt(process.env.RABBITMQ_PORT || '5672', 10),
  user: process.env.RABBITMQ_USER || 'admin',
  password: process.env.RABBITMQ_PASSWORD || 'admin',
  exchangeName: 'hr.events',
  queueName: 'profiles.queue',
  retryQueueName: 'profiles.retry.queue',
  deadLetterQueueName: 'profiles.dlq',
  retryDelayMilliseconds: 5000,
  maxRetries: 3,
  get url(): string {
    return `amqp://${this.user}:${this.password}@${this.host}:${this.port}`;
  },
};
