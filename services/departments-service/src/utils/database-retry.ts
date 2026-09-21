const TRANSIENT_POSTGRES_CODES = new Set([
  // Códigos de clase 08 — problemas de conexión PostgreSQL
  '08000',
  '08003',
  '08006',
  '08001',
  // Códigos de terminación abrupta del servidor
  '57P01',
  '57P02',
  '57P03',
  // Errores de red Node.js (llegan como code en el error del driver)
  'ECONNREFUSED',
  'ECONNRESET',
  'ETIMEDOUT',
  'ENOTFOUND',
  // Códigos de error de Prisma: BD inalcanzable (P1001) y timeout (P1002)
  'P1001',
  'P1002',
]);

/** Fragmentos de texto que delatan un fallo de red cuando code no está presente. */
const TRANSIENT_MESSAGE_PATTERNS = [
  'ECONNREFUSED',
  'ECONNRESET',
  'ETIMEDOUT',
  'ENOTFOUND',
  'connect ETIMEDOUT',
  'Connection terminated',
  'terminated unexpectedly',
];

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
  const name = candidate?.name;
  const message = candidate?.message ?? '';

  // Error de inicialización de Prisma (BD nunca llegó a estar disponible)
  if (name === 'PrismaClientInitializationError') return true;

  // Error desconocido de Prisma en runtime (BD cae cuando ya había conexión)
  if (name === 'PrismaClientUnknownRequestError') return true;

  // Código conocido de PostgreSQL, Node o Prisma presente en el campo code
  if (typeof code === 'string' && TRANSIENT_POSTGRES_CODES.has(code)) return true;

  // Cuando code no está disponible, inspeccionar el mensaje del error
  if (TRANSIENT_MESSAGE_PATTERNS.some((pattern) => message.includes(pattern))) return true;

  // Propagar la comprobación recursivamente a la causa del error
  if (candidate?.cause !== undefined) return isTransientDatabaseError(candidate.cause);

  return false;
}

function wait(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}
