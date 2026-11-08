# departments-service — Python 3.12 / FastAPI
#
# Multietapa con el mismo criterio que el resto del ecosistema: las dependencias
# se instalan en una etapa aparte para que su capa se reaproveche entre
# construcciones. Copiar primero requirements.txt y solo después el código es el
# equivalente a copiar el pom.xml antes del src/ en los servicios Java: si el
# código cambia pero las dependencias no, la instalación no se repite.

FROM python:3.12-slim AS deps

WORKDIR /app

# Se instala en un prefijo propio para poder copiar solo eso a la etapa final,
# sin arrastrar pip ni su caché.
COPY requirements.txt ./
RUN pip install --no-cache-dir --prefix=/instalacion -r requirements.txt


FROM python:3.12-slim AS runner

WORKDIR /app

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PORT=8081

# curl es para el healthcheck de docker-compose, igual que en los servicios Node.
RUN apt-get update \
 && apt-get install --no-install-recommends -y curl \
 && rm -rf /var/lib/apt/lists/*

COPY --from=deps /instalacion /usr/local

COPY app ./app
COPY migrations ./migrations
COPY alembic.ini ./

# Usuario sin privilegios: un proceso que solo necesita leer su código y hablar
# con PostgreSQL no tiene por qué ser root dentro del contenedor.
RUN useradd --create-home --uid 10001 servicio \
 && chown -R servicio:servicio /app
USER servicio

EXPOSE 8081

# Las migraciones se aplican al arrancar, igual que hacía la versión en Node con
# `prisma migrate deploy` y que hace employees-service con Flyway. La revisión
# inicial es idempotente, así que sobre una base ya creada por Prisma no cambia
# nada: solo registra que el esquema está al día.
#
# uvicorn con un solo worker a propósito: escalar es responsabilidad de Docker
# (más réplicas), no del proceso. Varios workers dentro del contenedor
# complicarían las métricas de Prometheus, que son por proceso.
CMD ["sh", "-c", "alembic upgrade head && exec uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8081} --no-access-log"]
