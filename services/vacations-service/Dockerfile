FROM node:22-alpine AS deps

WORKDIR /app

ARG PRISMA_GENERATE_DATABASE_URL=postgresql://placeholder:placeholder@placeholder:5432/placeholder
ENV DATABASE_URL=${PRISMA_GENERATE_DATABASE_URL}

COPY package*.json ./
RUN npm ci --include=dev

COPY prisma ./prisma
COPY prisma.config.ts ./
COPY tsconfig.json ./
COPY src ./src
RUN npx prisma generate
RUN npm run build

FROM node:22-alpine AS runner

WORKDIR /app
ENV NODE_ENV=production
ENV PORT=8084

RUN apk add --no-cache curl

COPY package*.json ./
COPY --from=deps /app/node_modules ./node_modules
COPY --from=deps /app/prisma ./prisma
COPY --from=deps /app/prisma.config.ts ./
COPY --from=deps /app/dist ./dist

EXPOSE 8084

CMD ["sh", "-c", "npx prisma migrate deploy && exec node dist/server.js"]