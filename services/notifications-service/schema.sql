-- Conserva el nombre y las columnas de la tabla que creó Prisma.
CREATE TABLE IF NOT EXISTS "NotificationHistory" (
 "id" TEXT PRIMARY KEY,
 "messageId" TEXT NOT NULL UNIQUE,
 "eventType" TEXT NOT NULL,
 "producer" TEXT NOT NULL,
 "employeeId" TEXT NOT NULL,
 "employeeName" TEXT NOT NULL,
 "email" TEXT NOT NULL,
 "processedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
 "payload" JSONB NOT NULL
);
CREATE INDEX IF NOT EXISTS notifications_employee ON "NotificationHistory" ("employeeId");

-- Limpia los payloads antiguos sin eliminar registros.
WITH public_payloads AS (
 SELECT n."id", jsonb_build_object(
  'id', n."payload"->'id', 'type', n."payload"->'type',
  'version', n."payload"->'version', 'occurredAt', n."payload"->'occurredAt',
  'producer', n."payload"->'producer', 'data', COALESCE((
   SELECT jsonb_object_agg(key,value) FROM jsonb_each(
    CASE WHEN jsonb_typeof(n."payload"->'data')='object'
     THEN n."payload"->'data' ELSE '{}'::jsonb END)
   WHERE key IN ('id','empleadoId','nombre','apellido','email','departamentoId','cargo',
    'fechaIngreso','fechaRetiro','fechaInicio','fechaFin','estado','fechaCreacion','motivo','permanente')
    AND jsonb_typeof(value) IN ('string','boolean','null')
  ), '{}'::jsonb)) AS payload
 FROM "NotificationHistory" n
)
UPDATE "NotificationHistory" n SET "payload"=p.payload
FROM public_payloads p WHERE n."id"=p."id" AND n."payload" IS DISTINCT FROM p.payload;
