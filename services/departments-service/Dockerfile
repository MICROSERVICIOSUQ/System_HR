FROM node:22-alpine AS deps

WORKDIR /app
ENV DATABASE_URL=postgresql://postgres:postgres@localhost:5432/gestion_departamentos

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
