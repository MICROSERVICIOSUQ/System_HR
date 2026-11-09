# Instalar y arrancar System HR en otro computador

Esta guía corresponde a la rama valentina con main integrado y los cambios del desarrollador 3. Los correos son simulados. Go, Node, Java, PostgreSQL y RabbitMQ se ejecutan dentro de Docker; no tiene que instalarlos por separado en Windows.

## 1. Llevar la versión correcta del código

Los cambios deben estar publicados en la rama remota antes de clonarlos en el otro computador. Desde el computador donde se hicieron los cambios, comprobar:

```powershell
git branch --show-current
git status
git log -1 --oneline
```

La rama debe ser `valentina`. Cuando esté listo para publicarla:

```powershell
git push origin valentina
```

Si Git solicita autenticación, hágala en su propio equipo con su cuenta autorizada. Esta guía no requiere compartir contraseñas o tokens por el chat. Si todavía no hizo push, clonar GitHub no traerá los cambios locales. Si Git rechaza el push porque hay cambios nuevos en el remoto, no use force: integre esos cambios antes de publicar.

Alternativamente puede copiar la carpeta System_HR al otro computador, excluyendo .env, secrets, node_modules, dist y reports. Conserve toda la estructura del repositorio; copiar solo los dos microservicios no alcanza para ejecutar el flujo completo.

## 2. Preparar Windows y Docker

Instale Git si usará clonación y Docker Desktop para contenedores Linux. En la instalación de Docker puede usar la opción por usuario y WSL 2. Si aparece que falta WSL, abra PowerShell como administrador:

```powershell
wsl --install
```

Reinicie Windows cuando termine. Abra Docker Desktop y espere a que arranque el motor. El inicio de sesión en Docker no es necesario para este proyecto si la instalación ofrece Omitir/Skip.

Abra una nueva PowerShell normal:

```powershell
git --version
docker version
docker compose version
```

Docker debe mostrar Client y Server. Un error de conexión o únicamente Client significa que el motor no está listo. La primera puesta en marcha puede tardar varios minutos.

## 3. Clonar valentina

Ubíquese en la carpeta donde guardará el proyecto:

```powershell
git clone --branch valentina --single-branch https://github.com/MICROSERVICIOSUQ/System_HR.git
Set-Location System_HR
git branch --show-current
git log -1 --oneline
```

Compare el último commit con el que publicó desde el computador original. Si ya tenía un clon, use `git switch valentina` y `git pull --ff-only origin valentina` con su trabajo local guardado. Si Git indica cambios locales o divergencia, resuélvalos antes de continuar; no use reset para descartarlos.

## 4. Crear .env solo en una instalación nueva

No copie las credenciales del otro computador. Si ya tiene un .env válido, consérvelo y omita este bloque. Para una instalación nueva, desde la raíz:

```powershell
Copy-Item .env.example .env
$taskText = Get-Content -Raw -Encoding UTF8 .env
$taskJwt = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
$taskAdmin = 'Admin1-' + [Guid]::NewGuid().ToString('N')
$taskGrafana = 'Grafana1-' + [Guid]::NewGuid().ToString('N')
$taskText = $taskText -replace '(?m)^JWT_SECRET=.*$', "JWT_SECRET=$taskJwt"
$taskText = $taskText -replace '(?m)^AUTH_SEED_ADMIN_PASSWORD=.*$', "AUTH_SEED_ADMIN_PASSWORD=$taskAdmin"
$taskText = $taskText -replace '(?m)^GRAFANA_PASSWORD=.*$', "GRAFANA_PASSWORD=$taskGrafana"
[IO.File]::WriteAllText((Join-Path $PWD '.env'), $taskText)
docker run --rm -v "${PWD}:/workspace" -w /workspace python:3.12-alpine python ci/preparar-entorno.py
docker compose config --quiet
```

El preparador completa los secretos de Jenkins/SonarQube que falten. No imprime sus valores. El usuario ADMIN y su contraseña se consultan localmente en .env (`AUTH_SEED_ADMIN_EMAIL` y `AUTH_SEED_ADMIN_PASSWORD`). Grafana usa `GRAFANA_USER` / `GRAFANA_PASSWORD`. No muestre ese archivo durante la sustentación ni lo suba a Git.

La semilla de ADMIN se aplica al crear la base de datos. Cambiar su contraseña en .env después no cambia automáticamente una cuenta ya existente en el volumen.

## 5. Arrancar primero el grupo necesario para tu parte

```powershell
docker compose up --build -d auth-service departments-service employees-service profiles-service notifications-service vacations-service api-gateway zipkin
docker compose ps
```

Compose arranca también las bases de datos y RabbitMQ por sus dependencias. Espere a que los servicios estén healthy. La primera vez descarga imágenes y dependencias; después reutiliza las imágenes y la caché.

Compruebe:

```powershell
docker compose exec notifications-service curl -fsS http://localhost:8082/health
docker compose exec vacations-service curl -fsS http://localhost:8084/health
```

Ambos deben responder `UP`, con database y messageBroker `UP`. Los puertos son los internos por defecto; si los cambió en .env, use sus valores.

## 6. Ejecutar las pruebas de tu parte

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\probar-desarrollador3.ps1
```

Este comando usa un entorno de pruebas separado, con sus propias bases y broker. No modifica los datos del sistema principal. Al terminar debe indicar que las pruebas de los dos servicios aprobaron. Las instrucciones de demostración y sus resultados esperados están en `GUIA-DESARROLLADOR3-SUSTENTACION.md`; su sección 9 explica Postman, incluidos los dos archivos JSON para importar.

## 7. Herramientas del resto del proyecto

El stack de main ya está definido. Para las herramientas de observabilidad:

```powershell
docker compose up -d prometheus loki promtail grafana
```

| Herramienta | Dirección predeterminada |
|---|---|
| API Gateway | http://localhost:8080 |
| Swagger agregado existente | http://localhost:8080/swagger-ui.html |
| Grafana | http://localhost:3000 |
| Prometheus | http://localhost:9090 |
| Zipkin | http://localhost:9411 |
| Jenkins | http://localhost:9091 |
| SonarQube | http://localhost:9000 |

Jenkins quedó en 9091 al integrar las ramas, para no ocupar el puerto 9090 de Prometheus. El acceso agregado a Swagger de tus dos servicios y la actualización de BDD/pipelines siguen detallados en la guía del desarrollador 4.

Para arrancar absolutamente todos los servicios definidos, incluidos Jenkins y SonarQube:

```powershell
docker compose up --build -d
```

Ese comando consume más recursos que el grupo de negocio. Si el computador tiene memoria limitada, ejecute primero el grupo del paso 5 y añada las herramientas necesarias para la demostración. No es necesario ejecutar los pipelines de CI para probar los endpoints de Vacaciones y Notificaciones.

## 8. Detener y volver a trabajar

```powershell
docker compose down
docker compose up -d
```

Down conserva los volúmenes. No añada `-v` al sistema principal: elimina los datos. Después de cambiar código o plantillas reconstruya con `docker compose up --build -d`.

## 9. Si algo falla

```powershell
docker compose ps
docker compose logs --tail=100 notifications-service vacations-service
docker compose logs --tail=100 auth-service employees-service departments-service api-gateway rabbitmq
```

- RabbitMQ no disponible durante el primer arranque: compruebe que haya terminado de inicializarse y aparezca healthy; después repita el comando up, sin borrar sus datos.
- Error de conexión al motor: abrir Docker Desktop y esperar; revisar WSL si Docker lo solicita.
- Variable obligatoria ausente: completar .env y ejecutar nuevamente el preparador y config --quiet.
- Puerto ocupado: revisar los valores de .env antes de recrear los contenedores.
- Empleado inexistente en Vacaciones inmediatamente después de crearlo: esperar a su réplica por eventos; el script de demostración ya hace polling.
- Login ADMIN falla con volúmenes existentes: usar las credenciales con las que se creó la cuenta; no borrar volúmenes para solucionar una contraseña.
- BDD falla esperando un token en el historial: requiere el ajuste del desarrollador 4; el historial ya no expone tokens.

Para saber qué se verificó realmente y qué sigue pendiente consulte `VERIFICACION-DESARROLLADOR3.md`.
