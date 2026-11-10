# Guía de las correcciones de pendientes en main

Esta guía explica los cambios realizados para los cuatro estados **PENDIENTE marcados en rojo** del documento de pendientes: métricas, outbox de Auth/Vacaciones, concurrencia del publicador y colecciones de Postman. El trabajo partió de `main` actualizada con `git pull --ff-only origin main`, en el commit `4857742`.

## 1. Qué cambió

| Pendiente | Corrección |
|---|---|
| Ruta común de métricas | Auth, Empleados y Gateway ofrecen `/metrics`, además de conservar `/actuator/prometheus`. Prometheus consulta el alias común |
| Eventos de Auth y Vacaciones que podían perderse | El cambio de negocio y su evento se guardan en una misma transacción de base de datos. Un publicador separado reintenta la entrega a RabbitMQ |
| Dos publicadores podían seleccionar la misma fila | La selección de pendientes utiliza `FOR UPDATE SKIP LOCKED` dentro de una transacción |
| Cuatro colecciones fallaban al repetirlas o requerían pasos manuales | Las colecciones generan datos únicos, acceden al negocio por Gateway y esperan las réplicas. Un comando coordina la caída y recuperación de Departamentos |

## 2. Métricas y compatibilidad de rutas

Se agregaron controladores pequeños que leen el registro Prometheus existente. No se movió la base de rutas de Actuator: así se conservan las sondas y los clientes que usan sus direcciones anteriores.

| Servicio | Alias común | Ruta anterior conservada |
|---|---|---|
| Gateway | `/metrics` | `/actuator/prometheus` |
| Auth | `/metrics` | `/actuator/prometheus` |
| Empleados | `/metrics` | `/actuator/prometheus` |

La ruta pública del Gateway se agregó de forma explícita a su configuración de seguridad. Las operaciones de negocio conservan su autorización JWT/RBAC. El archivo `observability/prometheus/prometheus.yml` utiliza `/metrics` para los tres objetivos Java.

Archivos principales:

- `api-gateway/src/main/java/com/sistema/gateway/MetricsController.java`.
- `services/auth-service/src/main/java/com/microservicios/auth/controller/MetricsController.java`.
- `services/employees-service/src/main/java/com/microservicios/employees/controller/MetricsController.java`.
- Configuración de seguridad del Gateway y configuración de Prometheus.

Para demostrarlo, con el entorno encendido abre `http://localhost:8080/metrics` si utilizas el puerto habitual del Gateway. Debe responder texto Prometheus, con líneas `# HELP` y `# TYPE`. En `http://localhost:9090/targets` comprueba que los siete servicios estén disponibles y que sus rutas de recolección sean `/metrics`. Si cambiaste los puertos en `.env`, utiliza los que correspondan a tu equipo.

## 3. Outbox de Auth

Antes, Auth guardaba el estado de la cuenta y trataba de publicar inmediatamente. Si el envío fallaba, la operación podía quedar confirmada sin su evento.

Ahora el flujo es:

1. Se abre la transacción del caso de uso.
2. Se actualiza la cuenta; en el consumo de eventos se guarda también el identificador procesado.
3. Se inserta el evento saliente en `outbox_evento`.
4. Se confirman juntas las escrituras. Un error en la operación revierte la transacción.
5. El publicador periódico toma eventos pendientes y espera la confirmación de RabbitMQ.
6. Al confirmar, marca la fila como publicada. Si el envío falla, conserva la fila e incrementa los intentos.

Los eventos de activación y recuperación contienen tokens. Por eso el payload pendiente se cifra con AES-GCM, utilizando una clave derivada para este propósito del secreto JWT existente. Después de confirmar el envío se vacía ese payload; se conservan el identificador, el tipo y los datos de seguimiento de la fila. No se imprime el token en los logs del publicador.

El método de recuperación dejó de ser una transacción de solo lectura porque ahora debe guardar su evento. El contrato HTTP de recuperación y los estados de cuenta se conservan.

Archivos principales:

- `services/auth-service/src/main/java/com/microservicios/auth/messaging/AuthEventPublisher.java`.
- `OutboxEvento.java`, `OutboxRepository.java`, `OutboxCipher.java` y `PublicadorDeOutbox.java` en esa misma carpeta.
- Migración `services/auth-service/src/main/resources/db/migration/V2__outbox_de_eventos.sql`.
- `AuthApplication.java`, configuración RabbitMQ y transacción de `AuthService.recuperarPassword`.
- Pruebas en `services/auth-service/src/test/java/com/microservicios/auth/AuthOutboxTest.java`.

## 4. Outbox de Vacaciones

El período y su evento se guardan juntos al:

- Programar vacaciones.
- Reprogramar un período.
- Iniciar vacaciones.
- Finalizar vacaciones.

Si falla el guardado del evento, se revierte también la creación, modificación o transición correspondiente. Si RabbitMQ está caído, la operación de base de datos puede confirmarse con el evento pendiente; el worker lo reintentará después.

Cada evento recibe un UUID al guardarse. Los reintentos reutilizan ese identificador y el mismo contenido. También se conservan las cabeceras de traza y el `correlationId` que acompañaba la operación. El canal de publicación espera la confirmación del broker.

Archivos principales:

- `services/vacations-service/prisma/schema.prisma` y su nueva migración de outbox.
- `services/vacations-service/src/services/vacation.service.ts`.
- `services/vacations-service/src/repositories/vacation.repository.ts`.
- `services/vacations-service/src/messaging/rabbitmq.publisher.ts`.
- `services/vacations-service/src/messaging/outbox.publisher.ts`.
- Arranque y cierre del worker en `src/server.ts`.
- Pruebas en `src/tests/outbox.integration.ts` y `src/tests/vacation.integration.ts`.

Las migraciones se aplican durante el arranque habitual de los contenedores. Se agregan tablas y una columna de traza; no es necesario borrar las bases de datos ni ejecutar `down -v` sobre el entorno habitual.

## 5. Publicadores concurrentes y trazas

`FOR UPDATE SKIP LOCKED` hace que una instancia tome filas pendientes y que otra omita las que ya están bloqueadas. El bloqueo se mantiene hasta terminar la transacción de ese envío o lote.

En Empleados se modificó `OutboxRepository.bloquearPendientes` y su uso en `PublicadorDeOutbox`. Se agregó una prueba de dos transacciones simultáneas en PostgreSQL. Los nuevos publicadores de Auth y Vacaciones utilizan también selección con bloqueo.

Para conservar las trazas a través de la publicación diferida, Auth y Empleados guardan el contexto de origen y lo continúan al enviar. Empleados agrega la columna mediante `V5__traza_del_outbox.sql`. Vacaciones conserva las cabeceras junto con el evento. Esto permite relacionar una petición con sus consumidores aunque se publique después de terminar el HTTP original.

La entrega sigue siendo **al menos una vez**: si el proceso cae después de enviar y antes de confirmar la marca en base de datos, puede haber un reenvío. Los consumidores deben conservar su idempotencia por identificador. No se garantiza un orden global entre instancias ni entrega exactamente una vez.

## 6. Las cuatro colecciones corregidas

| Archivo en `postman/` | Demostración |
|---|---|
| `ciclo-vida-cuenta.postman_collection.json` | Activación, vacaciones, reactivación y retiro durante vacaciones |
| `reto7-sustentacion.postman_collection.json` | Flujo funcional, autorización y retiro del empleado |
| `notificaciones-service.postman_collection.json` | Historial por Gateway con identificadores reales y rechazo de escrituras |
| `observabilidad-reto8.postman_collection.json` | Tráfico, métricas, trazas, logs, dashboard y caída/recuperación |

Cada ejecución prepara identificadores y correos propios. Las fechas representan un intervalo válido con regreso posterior al inicio. Las cuentas de prueba se activan mediante el canal de Auth reservado al administrador; no se buscan tokens en el historial de Notificaciones.

Las esperas comprueban una condición real: por ejemplo, que exista la réplica de Vacaciones o que el login cambie al estado esperado. Si no ocurre dentro del límite, la comprobación falla.

`postman/generar-colecciones-pendientes.py` permite regenerar las cuatro colecciones. El generador anterior de observabilidad utiliza ahora esa definición para regenerar únicamente su colección.

## 7. Preparar la demostración

Necesitas Docker Desktop con el motor disponible, Python 3 y el `.env` correspondiente a tu entorno. Utiliza la configuración de secretos explicada en el [README del proyecto](../README.md) si aún no tienes ese archivo. Conserva los valores de un entorno con datos existentes.

En PowerShell:

```powershell
Set-Location 'C:\Users\ANDRES MANRIQUE\Documents\MICROSERVICIOS\System_HR'
git branch --show-current
docker version
docker compose config --quiet
```

La rama debe ser `main`; Docker debe mostrar Client y Server. La comprobación de Compose debe terminar sin errores.

En este equipo conviene demostrar aplicación y observabilidad con Jenkins/SonarQube detenidos, debido a la memoria disponible. Antes de detener una herramienta, comprueba que no tenga una ejecución activa.

### Receptor local de alertas para esta demostración

Grafana necesita `DISCORD_WEBHOOK_URL` para cargar su punto de contacto. En este equipo estaba vacío y se eligió comprobar las notificaciones con un receptor local temporal. Ese receptor responde a las peticiones y cuenta su recepción; no publica en un canal externo.

Desde la raíz del proyecto, en la misma ventana de PowerShell que usarás para las pruebas:

```powershell
docker compose up -d prometheus
docker run -d --name hr-webhook-prueba --network system_hr_microservices -v "${PWD}:/workspace:ro" python:3.12-alpine python /workspace/scripts/webhook-prueba.py
$env:DISCORD_WEBHOOK_URL = 'http://hr-webhook-prueba:8080/api/webhooks/prueba/local'
```

El primer comando prepara la red de microservicios. El nombre mostrado corresponde a la configuración actual de `main`. La variable se establece solo en esa terminal; no sobrescribe `.env`. Luego ejecuta el comando de la sección siguiente. Si el nombre `hr-webhook-prueba` ya existe, comprueba si es el receptor de un ensayo anterior antes de crear otro.

Para mostrar la recepción durante la prueba:

```powershell
docker logs hr-webhook-prueba
```

Si ya tienes un webhook real configurado en `.env`, puedes usar directamente esa configuración. La comprobación local verifica la ruta de notificación y la alerta de Grafana; no certifica una entrega a Discord.

## 8. Ejecutar las colecciones con un comando

Para construir los servicios, levantar aplicación y observabilidad, ejecutar las cuatro colecciones y demostrar la caída:

```powershell
python scripts/probar-postman-pendientes.py --caida-departamentos
```

Las imágenes de negocio se construyen una a la vez. El comando espera al arranque del entorno y ejecuta Newman dentro de un contenedor temporal. No necesitas instalar Node ni Newman en Windows.

Si ya construiste y arrancaste la versión actual:

```powershell
python scripts/probar-postman-pendientes.py --sin-arranque --caida-departamentos
```

Para ejecutar únicamente las comprobaciones normales, omite `--caida-departamentos`.

La primera ejecución puede descargar imágenes y dependencias. El resumen queda en `reports/pendientes-postman/resumen.json`; contiene resultados y estadísticas, sin credenciales. El estado temporal de las colecciones, incluidos sus tokens, permanece dentro del contenedor de Newman y se elimina al retirarlo.

Código de salida `0` significa que terminaron correctamente las comprobaciones solicitadas. Un código distinto indica un fallo; revisa la aserción antes de repetir.

## 9. Qué hace la demostración de caída

1. Ejecuta las comprobaciones normales y prepara un empleado propio.
2. Detiene únicamente `departments-service` del proyecto Compose seleccionado.
3. Espera que Prometheus observe `up=0` para ese servicio.
4. Espera la alerta de Grafana según los tiempos de evaluación configurados.
5. Comprueba una operación sin caché que debe responder 503 por la dependencia caída.
6. Restaura Departamentos dentro de un bloque `finally`, incluso si alguna prueba falla.
7. Comprueba `up=1` y que una nueva operación de negocio funcione otra vez.

Para mostrarlo en pantalla, deja abiertas la terminal, la página de targets de Prometheus y las alertas de Grafana. La alerta utiliza una espera configurada de un minuto y evaluaciones periódicas; el cambio no es instantáneo.

Si Docker deja de responder durante la restauración, el comando informa el problema y conserva el resultado en el resumen. Revisa el servicio antes de continuar:

```powershell
docker compose ps departments-service
docker compose up -d departments-service
```

## 10. Usar Postman visualmente

1. Importa las colecciones corregidas desde `postman/`.
2. Selecciona un entorno con `gatewayBaseUrl`, `adminUsuario` y `adminPassword` de tu instalación.
3. Para observabilidad agrega las URLs de Prometheus, Zipkin, Loki y Grafana y las variables `grafanaUser` y `grafanaPassword`.
4. Ejecuta completas las tres primeras colecciones mediante Collection Runner para conservar su secuencia de preparación y comprobación.
5. En observabilidad, ejecuta solo **01 Funcionamiento** para una revisión con todos los servicios disponibles. El comando de Python coordina las tres carpetas cuando se solicita la caída.

Las URLs del navegador usan los puertos publicados del equipo. El comando Python usa nombres internos de Docker y la red compartida de microservicios. No hace falta publicar los puertos de los servicios de negocio para las pruebas.

## 11. Validación realizada

Las comprobaciones terminaron con estos resultados:

| Verificación | Resultado |
|---|---|
| Auth, Maven `clean verify` | 35 pruebas; cero fallos, errores u omisiones |
| Empleados, Maven `clean verify` | 81 pruebas; incluye ocho de PostgreSQL real y la selección concurrente de filas; sin omisiones |
| Gateway, Maven `clean verify` | 35 pruebas; incluye acceso público a métricas y las pruebas de seguridad anteriores |
| Vacaciones, pruebas unitarias | 66 correctas; cobertura de líneas del 77,78 % en el alcance unitario de dominio y servicios |
| Vacaciones, integración PostgreSQL/RabbitMQ | Seis correctas: atomicidad, reversión, reintento, identificador y cabeceras conservados, concurrencia y flujo HTTP/scheduler |
| Comando de Postman, restauración ante fallos | Cuatro pruebas correctas del manejo de errores y recuperación |
| Cuatro colecciones contra el sistema completo | 107 aserciones correctas, sin fallos, incluidas las fases de caída y recuperación |
| Suite BDD del proyecto | 39 escenarios correctos, sin errores ni omisiones |
| Construcción | Las siete imágenes de negocio y la imagen BDD se construyeron correctamente |

En observabilidad se comprobaron los siete objetivos disponibles, trazas con los siete servicios, logs estructurados, métricas y latencia, dashboard y punto de contacto. Durante la caída se observaron `up=0`, alerta activa y respuesta 503; tras restaurar, `up=1` y creación de un empleado con HTTP 201. El resumen confirmó la restauración de Departamentos.

También se recibieron peticiones del notificador en el receptor local de pruebas. Esta comprobación no representa una entrega a un canal real de Discord.

Durante esta validación se utilizaron bases de datos y recursos de prueba separados. El entorno habitual se pausó para disponer de memoria y después se restableció con sus contenedores y volúmenes existentes: los 15 contenedores quedaron saludables. Para ejecutar las correcciones en ese entorno hay que construir la versión actual mediante el comando de la sección 8.

Los tests Java se ejecutaron en un espacio Linux de Docker. Esto evitó la demora al escribir cobertura directamente sobre una carpeta de Windows. Los reportes se copiaron después a las carpetas locales de resultados.

Los reportes locales de esta corrección se encuentran en `reports/pendientes/` y `reports/pendientes-postman/`. Esas carpetas están ignoradas por Git. Los archivos fuente de las pruebas permanecen versionados junto al código.

Al terminar una demostración que utilizó el receptor local, detén Grafana antes de retirar su destino de prueba:

```powershell
docker compose stop grafana
docker rm -f hr-webhook-prueba
Remove-Item Env:DISCORD_WEBHOOK_URL
```

Para otro ensayo local, repite la preparación del receptor. Para usar Discord, configura su URL en tu entorno antes de volver a iniciar Grafana.

## 12. Aspectos de operación

- Conserva el secreto JWT mientras existan payloads de Auth pendientes cifrados con él; cambiarlo impide descifrarlos y también invalida sus tokens.
- Los tokens conservan su expiración original. Una entrega muy tardía no amplía su vigencia.
- El historial de Jenkins de otro equipo no se obtiene al clonar Git. Estas comprobaciones producen sus propios resultados locales.
- No muestres `.env`, contraseñas o tokens al compartir pantalla. Para enseñar el outbox basta consultar identificador, tipo, fecha de publicación e intentos.
