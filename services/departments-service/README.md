# Servicio de departamentos

API REST con Node.js 22, TypeScript, Express, Prisma y PostgreSQL. Mantiene el catálogo que empleados consulta durante el registro.

## Acceso

URL pública: `http://localhost:8080`, mediante el Gateway. El puerto interno predeterminado es 8081 y no está publicado al host.

| Método | Ruta pública | Resultado principal |
|---|---|---|
| POST | `/departamentos` | 201: creado |
| GET | `/departamentos` | 200: listado |
| GET | `/departamentos/{id}` | 200 o 404 |
| PUT | `/departamentos/{id}` | 200: actualizado |
| DELETE | `/departamentos/{id}` | 204: eliminado |

Los datos inválidos producen 400 y la indisponibilidad de persistencia produce 503.

## Modelo

`nombre` y `descripcion` son obligatorios al crear. `id` es opcional: si se envía se conserva; si se omite se genera mediante la secuencia del servicio. `createdAt` y `updatedAt` se gestionan automáticamente.

```bash
curl -i -X POST http://localhost:8080/departamentos \
  -H 'Content-Type: application/json' \
  -d '{"id":"IT","nombre":"Tecnología","descripcion":"Desarrollo y soporte técnico"}'

curl -i http://localhost:8080/departamentos/IT
```

Repetir la creación con el mismo ID puede provocar un rechazo por duplicado.

## Salud interna

El servicio mantiene `GET /health`, utilizado por el healthcheck de Docker. Desde la raíz se puede consultar dentro del contenedor:

```bash
docker compose exec departments-service sh -c 'curl -fsS http://localhost:$PORT/health'
```

El `/health` público en el puerto 8080 pertenece al Gateway. Las rutas internas `/docs` y `/docs/openapi.json` no están publicadas mediante el Gateway actual.

## Arquitectura y persistencia

La capa HTTP delega en el servicio de dominio y este utiliza un repositorio Prisma. El middleware central traduce los errores a HTTP. Prisma Migrate administra el esquema y los datos se conservan en un volumen de PostgreSQL.

Las operaciones de base de datos incluyen hasta tres intentos con esperas de 250 y 500 ms ante fallos transitorios. Si la dependencia continúa inaccesible, se responde 503.

## Relación con el Circuit Breaker

El Circuit Breaker reside en empleados, que es quien consume esta API. Este servicio conserva sus rutas y contrato. Una respuesta 404 por departamento inexistente debe distinguirse de una caída del servicio.

## Ejecución y pruebas

Desde la raíz:

```bash
docker compose up --build -d
```

Para ejecutar las pruebas propias, con Node.js y npm, desde la carpeta del servicio:

```bash
npm ci
npm test
```

La prueba integral de caída y recuperación está descrita en `tests/README.md` desde la raíz del repositorio.
