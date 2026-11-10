import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { ConfirmChannel, ConsumeMessage } from 'amqplib';

import { RabbitMQConsumer } from '../messaging/rabbitmq.consumer.ts';
import { rabbitConfig } from '../config/rabbitmq.ts';
import type { ProfileService } from '../services/profile.service.ts';

function makeConsumer(channel: ConfirmChannel) {
  const service = {
    processEvent: async () => {
      throw new Error('temporary database outage');
    },
  } as unknown as ProfileService;
  const consumer = new RabbitMQConsumer(service);
  const internals = consumer as unknown as {
    channel: ConfirmChannel;
    handleMessage(message: ConsumeMessage): Promise<void>;
  };
  internals.channel = channel;
  return internals;
}

function makeMessage(retries = 0): ConsumeMessage {
  return {
    content: Buffer.from(JSON.stringify({ id: 'event-1', type: 'empleado.retirado' })),
    properties: {
      headers: { 'x-profile-retry-attempt': retries },
      messageId: 'event-1',
    },
  } as unknown as ConsumeMessage;
}

function makeChannel(confirmError?: Error) {
  const sent: { queue: string; options: { headers?: Record<string, unknown> } }[] = [];
  let acked = false;
  let nackedWithRequeue = false;
  const channel = {
    sendToQueue(queue: string, _content: Buffer, options: { headers?: Record<string, unknown> }) {
      sent.push({ queue, options });
      return true;
    },
    async waitForConfirms() {
      if (confirmError) throw confirmError;
    },
    ack() {
      acked = true;
    },
    nack(_message: ConsumeMessage, _allUpTo: boolean, requeue: boolean) {
      nackedWithRequeue = requeue;
    },
  } as unknown as ConfirmChannel;

  return {
    channel,
    sent,
    wasAcked: () => acked,
    wasNackedWithRequeue: () => nackedWithRequeue,
  };
}

test('reintenta el evento al confirmar su publicación en la cola de retry', async () => {
  const mock = makeChannel();
  const consumer = makeConsumer(mock.channel);

  await consumer.handleMessage(makeMessage());

  assert.equal(mock.sent[0]?.queue, rabbitConfig.retryQueueName);
  assert.equal(mock.sent[0]?.options.headers?.['x-profile-retry-attempt'], 1);
  assert.equal(mock.wasAcked(), true);
  assert.equal(mock.wasNackedWithRequeue(), false);
});

test('envía a la DLQ después de agotar los reintentos', async () => {
  const mock = makeChannel();
  const consumer = makeConsumer(mock.channel);

  await consumer.handleMessage(makeMessage(rabbitConfig.maxRetries));

  assert.equal(mock.sent[0]?.queue, rabbitConfig.deadLetterQueueName);
  assert.equal(
    mock.sent[0]?.options.headers?.['x-profile-retry-attempt'],
    rabbitConfig.maxRetries,
  );
  assert.equal(mock.wasAcked(), true);
});

test('no reconoce el mensaje si el broker no confirma su redespacho', async () => {
  const mock = makeChannel(new Error('broker unavailable'));
  const consumer = makeConsumer(mock.channel);

  await consumer.handleMessage(makeMessage());

  assert.equal(mock.wasAcked(), false);
  assert.equal(mock.wasNackedWithRequeue(), true);
});
