FROM node:22-alpine AS deps

WORKDIR /app

ARG PRISMA_GENERATE_DATABASE_URL=postgresql://placeholder:placeholder@placeholder:5432/placeholder
ENV DATABASE_URL=${PRISMA_GENERATE_DATABASE_URL}

COPY package*.json ./
RUN npm ci --include=dev

COPY prisma ./prisma
COPY prisma.config.ts ./
COPY src ./src
RUN npx prisma generate

FROM node:22-alpine AS runner

WORKDIR /app
ENV NODE_ENV=production
ENV PORT=8083

RUN apk add --no-cache curl

COPY package*.json ./
COPY --from=deps /app/node_modules ./node_modules
COPY --from=deps /app/prisma ./prisma
COPY --from=deps /app/prisma.config.ts ./
COPY --from=deps /app/src ./src

EXPOSE 8083

# --import carga el SDK de OpenTelemetry ANTES del codigo de la aplicacion.
# La instrumentacion automatica parchea http, express, pg y amqplib al
# cargarlos; si el SDK arrancara despues, el parche llegaria tarde y no se
# generaria ni un span.
CMD ["sh", "-c", "npx prisma migrate deploy && exec npx tsx --import ./src/observability/tracing.ts src/server.ts"]
