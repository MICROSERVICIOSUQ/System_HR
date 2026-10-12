export const rabbitConfig = {
  host: process.env.RABBITMQ_HOST || 'localhost',
  port: parseInt(process.env.RABBITMQ_PORT || '5672', 10),
  user: process.env.RABBITMQ_USER || 'admin',
  password: process.env.RABBITMQ_PASSWORD || 'admin',
  queueName: 'vacations.queue',
  get url(): string {
    return `amqp://${this.user}:${this.password}@${this.host}:${this.port}`;
  },
};
