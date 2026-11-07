# Guía de ejecución y sustentación del desarrollador 3

Esta entrega corresponde a Vacaciones en TypeScript y Notificaciones migrado a Go, sobre la rama valentina actualizada con main (incluido el reto 8). Los correos son simulados por decisión del equipo. Mantenga el proyecto completo en `System_HR`: no copie los dos servicios a otra carpeta, porque necesitan el broker, las bases de datos, Auth y el Gateway.

## Preparación previa

Complete primero [la instalación y el arranque en otro computador](GUIA-DESARROLLADOR3-OTRO-PC.md). Esta guía se concentra en la sustentación. Abra PowerShell en la raíz de System_HR y mantenga los servicios del grupo de negocio encendidos.

## 1. Comprobar la salud de tu parte

```powershell
docker compose exec notifications-service curl -fsS http://localhost:8082/health
docker compose exec vacations-service curl -fsS http://localhost:8084/health
```

Los puertos anteriores son los internos predeterminados. Si cambió `NOTIFICACIONES_PORT` o `VACACIONES_PORT`, use esos valores. La respuesta debe incluir `status: UP`, el servicio y `checks.database` / `checks.messageBroker` en `UP`. Una dependencia no disponible produce HTTP 503; no basta con tener el proceso encendido.

## 2. Ejecutar las pruebas automatizadas

Desde la raíz:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\probar-desarrollador3.ps1
```

El script genera una contraseña efímera para pruebas, levanta dos PostgreSQL y un RabbitMQ separados del sistema principal, ejecuta pruebas unitarias e integración de ambos servicios y retira esos contenedores al terminar. No borra los volúmenes del sistema principal.

Las pruebas verifican fechas, empleado retirado, CRUD, scheduler en el día de regreso, persistencia real, consumo AMQP, duplicados, rutas HTTP y ausencia de tokens. Las unitarias de Go comprueban expresamente que un fallo de base de datos reintenta tres veces y no emite ACK exitoso.

Los reportes quedan en:

- `services/notifications-service/reports/coverage.out` y `coverage.txt`.
- `services/vacations-service/reports/lcov.info` y `junit.xml`.

Go mide sentencias del paquete completo. La configuración heredada de Vacaciones mide líneas de dominio y servicios; no confunda ese porcentaje con cobertura de todas las capas. Las pruebas de integración recorren HTTP, PostgreSQL y RabbitMQ. El desarrollador 4 debe revisar el alcance de cobertura de SonarQube al cerrar la rúbrica global.

## 3. Demostrar el flujo completo sin exponer tokens

El script de demostración usa las APIs reales a través del Gateway. Antes de crear el empleado abre una cola temporal de observación en RabbitMQ. Así obtiene el token de activación en memoria, como sustituto del correo simulado, sin leerlo del historial y sin consumir mensajes de las colas de producción.

En PowerShell, cargue únicamente las credenciales del administrador desde `.env`, sin imprimirlas:

```powershell
$taskSettings = @{}
Get-Content -Encoding UTF8 .env | ForEach-Object {
    if ($_ -match '^(AUTH_SEED_ADMIN_EMAIL|AUTH_SEED_ADMIN_PASSWORD)=(.*)$') {
        $taskSettings[$matches[1]] = $matches[2].Trim()
    }
}
$env:DEMO_ADMIN_USER = $taskSettings['AUTH_SEED_ADMIN_EMAIL']
$env:DEMO_ADMIN_PASSWORD = $taskSettings['AUTH_SEED_ADMIN_PASSWORD']
$taskPreviousEncoding = $OutputEncoding
$OutputEncoding = [System.Text.UTF8Encoding]::new()
try {
    Get-Content -Raw -Encoding UTF8 .\scripts\demo-desarrollador3.cjs |
        docker compose exec -T -e DEMO_ADMIN_USER -e DEMO_ADMIN_PASSWORD vacations-service node -
    if ($LASTEXITCODE -ne 0) { throw 'La demostración no terminó; revise el paso indicado.' }
}
finally {
    $OutputEncoding = $taskPreviousEncoding
    Remove-Item Env:DEMO_ADMIN_USER, Env:DEMO_ADMIN_PASSWORD -ErrorAction SilentlyContinue
}
```

Si su `.env` utiliza valores entre comillas, retire las comillas de las dos variables cargadas antes de ejecutar este bloque. El preparador de esta guía las escribe sin comillas.

La demostración crea un departamento y un empleado nuevos en cada ejecución y conserva sus datos para inspeccionarlos. No usa cuentas existentes. Debe mostrar:

| Paso | Resultado esperado |
|---|---|
| Login de ADMIN | 200 |
| Creación del empleado y activación por token | Cuenta activada y login del empleado 200 |
| Inicio y regreso iguales | 400 |
| Programación válida | 201 y notificación de vacaciones |
| Período solapado | 400 |
| Forzar inicio con la operación de demostración existente | EN_CURSO, suspensión de cuenta y login 401 |
| Forzar fin con la operación de demostración existente | FINALIZADA, reactivación y login 200 |
| Historial | Notificaciones del flujo sin tokens ni contraseñas |
| Retiro del empleado | Login 401 y nueva solicitud de vacaciones rechazada con 400 |

El inicio y fin manuales sirven para demostrar los efectos en segundos. No los presente como prueba del reloj automático. Las pruebas de integración verifican el scheduler usando registros de prueba con fechas controladas.

Para repetir la demostración, ejecute el mismo bloque: genera otros IDs. Si falla un paso, no lo presente como aprobado; revise los logs y la dependencia indicada.

## 4. Qué explicar al profesor

1. Vacaciones conserva una réplica local del empleado, alimentada por eventos; no consulta a Empleados por REST para validar cada solicitud.
2. `fechaInicio` suspende la cuenta y `fechaFin` es el regreso. Del 10 al 12 representa ausencia los días 10 y 11. Un día de vacaciones se representa del 10 al 11.
3. El scheduler publica `vacaciones.iniciadas` y `vacaciones.finalizadas`. Auth cambia la cuenta; Vacaciones no modifica directamente la base de Auth.
4. Notificaciones consume eventos, usa plantillas y registra un historial inmutable. Un evento repetido conserva una sola fila por `messageId`.
5. En los eventos de vacaciones, `data.id` identifica el período y `data.empleadoId` al empleado. No se confunden los dos IDs.
6. El token llega al servicio para componer el correo simulado en memoria. No se guarda ni se muestra en logs o consultas.
7. Notificaciones usa Go y Vacaciones TypeScript. Los lenguajes de los demás servicios siguen siendo responsabilidad de sus compañeros.

## 5. Mostrar observabilidad y documentación

```powershell
docker compose exec notifications-service curl -fsS http://localhost:8082/metrics
docker compose exec vacations-service curl -fsS http://localhost:8084/metrics
docker compose exec notifications-service curl -fsS http://localhost:8082/openapi.json
docker compose exec vacations-service curl -fsS http://localhost:8084/openapi.json
docker compose logs --tail=30 notifications-service vacations-service
```

Las métricas incluyen resultados de procesamiento en Notificaciones, duración HTTP y transiciones en Vacaciones. Los logs incluyen timestamp, nivel, servicio, mensaje, contexto y traceId. Un log de arranque puede no pertenecer a una traza de petición.

Ambos servicios tienen `/docs`. El acceso por navegador y la incorporación al Swagger agregado del Gateway se detallan en la guía del desarrollador 4. La UI carga sus recursos desde un CDN y requiere conexión a Internet.

La fusión de main incorporó el stack de observabilidad. Zipkin usa `OTEL_EXPORTER_ZIPKIN_ENDPOINT=http://zipkin:9411/api/v2/spans`. Jenkins queda en el puerto 9091 y Prometheus en 9090 para evitar una colisión. Notificaciones en Go publica las métricas HTTP con los nombres que usa el dashboard. Dev 4 debe conservar estos contratos al completar la integración global.

## 6. Orden de la sustentación y evidencias

Prepare cuatro ventanas: esta guía, PowerShell con `docker compose ps`, PowerShell con logs y las herramientas de documentación/observabilidad que el desarrollador 4 haya integrado.

Orden sugerido: explique responsabilidades, muestre salud, ejecute pruebas, ejecute la demostración y muestre el historial y las métricas. Guarde capturas de los estados y reportes, evitando `.env`, tokens y contraseñas. La demostración ya oculta esas credenciales en su salida normal.

Para detener el sistema conservando los datos:

```powershell
docker compose down
```

Para volver a arrancar sin reconstruir:

```powershell
docker compose up -d
```

Después de cambiar código o plantillas, use `docker compose up --build -d`. No use `down -v` en el sistema principal: elimina los volúmenes.

## 7. Estado de verificación de esta entrega

Se compilaron ambos servicios y pasaron las pruebas unitarias. La cifra exacta de cobertura y los límites de integración están en `docs/VERIFICACION-DESARROLLADOR3.md`. Las pruebas Docker y la demostración completa ya se ejecutaron con éxito después de integrar main. En la sustentación repita los pasos para mostrar su propia ejecución.

## 8. Evidencia comprobada y acceso a la traza

En esta entrega las pruebas de ambos servicios con PostgreSQL/RabbitMQ reales aprobaron. Vacaciones aprobó 66 pruebas unitarias y Go midió 83,3 % de cobertura con integración. La ejecución completa aprobó los siete pasos descritos arriba.

En el computador donde se hizo esta validación puede abrir la traza `c8d6eaa6561dae61f11e89cc2e33b905` en Zipkin. Incluye Gateway, Empleados, Auth, Perfiles, Vacaciones y Notificaciones. En otro computador o tras reiniciar Zipkin ejecute la demostración y busque las trazas nuevas; no espere encontrar ese mismo ID.

Antes de presentar, confirme `git branch --show-current` y revise el último commit. La base correcta es valentina con main integrado y la entrega de Dev 3 publicada o copiada según la guía de instalación.
