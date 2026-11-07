# Servicio de Vacaciones

Vacaciones conserva TypeScript, Express, Prisma y PostgreSQL. Recibe eventos de empleado para mantener una réplica local y publica eventos de vacaciones; no consulta a otros servicios por REST.

## Regla de fechas

`fechaInicio` es el comienzo de la ausencia y `fechaFin` es el día de regreso. Se exige fin posterior al inicio; del 10 al 11 representa un día de vacaciones. Las fechas se normalizan por día UTC, manteniendo la convención que ya tenía el servicio. No se aceptan fechas de inicio pasadas, empleados inexistentes/retirados ni períodos que se solapen. La reprogramación aplica las mismas reglas.

Los rangos son `[inicio, regreso)`: un período puede empezar el día de regreso del anterior. El scheduler finaliza períodos cuyo regreso llegó (`lte: hoy`) y después inicia los siguientes. Los estados son `PROGRAMADA`, `EN_CURSO`, `FINALIZADA`, `CANCELADA`. El retiro conserva el saldo e historial y cancela las vacaciones pendientes.

## Operaciones

`/vacaciones` es la ruta canónica y `/vacations` conserva compatibilidad.

| Método | Ruta | Resultado |
|---|---|---|
| POST | `/vacaciones` | Programar, HTTP 201 |
| GET | `/vacaciones` | Consultar con filtros y paginación |
| GET | `/vacaciones/{id}` | Consultar período |
| PUT/PATCH | `/vacaciones/{id}` | Reprogramar un período PROGRAMADA |
| DELETE | `/vacaciones/{id}` | Cancelar un período futuro, conservando historial |
| GET | `/vacaciones/balances` y `/{employeeId}` | Saldos replicados por eventos |
| POST | `/vacaciones/{id}/forzar-inicio` | Operación de demostración existente; ADMIN en Gateway |
| POST | `/vacaciones/{id}/forzar-fin` | Operación de demostración existente; ADMIN en Gateway |

La documentación completa está en `/openapi.json`; Swagger UI en `/docs` y `/vacaciones/docs`. Un empleado inexistente devuelve 404 y las otras validaciones de negocio devuelven 400.

## Observabilidad

`/health` comprueba PostgreSQL y la conexión del consumidor AMQP, con HTTP 200/503 y checks `database`/`messageBroker`. `/metrics` expone métricas Prometheus de HTTP, proceso y transiciones. Los logs son JSON con timestamp, level, service, message, context y traceId. HTTP y amqplib están instrumentados con OpenTelemetry.

Para exportar a Zipkin configure `OTEL_EXPORTER_ZIPKIN_ENDPOINT` cuando el desarrollador 4 despliegue el servidor de trazas. Sin esa variable no se exportan trazas. El contexto W3C necesita propagación en los demás servicios para mostrar la cascada completa.

## Arranque y pruebas

Desde la raíz use `docker compose up --build -d`. No necesita Node instalado en Windows. Para desarrollo local: instalar dependencias con `npm ci`, definir `DATABASE_URL`, ejecutar `npm run db:generate` y `npm run build`.

`npm test` genera LCOV/JUnit y exige 70 % de líneas de dominio y servicios. `npm run test:ci` conserva el chequeo de calidad en SonarQube. `npm run test:integration` requiere las dependencias reales y aplica migraciones antes de probar HTTP, PostgreSQL, AMQP y scheduler.

Desde la raíz puede ejecutar los dos tipos de pruebas en contenedores aislados con `powershell -ExecutionPolicy Bypass -File scripts/probar-desarrollador3.ps1`.

Las publicaciones conservan la limitación previa de envío sin Outbox. No presente este servicio como garantía de entrega exactamente una vez ante una caída entre persistir estado y publicar. El scheduler debe revisarse antes de escalar a varias instancias.

Consulte [la guía de sustentación](../../docs/GUIA-DESARROLLADOR3-SUSTENTACION.md) y [la guía del desarrollador 4](../../docs/GUIA-DESARROLLADOR4-INTEGRACION.md).
