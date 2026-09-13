const TRANSIENT_POSTGRES_CODES = new Set([
  '08000',
  '08003',
  '08006',
  '08001',
  '57P01',
  '57P02',
  '57P03',
  'ECONNREFUSED',
  'ECONNRESET',
  'ETIMEDOUT',
]);

const MAX_ATTEMPTS = 3;
const INITIAL_DELAY_MS = 250;

type DatabaseError = Error & {
  code?: string;
  cause?: unknown;
};

export class DatabaseUnavailableError extends Error {
  constructor(cause: unknown) {
    super('La base de datos no esta disponible temporalmente.', { cause });
    this.name = 'DatabaseUnavailableError';
  }
}

export async function withDatabaseRetry<T>(operation: () => Promise<T>): Promise<T> {
  for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt += 1) {
    try {
      return await operation();
    } catch (error) {
      if (!isTransientDatabaseError(error)) {
        throw error;
      }

      if (attempt === MAX_ATTEMPTS) {
        throw new DatabaseUnavailableError(error);
      }

      await wait(INITIAL_DELAY_MS * 2 ** (attempt - 1));
    }
  }

  throw new Error('No se pudo ejecutar la operacion de base de datos.');
}

function isTransientDatabaseError(error: unknown): boolean {
  const candidate = error as DatabaseError;
  const code = candidate?.code;

  return (
    candidate?.name === 'PrismaClientInitializationError' ||
    (typeof code === 'string' && TRANSIENT_POSTGRES_CODES.has(code)) ||
    (candidate?.cause !== undefined && isTransientDatabaseError(candidate.cause))
  );
}

function wait(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}
