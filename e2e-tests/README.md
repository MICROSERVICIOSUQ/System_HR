# Suite BDD de extremo a extremo — System_HR

Proyecto independiente que verifica el ecosistema **ya desplegado**, a través del
API Gateway y solo con su API pública. No comparte código ni `pom.xml` con los
microservicios: no importa sus clases, no lee sus bases de datos y no se conecta
a RabbitMQ. Si un escenario pasa, pasa porque el sistema se comporta como el
negocio espera.

```
e2e-tests/
├── .dockerignore                 Archivos excluidos de la imagen de pruebas
├── Dockerfile                    Imagen efímera para la suite en Compose
├── pom.xml                      Dependencias y configuración de Surefire
├── mvnw / mvnw.cmd / .mvn/      Maven Wrapper: no hace falta Maven instalado
└── src/test/
    ├── java/com/microservicios/e2e/
    │   ├── RunCucumberTest.java         Punto de entrada único
    │   ├── steps/                       Step definitions
    │   │   ├── PasosComunes.java        Salud, autenticación, aserciones comunes
    │   │   ├── PasosSeguridad.java      Tokens, cabeceras, RBAC y pertenencia
    │   │   ├── PasosOnboarding.java     Alta, activación y login del empleado
    │   │   └── PasosCicloDeVida.java    Vacaciones y offboarding
    │   └── support/
    │       ├── Configuracion.java       Variables de entorno y parámetros
    │       ├── ContextoEscenario.java   El "World": estado por escenario
    │       ├── ApiCliente.java          Cliente HTTP sobre Rest Assured
    │       └── Espera.java              Polling con reintentos
    └── resources/
        ├── junit-platform.properties    Glue y reportes
        └── features/                    Los .feature en español
```

---

## Cómo ejecutarla

### Requisitos

1. Java 21 (el wrapper descarga Maven solo).
2. El ecosistema arriba y sano:

   ```bash
   docker compose up -d

   docker compose ps
   ```

   Los servicios requeridos deben aparecer `healthy`. El escenario de humo falla
   rápido si no lo están, para no interpretar un sistema caído como una
   regresión funcional.

### Un solo comando

```bash
cd e2e-tests
./mvnw test
```

Si el gateway no está en el puerto 8080 —en esta máquina lo ocupa Apache— se
indica la URL base:

```bash
BASE_URL=http://localhost:8090 ./mvnw test
```

Un escenario fallido devuelve código de salida distinto de cero, que es lo que
detendrá el pipeline del Reto 7.

### Ejecución en Docker Compose

Desde la raíz del repositorio, el perfil `testing` ejecuta la suite como un
contenedor efímero y conserva los tres reportes en `e2e-tests/reports/`:

```powershell
docker compose --profile testing run --build --rm bdd-tests
```

`bdd-tests` espera a que API Gateway, auth, empleados, departamentos, vacaciones
y notificaciones estén saludables. Dentro de la red de Compose usa
`BASE_URL=http://api-gateway:8080`; el `JWT_SECRET` debe estar configurado en el
`.env` de la raíz para que auth y gateway puedan arrancar. El perfil evita
ejecutar la suite automáticamente durante un `docker compose up` normal.

Para arrancar el ecosistema, correr BDD y propagar el código de salida de las
pruebas:

```powershell
docker compose --profile testing up --build --abort-on-container-exit --exit-code-from bdd-tests
```

### Variables de entorno

Todas tienen un valor por defecto de desarrollo, de modo que `./mvnw test`
funcione recién clonado el repositorio. Se leen también como propiedades del
sistema (`-DBASE_URL=...`).

| Variable | Valor por defecto | Para qué |
|---|---|---|
| `BASE_URL` | `http://localhost:8080` | URL del API Gateway |
| `ADMIN_USER` | `admin@empresa.com` | Administrador semilla (`ADMIN_USUARIO` se acepta como alias legado) |
| `ADMIN_PASSWORD` | `Admin12345` | Contraseña del administrador |
| `REGULAR_USER` | `e2e` | Prefijo de correo para cuentas únicas por escenario; acepta una dirección base |
| `REGULAR_PASSWORD` | `Secreta123` | Contraseña que la suite fija a los empleados que crea (`PASSWORD_EMPLEADO` se acepta como alias legado) |
| `POLL_MAX_INTENTOS` | `15` | Reintentos al verificar un efecto asíncrono |
| `POLL_INTERVALO_MS` | `1000` | Espera entre reintentos |

Al ejecutar `bdd-tests` con Compose, `ADMIN_USER` y `ADMIN_PASSWORD` se toman de
`AUTH_SEED_ADMIN_EMAIL` y `AUTH_SEED_ADMIN_PASSWORD`, respectivamente. Para la
ejecución desde el host, las credenciales deben apuntar a la misma cuenta semilla.

---

## Reportes

Se generan en rutas **fijas**, relativas a `e2e-tests/`, porque el pipeline del
Reto 7 apuntará exactamente a ellas:

| Archivo | Para qué sirve |
|---|---|
| `reports/junit.xml` | Resultados máquina-legibles; es lo que leerá Jenkins |
| `reports/cucumber.json` | Reporte estructurado para el plugin de Cucumber |
| `reports/cucumber.html` | Reporte navegable, en lenguaje de negocio |
| `target/surefire-reports/` | Salida estándar de Surefire |

El directorio `reports/` está en `.gitignore`: la ruta es parte del contrato, el
contenido no se versiona.

---

## Las características

39 escenarios en cinco archivos. Se ejecutan en el orden alfabético de sus
nombres, aunque **ningún escenario depende de otro**: cada uno construye sus
propios datos desde los endpoints reales.

| Archivo | Escenarios | Qué cubre |
|---|---|---|
| `00-humo.feature` | 2 | El sistema responde y el administrador semilla se autentica |
| `01-seguridad.feature` | 14 | Token ausente, malformado, con firma falsificada, cabeceras de identidad falsificadas, token de restablecimiento usado como token de acceso, lectura permitida para USER y ADMIN, y denegaciones RBAC |
| `02-onboarding.feature` | 12 | Alta del empleado, aprovisionamiento y notificación por evento, activación, login, y seis casos de rechazo |
| `03-vacaciones.feature` | 7 | Programación, fechas incoherentes, fecha pasada, solapamiento, período de un día, notificación al iniciar, y el ciclo completo de suspensión y reactivación |
| `04-offboarding.feature` | 4 | Desvinculación y bloqueo de cuenta, auditoría, conservación de datos y retiro durante las vacaciones |

### Los dos escenarios que concentran la dificultad

**El ciclo completo de vacaciones** (`03-vacaciones.feature`) combina tres
esperas de naturaleza distinta en un solo escenario: la respuesta sincrónica del
`POST`, la propagación del evento por el broker y el paso del tiempo hasta que el
período arranca.

```gherkin
Escenario: La cuenta se suspende al iniciar las vacaciones y se reactiva al terminar
  Dado que el empleado puede iniciar sesión
  Cuando programo un período de vacaciones que inicia de inmediato
  Y fuerzo la ejecución del scheduler para iniciar el período
  Entonces eventualmente el empleado no puede iniciar sesión
  Y el mensaje de error debe contener "vacaciones"
  Y eventualmente debe existir una notificación "cuenta.desactivada" para el empleado
  Y el motivo de la desactivación debe ser "VACACIONES"
  Cuando fuerzo la finalización del período de vacaciones
  Entonces eventualmente el empleado puede iniciar sesión de nuevo
  Y eventualmente debe existir una notificación "cuenta.activada" para el empleado
```

**El retiro durante las vacaciones** (`04-offboarding.feature`) es el caso borde
que justifica modelar el estado de la cuenta como enumeración y no como booleano
(véase [ADR-009](../docs/decisions/ADR-009-estados-de-cuenta.md)): al llegar la
`fechaFin`, el scheduler publica `vacaciones.finalizadas` sobre una cuenta que ya
fue dada de baja, y el empleado **no** debe recuperar el acceso.

---

## Decisiones de diseño de la suite

### Por qué Cucumber y Rest Assured

El criterio no fue la popularidad sino quién lee el resultado. Un `.feature` en
español lo revisa alguien que no programa, y eso es lo que permite la
conversación de los *Three Amigos*: negocio, desarrollo y pruebas acordando el
comportamiento antes de escribirlo. Rest Assured aporta la parte mecánica —
construir la petición, leer el JSON— sin que esa mecánica se filtre al `.feature`.

Cucumber-JVM además comparte lenguaje y herramientas con dos de los servicios, de
modo que la suite se compila y se reporta con las mismas piezas (Maven, Surefire,
JUnit XML) que ya usa el resto del proyecto.

### El "World" y el aislamiento

`ContextoEscenario` es el estado compartido entre los pasos de un escenario.
PicoContainer construye **una instancia nueva por escenario** y la inyecta en
cada clase de pasos que la declare en su constructor: ningún escenario ve el
token ni la respuesta del anterior.

Lo que el contexto no resuelve es el estado del sistema desplegado: un empleado
creado por un escenario sigue en PostgreSQL cuando empieza el siguiente. Para eso
el contexto genera un sufijo UUID del que derivan
todos los identificadores, así que dos escenarios —o dos ejecuciones de la misma
suite— nunca compiten por la misma fila. Esa es la razón por la que la suite se
puede ejecutar tres veces seguidas sin limpiar nada.

### Esperas

Ningún paso usa una espera fija. Los que empiezan por «eventualmente» reintentan
hasta que la condición se cumple o se agota el plazo, y las condiciones negativas
se confirman varias veces para no confundir un estado transitorio con el
definitivo. El razonamiento completo y la justificación de los parámetros están
en [ADR-011](../docs/decisions/ADR-011-verificacion-de-efectos-asincronos.md).

### Control del tiempo

Las transiciones del período se provocan con los endpoints de disparo manual
`POST /vacaciones/{id}/forzar-inicio` y `/forzar-fin`, que ejecutan la misma
transición y publican el mismo evento que el scheduler. La prueba verifica el
comportamiento del sistema, no la puntualidad del reloj.

### El token de activación

Nunca aparece en una respuesta HTTP. Viaja dentro de `usuario.creado` y el
`notifications-service` lo persiste en el `payload` del envelope, así que la
suite lo recupera con `GET /notifications` y lo extrae de `payload.data.token`.
Que haya que buscarlo ahí es, en sí mismo, la prueba de que el sistema no lo
expone.

---

## Añadir un escenario

1. Escribir el escenario en el `.feature` que le corresponda, en lenguaje de
   negocio: qué hace un actor y qué debe observar, nunca códigos internos ni
   nombres de tabla.
2. Ejecutar `./mvnw test`. Cucumber imprime el esqueleto de los pasos que no
   reconoce.
3. Implementar el paso en la clase de `steps/` por tema, reutilizando
   `ApiCliente` y `Espera`. Los efectos asíncronos van siempre con `Espera`,
   sin esperas fijas.

> Cucumber ignora la palabra clave (`Dado`, `Cuando`, `Entonces`, `Y`) al
> emparejar un paso: registrar el mismo texto con dos anotaciones distintas es
> una definición duplicada y aborta la suite. Un texto, una anotación.
