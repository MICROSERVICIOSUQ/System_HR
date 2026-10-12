CREATE TABLE "http_idempotency" (
    "key"          TEXT         NOT NULL,
    "method"       TEXT         NOT NULL,
    "path"         TEXT         NOT NULL,
    "statusCode"   INTEGER      NOT NULL,
    "responseBody" JSONB        NOT NULL,
    "createdAt"    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "http_idempotency_pkey" PRIMARY KEY ("key")
);
