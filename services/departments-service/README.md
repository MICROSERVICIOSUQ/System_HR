# gestion-departamentos

Microservicio REST para la gestión de departamentos, desarrollado con Node.js + TypeScript + Express + Prisma + PostgreSQL.

El servicio está diseñado con una separación clara de capas para que la lógica de negocio, la persistencia y la capa HTTP se mantengan desacopladas.

---

## Tabla de contenido

- [Arquitectura](#arquitectura)
- [Modelo de datos](#modelo-de-datos)
- [API](#api)
  - [Resumen](#resumen)
  - [GET /health](#get-health)
  - [POST /departamentos](#post-departamentos)
  - [GET /departamentos](#get-departamentos)
  - [GET /departamentos/{id}](#get-departamentosid)
  - [PUT /departamentos/{id}](#put-departamentosid)
  - [DELETE /departamentos/{id}](#delete-departamentosid)
  - [Errores](#errores)
- [Puesta en marcha](#puesta-en-marcha)
- [Docker](#docker)
- [Estructura del proyecto](#estructura-del-proyecto)

---

## Arquitectura

Tres capas con dependencias en una sola dirección, más un manejador de errores transversal:

```
   HTTP
     │
     ▼
┌────────────────────────┐
│ DepartamentoController │  Traduce HTTP ↔ dominio. Sin lógica de negocio.
└──────────┬─────────────┘
           ▼
┌────────────────────────┐
│ DepartamentoService    │  Reglas y validaciones del negocio.
└──────────┬─────────────┘
           ▼
┌────────────────────────┐
│ DepartamentoRepository │  Consultas a Prisma / PostgreSQL.
└────────────────────────┘

┌──────────────────────────────┐
│ Middleware global de errores  │  Captura errores de Prisma, validación y rutas.
└──────────────────────────────┘
```

| Capa | Responsabilidad | Motivo |
|---|---|---|
| `controller` | Recibir la petición y devolver la respuesta | Aísla el transporte HTTP |
| `service` | Lógica de negocio y validaciones | Se puede probar sin HTTP |
| `repository` | Persistencia y consultas | Prisma queda encapsulado |
| `middleware` | Manejo centralizado de errores | Respuestas estándar y limpias |

---

## Modelo de datos

El modelo persistido en Prisma es:

```prisma
generator client {
  provider = "prisma-client-js"
}

datasource db {
  provider = "postgresql"
}

model Departamento {
  id          String   @id
  nombre      String
  descripcion String
  createdAt   DateTime @default(now())
  updatedAt   DateTime @updatedAt
}
```

| Campo | Tipo | Ejemplo | Restricciones |
|---|---|---|---|
| `id` | String | `D001` | Identificador secuencial generado automáticamente |
| `nombre` | String | `Tecnología` | Obligatorio |
| `descripcion` | String | `Departamento de desarrollo` | Obligatorio |
| `createdAt` | DateTime | `2026-09-10T12:00:00Z` | Automático |
| `updatedAt` | DateTime | `2026-09-10T12:00:00Z` | Automático |

---

## API

Base URL por defecto: `http://localhost:8081`

### Documentación Interactiva (Swagger UI)
* **Swagger UI:** [http://localhost:8081/docs](http://localhost:8081/docs)
* **Especificación OpenAPI 3.0 (JSON):** [http://localhost:8081/docs/openapi.json](http://localhost:8081/docs/openapi.json)

### Resumen

| Método | Ruta | Descripción | Éxito | Errores |
|---|---|---|---|---|
| `GET` | `/health` | Verificar disponibilidad del servicio | `200` | `500` |
| `POST` | `/departamentos` | Crear un departamento | `201` | `400` |
| `GET` | `/departamentos` | Listar todos los departamentos | `200` | `500` |
| `GET` | `/departamentos/{id}` | Consultar por identificador | `200` | `404` |
| `PUT` | `/departamentos/{id}` | Actualizar departamento | `200` | `400`, `404` |
| `DELETE` | `/departamentos/{id}` | Eliminar departamento | `204` | `404` |

---

### GET /health

```bash
curl -s http://localhost:8081/health
```

**Respuesta esperada (200 OK):**

```json
{
  "status": "ok",
  "service": "departamentos"
}
```

---

### POST /departamentos

```bash
curl -s -X POST http://localhost:8081/departamentos \
  -H "Content-Type: application/json" \
  -d '{
    "nombre": "Tecnología",
    "descripcion": "Departamento encargado del desarrollo y soporte técnico"
  }'
```

**Respuesta esperada (201 Created):**

```json
{
  "id": "b4f7a3cc-9d31-4d2d-9d5f-3d2fa5432c9a",
  "nombre": "Tecnología",
  "descripcion": "Departamento encargado del desarrollo y soporte técnico",
  "createdAt": "2026-09-10T12:00:00.000Z",
  "updatedAt": "2026-09-10T12:00:00.000Z"
}
```

---

### GET /departamentos

```bash
curl -s http://localhost:8081/departamentos
```

**Respuesta esperada (200 OK):**

```json
[
  {
    "id": "b4f7a3cc-9d31-4d2d-9d5f-3d2fa5432c9a",
    "nombre": "Tecnología",
    "descripcion": "Departamento encargado del desarrollo y soporte técnico",
    "createdAt": "2026-09-10T12:00:00.000Z",
    "updatedAt": "2026-09-10T12:00:00.000Z"
  }
]
```

---

### GET /departamentos/{id}

```bash
curl -s http://localhost:8081/departamentos/b4f7a3cc-9d31-4d2d-9d5f-3d2fa5432c9a
```

**Respuesta esperada (200 OK):**

```json
{
  "id": "b4f7a3cc-9d31-4d2d-9d5f-3d2fa5432c9a",
  "nombre": "Tecnología",
  "descripcion": "Departamento encargado del desarrollo y soporte técnico",
  "createdAt": "2026-09-10T12:00:00.000Z",
  "updatedAt": "2026-09-10T12:00:00.000Z"
}
```

**Si no existe el registro:**

```json
{
  "status": "error",
  "message": "El departamento con id b4f7a3cc-9d31-4d2d-9d5f-3d2fa5432c9a no existe"
}
```

---

### PUT /departamentos/{id}

```bash
curl -s -X PUT http://localhost:8081/departamentos/b4f7a3cc-9d31-4d2d-9d5f-3d2fa5432c9a \
  -H "Content-Type: application/json" \
  -d '{
    "nombre": "Infraestructura",
    "descripcion": "Departamento responsable de infraestructura y operaciones"
  }'
```

**Respuesta esperada (200 OK):**

```json
{
  "id": "b4f7a3cc-9d31-4d2d-9d5f-3d2fa5432c9a",
  "nombre": "Infraestructura",
  "descripcion": "Departamento responsable de infraestructura y operaciones",
  "createdAt": "2026-09-10T12:00:00.000Z",
  "updatedAt": "2026-09-10T12:30:00.000Z"
}
```

---

### DELETE /departamentos/{id}

```bash
curl -s -X DELETE http://localhost:8081/departamentos/b4f7a3cc-9d31-4d2d-9d5f-3d2fa5432c9a
```

**Respuesta esperada (204 No Content):**

Sin cuerpo en la respuesta.

---

### Errores

| HTTP | Mensaje | Cuando ocurre |
|---|---|---|
| `400` | `Datos inválidos` + `errors[{field,message}]` | El body no cumple con Zod |
| `400` | `Parámetros inválidos` + `errors[{field,message}]` | El `id` no es válido |
| `404` | `Ruta no encontrada: METHOD /ruta` | Ruta no definida |
| `404` | `El departamento con id {id} no existe` | Consulte un departamento inexistente |
| `500` | `Error interno del servidor.` | Error no controlado |

---

## Puesta en marcha

### Requisitos

| Requisito | Versión | Uso |
|---|---|---|
| Node.js | 22.x | Ejecutar TypeScript con `tsx` |
| PostgreSQL | 16.x | Base de datos principal |
| Docker | reciente | Para ejecución en contenedor |

### Instalación local

```bash
npm install
```

Configurar la variable de entorno:

```env
DATABASE_URL="postgresql://postgres:postgres@localhost:5432/gestion_departamentos"
PORT=8081
```

Generar el cliente de Prisma:

```bash
npx prisma generate
```

Aplicar el esquema a la base de datos:

```bash
npx prisma db push
```

Iniciar la aplicación:

```bash
npx tsx src/server.ts
```

---

## Docker

### Construcción

```bash
docker build -t gestion-departamentos .
```

### Ejecución con Docker Compose

```bash
docker compose up --build
```

Esto levanta:

- base de datos PostgreSQL
- servicio de departamentos

### Verificar servicio

```bash
curl http://localhost:8081/health
```

---

## Estructura del proyecto

```text
departments-service/
├── Dockerfile
├── docker-compose.yml
├── .dockerignore
├── .env
├── package.json
├── tsconfig.json
├── prisma/
│   └── schema.prisma
├── prisma.config.ts
├── src/
│   ├── app.ts
│   ├── server.ts
│   ├── config/
│   │   └── db.ts
│   ├── controllers/
│   │   └── department.controller.ts
│   ├── middleware/
│   │   └── error.middleware.ts
│   ├── repositories/
│   │   └── department.repository.ts
│   ├── routes/
│   │   └── department.routes.ts
│   ├── schemas/
│   │   └── department.schema.ts
│   ├── services/
│   │   └── department.service.ts
│   └── utils/
│       └── http.ts
└── README.md
```

---

## Decisiones de diseño

- La lógica de negocio no vive en los controladores.
- Prisma queda aislado detrás del repositorio.
- Los errores se manejan de forma centralizada.
- La API usa rutas uniformes sin prefijo `/api` para mantener consistencia en microservicios.
- El proyecto usa TypeScript con `NodeNext` y rutas explícitas para mantener compatibilidad con el entorno actual.

---

## Limitaciones conocidas

- Los datos solo persisten mientras la base de datos esté disponible.
- No hay autenticación ni autorización en este microservicio.
- El servicio no incluye observabilidad avanzada (tracing, métricas, logs estructurados) aún.
- La validación está enfocada en el dominio de departamentos y no cubre eventos avanzados de negocio.