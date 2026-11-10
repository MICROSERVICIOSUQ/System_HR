# Servicio de Notificaciones en Go

Consume eventos de RabbitMQ y conserva un historial inmutable en PostgreSQL. Los correos usan plantillas de texto; el proveedor mock es el predeterminado y SMTP puede habilitarse explícitamente. El token solo se usa en el cuerpo renderizado en memoria y no se persiste ni se registra. No hay endpoint de creación de notificaciones.

## Archivos para entender el servicio

- `main.go`: configuración, conexiones, arranque y apagado.
- `events.go`: validación, plantillas, idempotencia y payload público.
- `database.go` y `schema.sql`: consultas y compatibilidad con la tabla anterior.
- `rabbitmq.go`: consumidor, reintentos, ACK y propagación W3C.
- `http.go` y `openapi.json`: consultas, salud, métricas y Swagger UI.
- `observability.go`: logs JSON y exportación opcional a Zipkin.
- `templates`: textos modificables de los correos simulados.

## Ejecución

Desde la raíz: `docker compose up --build -d`. El Dockerfile compila con Go 1.25; no necesita Go instalado en Windows. Conserva el puerto interno 8082, la cola `notifications.queue`, el exchange `hr.events` y la tabla `NotificationHistory`. Al arrancar limpia los tokens de los payloads antiguos sin eliminar el historial.

Variables: `DATABASE_URL` obligatoria, `PORT` (8082), `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD`; opcionalmente `RABBITMQ_URL`, `TEMPLATES_DIR` y `OTEL_EXPORTER_ZIPKIN_ENDPOINT`. Para SMTP configure `EMAIL_SENDER=smtp`, `EMAIL_SMTP_HOST`, `EMAIL_SMTP_PORT`, `EMAIL_SMTP_FROM`, `EMAIL_SMTP_TLS` (por defecto `true`), y opcionalmente `EMAIL_SMTP_USERNAME`/`EMAIL_SMTP_PASSWORD`. Sin `EMAIL_SENDER=smtp` se usa el mock. Las credenciales se inyectan desde el entorno, no se escriben en el código. Las plantillas están embebidas en el binario; para modificarlas puede reconstruir la imagen o montar un directorio completo mediante `TEMPLATES_DIR`.

## API

- `GET /notificaciones`: historial y filtro opcional `empleadoId`.
- `GET /notificaciones/empleado/{empleadoId}`: historial del empleado.
- `GET /notificaciones/{messageId}`: registro por ID de evento.
- `/notifications` conserva las mismas operaciones como alias.
- `GET /health`: checks reales de PostgreSQL y consumidor AMQP, HTTP 200/503.
- `GET /metrics`, `/docs`, `/openapi.json`: observabilidad y documentación.

Sin paginación se devuelve un array; con `page`, `limit`/`size`, `sortBy` u `order` se devuelve `{data, meta}`. Los detalles y modelos están en OpenAPI. El Gateway actual enruta `/notifications`; el desarrollador 4 debe incorporar el nombre `/notificaciones` y el Swagger agregado.

## Eventos

Consume los seis del informe (`usuario.creado`, `usuario.recuperacion`, `vacaciones.programadas`, `cuenta.activada`, `cuenta.desactivada`, `empleado.retirado`), los de inicio/fin de vacaciones indicados por el PDF y los de creación/actualización de empleado conservados del servicio anterior. En vacaciones el empleado es `data.empleadoId`; en las otras familias es `data.id`.

El índice único de `messageId` evita registrar y simular dos veces un evento. Los fallos transitorios se intentan tres veces; si persisten, se rechazan sin requeue y RabbitMQ los enruta a `notifications.dlq` mediante la política declarada en `infrastructure/rabbitmq/definitions.json`. Los mensajes inválidos se rechazan sin imprimir su contenido.

## Verificación

Con Go instalado: `go test -coverprofile=reports/coverage.out ./...`, creando antes `reports`; `go tool cover -func=reports/coverage.out` muestra el porcentaje. La prueba con etiqueta `integration` necesita PostgreSQL y RabbitMQ reales.

Sin instalar Go, ejecute desde la raíz `powershell -ExecutionPolicy Bypass -File scripts/probar-desarrollador3.ps1`. El entorno está aislado y exige al menos 70 % de cobertura de Go.

Consulte [la guía de sustentación](../../docs/GUIA-DESARROLLADOR3-SUSTENTACION.md) y [la guía del desarrollador 4](../../docs/GUIA-DESARROLLADOR4-INTEGRACION.md).
