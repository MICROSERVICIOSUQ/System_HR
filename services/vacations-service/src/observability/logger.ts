import { trace, context } from '@opentelemetry/api';

function write(level: string, message: string, details: unknown[]) {
  const safeDetails = details.map(value => value instanceof Error
    ? { name: value.name, message: value.message } : value);
  process.stdout.write(JSON.stringify({
    timestamp: new Date().toISOString(), level, service: 'vacations-service',
    message, context: { details: safeDetails },
    traceId: trace.getSpan(context.active())?.spanContext().traceId ?? '',
  }) + '\n');
}
export const log = {
  info: (message: string, ...details: unknown[]) => write('INFO', message, details),
  warn: (message: string, ...details: unknown[]) => write('WARN', message, details),
  error: (message: string, ...details: unknown[]) => write('ERROR', message, details),
};
