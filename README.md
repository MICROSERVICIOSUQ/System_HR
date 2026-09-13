# Sistema de Recursos Humanos

Plataforma de microservicios para la gestion de recursos humanos.

## Estructura

```text
sistema_rrhh/
|-- services/              Microservicios independientes
|-- api-gateway/           Punto de entrada comun
|-- infrastructure/       Bases de datos, scripts y configuracion auxiliar
|-- tests/                 Pruebas que involucran varios servicios
|-- docs/                  Arquitectura, diagramas y decisiones tecnicas
|-- docker-compose.yml     Orquestacion del entorno completo
|-- .env.example           Variables de entorno de referencia
```

## Microservicios

| Servicio | Tecnologia | Puerto local | Persistencia |
|---|---|---:|---|
| departments-service | Node.js, TypeScript, Prisma | 8081 | PostgreSQL propia |
| employees-service | Java 21, Spring Boot | 8080 | PostgreSQL propia |

Cada microservicio mantiene su propio codigo, reglas de negocio, contrato HTTP y esquema de persistencia. Las bases de datos no se comparten entre servicios.

## Despliegue

El entorno completo se inicia con:

```bash
docker compose up --build
```

Los servicios se comunican por la red interna `sistema_rrhh_network`. Solo las APIs
publican puertos al host: `8081` para Departamentos y `8080` para Empleados. PostgreSQL
permanece accesible únicamente dentro de la red de Docker.

Los servicios exponen `/health` para que Docker compruebe que la aplicación está
respondiendo. La comprobación de disponibilidad de PostgreSQL se realiza con
`pg_isready` antes de iniciar cada microservicio.

## Salud y resiliencia

- Las bases `database-departamentos` y `database-empleados` usan
  `pg_isready -U <usuario> -d <base>` como health check.
- Los servicios esperan a que su propia base este `healthy` mediante
  `depends_on.condition: service_healthy`. Departamentos se comprueba en
  `GET /health` y Empleados en `GET /empleados/health`.
- Las imagenes de los dos microservicios incluyen `curl`; sus health checks
  ejecutan `curl -f` contra esos endpoints y respetan los puertos configurados
  por las variables de entorno.

Tras iniciar el entorno se puede comprobar el estado con:

```bash
docker compose ps
```

Los health checks controlan unicamente el orden de arranque. Para fallos
transitorios de PostgreSQL durante la ejecucion, ambos servicios hacen hasta
tres intentos con espera exponencial y usan timeouts de conexion. Si la base
continua sin estar disponible, la peticion devuelve `503` sin detener el
microservicio.

Para detener el entorno:

```bash
docker compose down
```

## Reglas de organizacion

- Los cambios internos de un servicio se realizan dentro de su directorio en `services/`.
- Las migraciones y modelos de datos pertenecen al servicio dueno de la informacion.
- `infrastructure/` contiene configuracion de ejecucion, no logica de negocio.
- Las pruebas que requieren varios servicios pertenecen a `tests/`.
- Las variables locales y credenciales se mantienen en `.env`, nunca en el repositorio.

## Estado actual

La orquestacion completa incluye una base PostgreSQL independiente para cada servicio. `employees-service` conserva sus datos en `gestion_empleados` y `departments-service` en `gestion_departamentos`.
