FROM node:22-alpine AS deps

WORKDIR /app

# `prisma generate` exige que DATABASE_URL exista aunque no se conecte a nada.
# Se usa un valor de relleno evidente para que ninguna capa de la imagen contenga
# credenciales con aspecto real. La URL de verdad la inyecta docker-compose en
# tiempo de ejecución y solo vive en la etapa runner.
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
ENV PORT=8081

RUN apk add --no-cache curl

COPY package*.json ./
COPY --from=deps /app/node_modules ./node_modules
COPY --from=deps /app/prisma ./prisma
COPY --from=deps /app/prisma.config.ts ./
COPY --from=deps /app/src ./src

EXPOSE 8081

CMD ["sh", "-c", "npx prisma migrate deploy && exec npx tsx src/server.ts"]
