# Integración del desarrollador 4 con la entrega del desarrollador 3

El informe asigna al desarrollador 4 API Gateway, Docker, CI/CD y BDD. El desarrollador 3 entrega Vacaciones en TypeScript y Notificaciones en Go. La rama valentina ya incorpora main y conserva sus cambios de CI. Esta guía identifica los contratos que deben conservarse y los cambios compartidos pendientes.

## 1. Recibir el trabajo sin perder cambios del equipo

Integre los cambios con Git desde la raíz del mismo repositorio. No copie únicamente `src`: Notificaciones necesita `go.mod`, `go.sum`, `schema.sql`, `openapi.json`, `templates` y su nuevo Dockerfile. Vacaciones necesita su `package-lock.json` actualizado y `src/observability`.

No restaure los archivos Node/Prisma retirados de Notificaciones: fueron reemplazados por Go. Se conserva PostgreSQL y la tabla `NotificationHistory`; el arranque ejecuta SQL idempotente para crearla si falta y limpiar tokens de payloads antiguos. Respaldar datos antes de migrar una base existente. No se deben conservar copias de esos tokens dentro de Git.

El Compose raíz conserva Jenkins/SonarQube de valentina y el stack de observabilidad de main. Jenkins cambió al puerto 9091 para no colisionar con Prometheus (9090). También incorpora la configuración de Zipkin de Dev 3 y retira NODE_ENV del servicio Go. Los servicios siguen usando sus puertos internos existentes, 8082 y 8084 por defecto. No se modificaron las rutas o filtros de negocio del Gateway, los Jenkinsfiles ni la suite BDD. El ajuste de puerto de Jenkins se trasladó a sus valores de configuración.

## 2. Gateway y compatibilidad de rutas

Notificaciones sirve:

| Método | Ruta | Comportamiento |
|---|---|---|
| GET | `/notificaciones` | Historial; admite filtro empleadoId y paginación |
| GET | `/notificaciones/empleado/{empleadoId}` | Historial del empleado |
| GET | `/notificaciones/{messageId}` | Un registro por ID de evento |
| GET | `/notifications` y subrutas equivalentes | Alias para los clientes anteriores |
| GET | `/health` | Salud de PostgreSQL y consumidor AMQP |
| GET | `/metrics` | Prometheus |
| GET | `/openapi.json` | Contrato API |
| GET | `/docs` | Swagger UI |

Con parámetros de paginación devuelve `{data, meta}`. Sin ellos conserva el array anterior. `limit` o `size` admiten de 1 a 100; `page` de 1 a 1 000 000. Los campos ordenables están en OpenAPI. El campo de respuesta `employeeId` se conserva para compatibilidad. El historial rechaza escrituras HTTP con 405.

En `api-gateway/src/main/resources/application.yml`, amplíe el predicado actual de Notificaciones para incluir los dos nombres:

```yaml
- Path=/notifications,/notifications/**,/notificaciones,/notificaciones/**
```

Revise también los filtros de autorización: conservar JWT/RBAC y los encabezados de identidad. No publique los puertos de negocio para evitar el Gateway. La ruta antigua debe seguir disponible mientras migra clientes y BDD.

Agregue contratos de Vacaciones y Notificaciones al Swagger agregado. Ejemplo de rutas:

```yaml
- id: api-docs-notificaciones
  uri: ${NOTIFICACIONES_URL:http://notifications-service:8082}
  predicates:
    - Path=/v3/api-docs/notificaciones
  filters:
    - SetPath=/openapi.json
- id: api-docs-vacaciones
  uri: ${VACACIONES_URL:http://vacations-service:8084}
  predicates:
    - Path=/v3/api-docs/vacaciones
  filters:
    - SetPath=/openapi.json
```

Incluya esas URLs en `springdoc.swagger-ui.urls`. Si publica las UIs propias bajo un prefijo, reescriba su URL relativa de OpenAPI o utilice la UI agregada. Los `/health` y `/metrics` internos no sustituyen el `/health` propio del Gateway.

## 3. Contratos de eventos que debe respetar

Se mantienen `hr.events`, exchange fanout durable, y las colas existentes. Notificaciones usa `notifications.queue`; sus reintentos no requieren colas nuevas. Go no crea la topología de producción: debe existir antes de arrancar el consumidor.

La implementación conserva los seis eventos del informe e incorpora los de inicio y fin de vacaciones indicados por el PDF. También registra `empleado.creado` y `empleado.actualizado` por compatibilidad con el historial previo, sin enviar otra bienvenida.

| Familia | Identificador del empleado en data |
|---|---|
| `usuario.*`, `cuenta.*`, `empleado.*` | `id` |
| `vacaciones.programadas`, `vacaciones.iniciadas`, `vacaciones.finalizadas` | `empleadoId`; `id` identifica el período |

Los correos de vacaciones recuperan el destinatario del historial local, alimentado por los eventos anteriores de empleado/usuario. No se agregó una llamada REST hacia otro microservicio.

El catálogo normativo del profesor no estuvo disponible. No afirme conformidad exacta con ese catálogo hasta conseguirlo y comparar nombres, versiones y payloads. La compatibilidad implementada se basa en el PDF y los productores actuales del repositorio.

## 4. Cambiar BDD para dejar de leer tokens del historial

La dependencia que debe eliminarse está en `e2e-tests/src/test/java/com/microservicios/e2e/support/ApiCliente.java`, método `tokenDeEvento`, y en los pasos de onboarding que lo usan. El historial ya no contiene `payload.data.token`; esperar encontrarlo allí hará fallar la suite. Las aserciones de bienvenida deben verificar tipo de evento, empleado y resultado, no el token persistido.

Para seguir probando activación y recuperación:

1. Cree una cola temporal exclusiva de pruebas antes de crear el empleado o pedir recuperación.
2. Vincúlela a `hr.events` sin consumir las colas de los servicios.
3. Obtenga de esa cola el token del evento correspondiente, filtrando por empleado/email y tipo.
4. Manténgalo en memoria y úselo en `/auth/reset-password`.
5. Cierre la cola al terminar y redacte los datos sensibles en los reportes.

La cola se declara antes de la acción para no perder el evento. No añada un endpoint público para consultar tokens ni vuelva a guardarlos en Notificaciones. El script `scripts/demo-desarrollador3.cjs` muestra este patrón para una demostración; no reemplaza la suite Cucumber.

También adapte las fechas de los escenarios: `fechaFin` es regreso y debe ser posterior al inicio. Un día se representa como hoy/mañana. Los períodos contiguos no se solapan. La finalización se procesa en el día de regreso, no al día siguiente. Mantenga los estados `PROGRAMADA`, `EN_CURSO`, `FINALIZADA`, `CANCELADA`.

## 5. Jenkins y SonarQube

Notificaciones ya no se construye con npm ni Prisma. Use Go 1.25 o superior, o la etapa `build` de su Dockerfile. Sus comandos son:

```sh
mkdir -p reports
go test -coverprofile=reports/coverage.out ./...
go tool cover -func=reports/coverage.out
go build .
```

El archivo `services/notifications-service/sonar-project.properties` ya apunta al reporte nativo de Go. Cree su proyecto, Quality Gate >=70 % y Jenkinsfile con build, test, calidad y package. Una prueba fallida o un Quality Gate rechazado debe detener la entrega. La cobertura local completa de sentencias incluye el arranque y el adaptador AMQP, aunque las ramas que conectan realmente requieren integración.

Vacaciones conserva Jenkinsfile, compilación y reportes LCOV/JUnit. La ejecución Docker verificada aprobó 66 pruebas y midió 77,75 % de líneas de negocio; Go con integración midió 83,3 % de sentencias. `npm test` exige 70 % de líneas en dominio y servicios; `npm run test:ci` conserva la decisión de cobertura en SonarQube. Revise sus exclusiones al cerrar la exigencia global de cobertura: no presente el porcentaje limitado a negocio como cobertura de todas las capas.

Para integración de ambos servicios use `compose.dev3-tests.yml` y `scripts/probar-desarrollador3.ps1`. El entorno de pruebas genera credenciales efímeras y no publica puertos ni usa los volúmenes principales. Incorpore la ejecución equivalente a Jenkins antes de considerar aprobada la integración.

Revise cualquier script global que todavía recorra todos los servicios esperando encontrar `package.json`: Notificaciones tiene ahora `go.mod`. Los scripts de los retos históricos pueden asumir Node o tokens en el historial y necesitan revisión antes de reutilizarlos.

## 6. Observabilidad e infraestructura compartida

Los jobs ya existen en el stack integrado. Conserve sus targets y no los duplique:

```yaml
- job_name: notifications
  static_configs:
    - targets: [notifications-service:8082]
- job_name: vacations
  static_configs:
    - targets: [vacations-service:8084]
```

El `metrics_path` de ambos es `/metrics`. Si cambia los puertos internos, actualice también los targets. Las etiquetas no usan IDs de empleados para evitar cardinalidad alta.

Recolecte stdout JSON hacia el sistema central de logs. Los campos son `timestamp`, `level`, `service`, `message`, `context`, `traceId`. Notificaciones no imprime bodies AMQP, tokens, contenido de correo ni errores de conexión que puedan contener contraseñas.

Zipkin ya está definido en el Compose integrado. Mantenga en `.env`:

```dotenv
OTEL_EXPORTER_ZIPKIN_ENDPOINT=http://zipkin:9411/api/v2/spans
```

La variable ya se pasa desde Compose a los dos servicios. Después de cambiarla, recree sus contenedores. Vacaciones instrumenta HTTP y conserva las funciones W3C de main para AMQP y el scheduler; Go extrae `traceparent` de los headers AMQP y usa W3C Trace Context. Para una cascada completa, Gateway, Empleados y Auth también deben inyectar y propagar ese contexto. Un `correlationId` del body no sustituye a `traceparent`.

La segmentación de redes, revisión de dashboards y alertas, secretos generales y escala horizontal siguen bajo su integración. Los archivos de observabilidad existentes se conservaron; Prometheus identifica Notificaciones como lenguaje go. Evite múltiples instancias de Vacaciones hasta revisar el scheduler y la entrega durable de sus eventos: la actualización condicional evita algunas duplicaciones, pero no es un Outbox transaccional.

## 7. Límites que no debe ocultar en la entrega

- La simulación compone correos en memoria; no hay SMTP ni bandeja real.
- Los mensajes inválidos de Notificaciones se rechazan sin reencolar; con la topología actual no hay cola de cuarentena. Los fallos de infraestructura sí se reintentan y reencolan sin ACK exitoso.
- Las publicaciones de Vacaciones conservan el comportamiento previo de envío sin Outbox. Una caída entre persistir estado y publicar puede requerir recuperación. No atribuya a este cambio entrega exactamente una vez.
- Los cambios de cuenta los realiza Auth. Los cambios del estado maestro del empleado corresponden al desarrollador 1; la réplica de Vacaciones conserva `ACTIVO`/`RETIRADO` como antes.
- Sin ejecutar las pruebas Docker y la demostración en el entorno integrado no puede afirmarse que todo el proyecto pasó BDD.

## 8. Orden recomendado de integración

Integre primero código y configuración de Dev 3; compile los dos servicios y ejecute sus pruebas aisladas. Después actualice rutas, Swagger y BDD. Configure observabilidad y pipelines, y finalmente pruebe con el sistema completo: onboarding, vacaciones, regreso y retiro. Conserve evidencia de cada etapa y anote cualquier fallo pendiente.

## 9. Dependencia pendiente de revisión

El lock de Vacaciones incorpora correcciones compatibles de seguridad. Quedan avisos moderados de `node-cron` 3 y su `uuid` transitivo; npm propone una actualización mayor a node-cron 4. Revisar esa migración y su API de scheduler antes de aplicarla; no usar `npm audit fix --force` indiscriminadamente. Los detalles y el estado de pruebas están en `VERIFICACION-DESARROLLADOR3.md`.

## 10. Guías y validación entregadas

La instalación en otro computador está en `GUIA-DESARROLLADOR3-OTRO-PC.md`. La demostración está en `GUIA-DESARROLLADOR3-SUSTENTACION.md`. El detalle de lo ejecutado y sus límites está en `VERIFICACION-DESARROLLADOR3.md`. El flujo completo de negocio y la traza Java/TypeScript/Go ya se verificaron después de integrar main; no es necesario reconstruir la parte de Dev 3 para comenzar la integración de Gateway, BDD y CI pendiente.
