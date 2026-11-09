# Guía de ejecución y sustentación del desarrollador 3

Esta entrega corresponde a Vacaciones en TypeScript y Notificaciones migrado a Go, sobre la rama valentina actualizada con main (incluido el reto 8). Los correos son simulados por decisión del equipo. Mantenga el proyecto completo en `System_HR`: no copie los dos servicios a otra carpeta, porque necesitan el broker, las bases de datos, Auth y el Gateway.

La ruta manual con Postman está en la sección 9. Incluye los archivos para importar, la configuración y el orden de las peticiones.

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

## 9. Sustentar con Postman

### 9.1 Importar la colección y el entorno

Abra Postman Desktop. Pulse **Import**, seleccione estos dos archivos de la carpeta postman del repositorio y confirme la importación:

- `postman/desarrollador3-sustentacion.postman_collection.json`.
- `postman/desarrollador3-local.postman_environment.json`.

En Collections aparecerá **Dev 3 - Sustentación Vacaciones y Notificaciones**. En Environments aparecerá **Dev 3 - Local (completar valores locales)**. Seleccione ese entorno como activo en la parte superior antes de enviar peticiones. Si usa Postman web, necesita el Desktop Agent para acceder a localhost.

La colección ya tiene métodos, URLs, cuerpos JSON, autorización y scripts de validación. Los archivos que se entregan no incluyen contraseñas ni tokens reales.

### 9.2 Preparar una cuenta activa para la demostración manual

Este paso se hace una vez antes de empezar las peticiones. Crea un departamento y un empleado nuevos, activa su cuenta y termina sin programar vacaciones ni retirarlo. El token de activación se obtiene en una cola temporal independiente y se utiliza en memoria. No se añade una API para consultar tokens.

Desde PowerShell, en la raíz del proyecto:

```powershell
$taskSettings = @{}
Get-Content -Encoding UTF8 .env | ForEach-Object {
    if ($_ -match '^(AUTH_SEED_ADMIN_EMAIL|AUTH_SEED_ADMIN_PASSWORD)=(.*)$') {
        $taskSettings[$matches[1]] = $matches[2].Trim().Trim('"').Trim("'")
    }
}
$env:DEMO_ADMIN_USER = $taskSettings['AUTH_SEED_ADMIN_EMAIL']
$env:DEMO_ADMIN_PASSWORD = $taskSettings['AUTH_SEED_ADMIN_PASSWORD']
$taskEmployeePassword = Read-Host 'Elija una contraseña de prueba: mínimo 8 caracteres, una letra y un dígito' -AsSecureString
$env:DEMO_EMPLOYEE_PASSWORD = [System.Net.NetworkCredential]::new('', $taskEmployeePassword).Password
$env:DEMO_PREPARAR_POSTMAN = '1'
$taskPreviousEncoding = $OutputEncoding
$OutputEncoding = [System.Text.UTF8Encoding]::new()
try {
    Get-Content -Raw -Encoding UTF8 .\scripts\demo-desarrollador3.cjs |
        docker compose exec -T -e DEMO_ADMIN_USER -e DEMO_ADMIN_PASSWORD -e DEMO_EMPLOYEE_PASSWORD -e DEMO_PREPARAR_POSTMAN vacations-service node -
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo preparar la cuenta de Postman.' }
}
finally {
    $OutputEncoding = $taskPreviousEncoding
    Remove-Item Env:DEMO_ADMIN_USER, Env:DEMO_ADMIN_PASSWORD, Env:DEMO_EMPLOYEE_PASSWORD, Env:DEMO_PREPARAR_POSTMAN -ErrorAction SilentlyContinue
}
```

La contraseña se escribe oculta. Recuérdela para colocarla en Postman; no la imprime el script. Al terminar verá un JSON con tres valores públicos: `dev3EmpleadoId`, `dev3EmpleadoEmail`, `dev3DepartamentoId`. Cópielos al entorno. Los IDs cambian cada vez que ejecuta la preparación.

La preparación es un cliente de demostración: la contraseña la elige usted y se establece mediante el endpoint normal de Auth. El servicio de Notificaciones sigue simulando los correos y no guarda el token.

### 9.3 Completar las variables del entorno

Abra el entorno importado y complete sus valores locales:

| Variable | Qué colocar |
|---|---|
| `dev3BaseUrl` | `http://localhost:8080`, o el puerto GATEWAY_PORT de ese computador |
| `dev3AdminEmail` | AUTH_SEED_ADMIN_EMAIL de su .env |
| `dev3AdminPassword` | La contraseña vigente de ADMIN; en una instalación nueva es AUTH_SEED_ADMIN_PASSWORD |
| `dev3EmpleadoId` | El ID público que imprimió la preparación |
| `dev3EmpleadoEmail` | El email público que imprimió la preparación |
| `dev3DepartamentoId` | El ID del departamento que imprimió la preparación |
| `dev3EmpleadoPassword` | La contraseña de prueba que usted eligió en el paso anterior |

Deje vacíos `dev3AdminToken`, `dev3UsuarioToken`, `dev3PeriodoId`, `dev3CancelarId`, `dev3MessageId` y las fechas: los scripts los completan durante la ejecución. Marque contraseñas y tokens como sensibles en Postman y manténgalos como valores locales. No exporte ni publique un entorno rellenado con credenciales.

Las variables `{{dev3BaseUrl}}` y similares deben aparecer resueltas al usarlas. Si Postman indica que no están definidas, revise el entorno seleccionado y la escritura exacta del nombre.

### 9.4 Cómo configurar una petición manualmente

Por ejemplo, para autenticar ADMIN:

1. Cree o abra una petición y seleccione **POST**.
2. URL: `{{dev3BaseUrl}}/auth/login`.
3. En **Authorization**, seleccione **No Auth**.
4. En **Body**, seleccione **raw** y luego **JSON**.
5. Coloque:

```json
{
  "usuario": "{{dev3AdminEmail}}",
  "password": "{{dev3AdminPassword}}"
}
```

6. Confirme en **Headers** que exista `Content-Type: application/json`; Postman lo añade al seleccionar JSON.
7. Pulse **Send**. Debe ver HTTP **200**.
8. La petición importada ya guarda el token en `dev3AdminToken` con su script de respuesta. No necesita copiarlo del JSON.

Para una petición protegida seleccione **Bearer Token** y escriba en el campo Token únicamente `{{dev3AdminToken}}`. Postman añade el prefijo Bearer. En las peticiones importadas basta con conservar **Inherit auth from parent**, porque la colección ya configura ese token.

Las peticiones de login y la prueba sin token usan No Auth. La prueba de permisos USER usa `{{dev3UsuarioToken}}`. No cambie esas excepciones por el token ADMIN.

Los scripts están en **Scripts → Post-response**; en versiones anteriores aparecen como **Tests**. Revise **Test Results** después de cada envío: además del HTTP esperado, se validan los cambios de cuenta y la ausencia de credenciales en el historial.

### 9.5 Ejecutar las peticiones en orden

La colección tiene cuatro carpetas. Para sustentar, abra cada petición y pulse Send; lea el resultado antes de continuar. Las fechas se calculan como días UTC futuros al ejecutar la petición 01, para que no caduquen los ejemplos de la guía.

| Número | Petición | Resultado esperado |
|---|---|---|
| 01 | Login ADMIN | 200; guarda token ADMIN y fechas |
| 02 | Login del empleado activo | 200; guarda token USER |
| 03 | GET saldo del empleado | 200; réplica presente |
| 04–05 | Historial y notificación por ID de evento | 200; bienvenida sin token |
| 06 | GET vacaciones sin token | 401 |
| 07 | USER intenta programar vacaciones | 403 |
| 08 | Inicio y regreso iguales | 400 |
| 09 | Fechas invertidas | 400 |
| 10 | Inicio en el pasado | 400 |
| 11 | Programar vacaciones válidas | 201; guarda dev3PeriodoId y espera la confirmación |
| 12 | Repetir el período solapado | 400 |
| 13–14 | Consultar período y listar por empleado | 200 |
| 15 | Reprogramar con PUT | 200; sigue PROGRAMADA con fechas nuevas |
| 16 | Forzar inicio | 200; espera que Auth bloquee el login |
| 17 | Login del empleado en vacaciones | 401 |
| 18 | Consultar historial | 200; eventos de vacaciones y cuenta |
| 19 | Forzar fin | 200; espera que Auth reactive el login |
| 20 | Login después del regreso | 200 |
| 21–22 | Crear otro período futuro y cancelarlo | 201 y luego 200; estado CANCELADA |
| 23 | Retirar empleado, al final de la demostración | 200; espera la desactivación y la réplica RETIRADO |
| 24 | Login del empleado retirado | 401 |
| 25 | Intentar nuevas vacaciones para el retirado | 400 |
| 26 | Historial final | 200 y sin campos de credenciales |

**Retire al empleado únicamente al final.** Después del paso 23 necesitará ejecutar nuevamente la preparación y cambiar los IDs/email en el entorno para repetir el flujo desde el principio. Cada repetición genera otro empleado; no se borra el historial anterior.

Los pasos con efectos asíncronos hacen polling por condición en los scripts de Postman. Si una validación falla, revise Test Results, RabbitMQ y los logs; no dé por aprobado un paso únicamente porque la petición inicial respondió 200.

### 9.6 URLs y JSON que está enviando

Todas las peticiones de negocio van por el Gateway. Actualmente se usa `/notifications` porque es la ruta que el Gateway ya expone. El servicio Go también tiene `/notificaciones`, cuya publicación canónica debe completar el desarrollador 4.

Para crear el período, el paso 11 utiliza POST `{{dev3BaseUrl}}/vacaciones`, Body → raw → JSON:

```json
{
  "empleadoId": "{{dev3EmpleadoId}}",
  "fechaInicio": "{{dev3Inicio}}",
  "fechaFin": "{{dev3Fin}}"
}
```

Para reprogramar, el paso 15 utiliza PUT `{{dev3BaseUrl}}/vacaciones/{{dev3PeriodoId}}`:

```json
{
  "fechaInicio": "{{dev3NuevoInicio}}",
  "fechaFin": "{{dev3NuevoFin}}"
}
```

Las operaciones siguientes no llevan Body:

- GET `{{dev3BaseUrl}}/vacaciones/{{dev3PeriodoId}}`.
- GET `{{dev3BaseUrl}}/vacaciones?empleadoId={{dev3EmpleadoId}}`.
- GET `{{dev3BaseUrl}}/notifications/empleado/{{dev3EmpleadoId}}`.
- POST `{{dev3BaseUrl}}/vacaciones/{{dev3PeriodoId}}/forzar-inicio`.
- POST `{{dev3BaseUrl}}/vacaciones/{{dev3PeriodoId}}/forzar-fin`.
- DELETE `{{dev3BaseUrl}}/vacaciones/{{dev3CancelarId}}`.
- DELETE `{{dev3BaseUrl}}/empleados/{{dev3EmpleadoId}}`.

Para el login del empleado use POST `/auth/login`, No Auth y JSON con `usuario: {{dev3EmpleadoEmail}}` y `password: {{dev3EmpleadoPassword}}`, como ya está configurado en los pasos 02, 17, 20 y 24. Se comprueba un login nuevo en cada estado de la cuenta.

### 9.7 Qué mostrar y qué explicar

Muestre el método, URL, Body, código HTTP y Test Results. Explique que PROGRAMADA/EN_CURSO/FINALIZADA pertenecen al período y que Auth aplica la suspensión/reactivación de la cuenta mediante eventos. Los botones de forzar inicio/fin son operaciones de demostración; las pruebas de integración son las que verifican el scheduler con fechas controladas.

La salud individual y las métricas internas se muestran con los comandos de las secciones 1 y 5: no suponga que el `/health` del Gateway representa la salud de todos los servicios. Los puertos internos no están publicados directamente en Windows.

Para automatizar el mismo orden puede abrir Run collection y seleccionar las 26 peticiones, una iteración. Antes prepare una cuenta nueva y complete el entorno. El Runner retira esa cuenta al final, igual que la ruta manual.

Referencias de la interfaz: [importar archivos](https://learning.postman.com/v11/docs/getting-started/importing-and-exporting/importing-data), [variables locales y sensibles](https://learning.postman.com/latest-v-12/docs/use/send-requests/variables/define-variables).
