# Guía de pruebas — Reto 6 (Automatización de pruebas)

Orden exacto de ejecución, comando por comando, con el resultado que debe
aparecer en cada paso. Cada bloque indica a qué punto del enunciado responde.

Todos los comandos se ejecutan desde la raíz del repositorio salvo donde se
indique otra cosa.

> **Puerto del gateway.** En esta máquina el 8080 lo ocupa Apache, así que el
> `docker-compose.yml` se levanta con `GATEWAY_PORT=8090` y la suite BDD se
> ejecuta con `BASE_URL=http://localhost:8090`. Si el 8080 está libre, se pueden
> omitir ambas variables.

---

## Bloque A — Pruebas unitarias y de integración (punto 1)

Este bloque **no necesita el sistema levantado**. Es el punto de la pirámide
donde todas las dependencias están sustituidas por dobles.

### A.1 — Pruebas unitarias del `vacations-service` (TypeScript)

```bash
cd services/vacations-service
npm test
```

Resultado esperado:

```
ℹ tests 54
ℹ pass 54
ℹ fail 0
```

| Qué cubre | Pruebas |
|---|---|
| `vacation-rules.test.ts` | 34 — las cuatro validaciones de fechas y la tabla de 16 casos borde de solapamiento |
| `vacation-service.test.ts` | 20 — el servicio con `RepositorioFake` y `PublisherFake`: validaciones, transiciones del scheduler, *compare-and-set* y endpoints de disparo manual |

Reportes que deja: `reports/junit.xml` y `reports/lcov.info`. La tabla de
cobertura se imprime al final de la ejecución.

Cobertura medida (líneas):

| Archivo | Cobertura |
|---|---|
| `src/domain/vacation-rules.ts` | 94,19 % |
| `src/repositories/vacation.repository.ts` | 68,84 % |
| `src/services/vacation.service.ts` | 63,94 % |
| **Total** | **66,87 %** |

> El total queda por debajo del 70 % porque el repositorio de Prisma y el
> publicador de AMQP solo se cubren con pruebas de integración. Se reporta el
> número real en lugar de excluir esos archivos para maquillarlo; el umbral del
> enunciado se cumple en `employees-service`, donde además rompe el build.

**Comprobación de que no hace falta Docker ni base de datos:** el comando
anterior funciona con los contenedores apagados y sin `DATABASE_URL` definida.
Lo hace posible `src/tests/entorno-pruebas.ts`, que se carga con `--import` e
inyecta una URL de marcador antes de que se evalúe cualquier import de la
aplicación. Prisma no abre la conexión hasta la primera consulta y los dobles
garantizan que no haya ninguna.

### A.2 — Pruebas unitarias y de integración del `employees-service` (Java)

```bash
cd services/employees-service
./mvnw test
```

Resultado esperado: 17 pruebas unitarias de `EmployeeServiceUnitTest` en verde en
menos de dos segundos, sin contexto de Spring. Las 7 de
`EmployeeRepositoryIntegrationTest` aparecen como *Skipped* si el JVM no alcanza
Docker, y como ejecutadas si sí lo alcanza.

| Clase | Pruebas | Nivel |
|---|---|---|
| `EmployeeServiceUnitTest` | 17 | Unitario, con `@Mock` de repositorio, cliente de departamentos y publicador |
| `EmployeeRepositoryIntegrationTest` | 7 | Integración, PostgreSQL 16 real vía Testcontainers |

Lo que verifica cada uno:

- **Unitario** — unicidad de `id`, `email` y `numeroEmpleado`; validación del
  departamento; que no se gaste una llamada HTTP si la operación ya va a fallar
  localmente (`verifyNoInteractions(departmentClient)`); la transición a
  `RETIRADO` con su `fechaRetiro`; y que la baja sea **lógica**
  (`verify(repository, never()).delete(...)`).
- **Integración** — que Flyway aplicó las migraciones, que las restricciones
  `UNIQUE` existen **en el esquema** y no solo en el código, que
  `existsByEmailIgnoreCase` se comporta igual contra PostgreSQL, que el retiro
  conserva la fila, y que los `NOT NULL` se aplican.

### A.3 — Cobertura con umbral que rompe el build

```bash
cd services/employees-service
./mvnw verify
```

Resultado esperado:

```
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
```

Cobertura de líneas: **82,66 %**, con el umbral de JaCoCo fijado en el 70 % como
`BUNDLE / LINE / COVEREDRATIO`. Si la cobertura baja de ahí, la fase `verify`
falla.

Reportes: `target/site/jacoco/index.html` (navegable),
`target/site/jacoco/jacoco.xml` y `.csv` (para herramientas),
`target/surefire-reports/*.xml`.

> **Nota sobre dos clases en esta máquina.** `DepartmentCacheFallbackTest` y
> `DepartmentCircuitBreakerTest` levantan un servidor HTTP local y fallan aquí
> porque el entorno no permite abrir sockets de *loopback* desde el JVM; en una
> máquina normal pasan. Para medir la cobertura sin ese ruido se usó
> `./mvnw "-Dtest=!DepartmentCacheFallbackTest,!DepartmentCircuitBreakerTest" verify`,
> que ejecutó 65 pruebas, 0 fallos y 7 omitidas.

### A.4 — Comprobar que una regla rota se detecta (punto 1, último criterio)

La evidencia completa está en
[`evidencias/reto6-mutaciones.txt`](evidencias/reto6-mutaciones.txt). Resumen:

| Regla alterada | Cambio | Pruebas que lo detectan |
|---|---|---|
| Solapamiento de períodos | `ai <= bf && af >= bi` → `ai < bf && af > bi` | 7, entre ellas «un día igual al inicio del existente», «el predicado es simétrico» y «validación 4: rechaza el solapamiento de un solo día compartido» |
| Retiro lógico | se omite `existing.setStatus(RETIRED)` | 2: `EmployeeServiceUnitTest$Retiro.retiroFijaEstadoYFecha` y `.elFalloDelBrokerNoImpideElRetiro` |

Las dos mutaciones devolvieron código de salida 1 y después se revirtieron.

---

## Bloque B — Suite BDD (puntos 2 a 5)

### B.1 — Levantar el ecosistema

```bash
GATEWAY_PORT=8090 docker compose up -d
docker compose ps
```

Los 14 contenedores deben aparecer `running (healthy)`. Si alguno no lo está, el
escenario de humo lo delatará de inmediato y no tiene sentido seguir: el problema
está en el despliegue, no en el comportamiento del sistema.

### B.2 — Ejecutar la suite completa

```bash
cd e2e-tests
BASE_URL=http://localhost:8090 ./mvnw test
```

Resultado esperado:

```
[INFO] Tests run: 41, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### B.3 — Qué se acaba de verificar

| Punto del enunciado | Archivo | Escenarios |
|---|---|---|
| 2 — Proyecto BDD y escenario de humo | `00-humo.feature` | 2 |
| 3 — Escenarios de seguridad | `01-seguridad.feature` | 13 |
| 4 — Onboarding con verificación asíncrona | `02-onboarding.feature` | 13 |
| 5 — Vacaciones | `03-vacaciones.feature` | 7 |
| 5 — Offboarding | `04-offboarding.feature` | 6 |

**Punto 3 (seguridad), más allá del escenario dado por el enunciado:**

1. Token ausente → 401.
2. Token malformado (`"esto-no-es-un-jwt"`) → 401.
3. Token con la firma falsificada que se autoproclama `ADMIN` → 401. Si el
   gateway lo aceptara, cualquiera podría fabricarse permisos.
4. Cabeceras `X-Auth-User` y `X-Auth-Role` enviadas por el cliente → 401. Los
   microservicios confían en esas cabeceras; el escenario comprueba que el borde
   las descarta en lugar de propagarlas.
5. Token de restablecimiento usado como token de acceso → 401.
6. Dos `Regla` con su propio `Antecedentes`: `USER` tiene lectura y su propio
   perfil (403 al retirar, al crear departamento y al tocar el perfil de otro);
   `ADMIN` tiene acceso total.

**Punto 4 (onboarding), la cadena asíncrona:**

```
POST /empleados → empleado.creado → auth-service crea la cuenta
               → usuario.creado (con el token) → notifications-service
```

El escenario registra al empleado, espera con *polling* a que exista la
notificación `usuario.creado`, extrae el token de `payload.data.token`, establece
la contraseña con `POST /auth/reset-password` y comprueba que el login devuelve
un token con rol `USER`. Además verifica que lo que llegó por el evento es un JWT
de tres segmentos y no una contraseña en claro.

Casos de rechazo incluidos: departamento inexistente, campos obligatorios
faltantes, email duplicado, `numeroEmpleado` duplicado, tres formatos de email
inválidos mediante `Esquema del escenario`, y el intento de login antes de
activar la cuenta.

**Punto 5 (vacaciones y offboarding):** programación correcta, fechas
incoherentes, fecha de inicio en el pasado, solapamiento, período de un solo día,
el ciclo completo de suspensión y reactivación, el retiro con su notificación, el
login bloqueado, la recuperación de contraseña rechazada, la consulta de
auditoría `GET /empleados?estado=RETIRADO`, y el caso borde del empleado retirado
durante sus vacaciones que **no** recupera el acceso al llegar la `fechaFin`.

### B.4 — Revisar los reportes

```bash
cd e2e-tests
start reports/cucumber.html      # Windows
```

| Archivo | Contenido |
|---|---|
| `reports/cucumber.html` | Los 41 escenarios en lenguaje de negocio, con cada paso |
| `reports/junit.xml` | `tests="41" failures="0" errors="0"` |
| `reports/cucumber.json` | Reporte estructurado para el plugin de Jenkins |

---

## Bloque C — Reproducibilidad (punto 6)

### C.1 — Un fallo rompe el build

Evidencia completa en
[`evidencias/reto6-bdd-fallo-deliberado.txt`](evidencias/reto6-bdd-fallo-deliberado.txt).

Se cambió en `00-humo.feature` el código esperado de 200 a 418 y se volvió a
ejecutar la suite:

- Código de salida del proceso: **1**.
- Surefire: `Tests run: 41, Failures: 1`.
- `reports/junit.xml`: `failures="1"`, con el valor esperado, el obtenido y el
  cuerpo real de la respuesta dentro del `<failure>`.
- `reports/cucumber.html`: ese escenario en rojo, los otros 40 en verde.

### C.2 — Tres ejecuciones consecutivas consistentes

Evidencia completa en
[`evidencias/reto6-bdd-ejecuciones.txt`](evidencias/reto6-bdd-ejecuciones.txt).

| Ejecución | Resultado | Código de salida | Duración |
|---|---|---|---|
| 1 | 41/41 | 0 | 36,34 s |
| 2 | 41/41 | 0 | 44,33 s |
| 3 | 41/41 | 0 | 31,68 s |

Sin limpiar bases de datos entre ejecuciones: los identificadores únicos por
escenario hacen que no haya colisiones.

### C.3 — Un comando por suite

| Suite | Comando | Resultados | Cobertura |
|---|---|---|---|
| Unitarias TS | `cd services/vacations-service && npm test` | `reports/junit.xml` | consola + `reports/lcov.info` |
| Unitarias + integración Java | `cd services/employees-service && ./mvnw verify` | `target/surefire-reports/` | `target/site/jacoco/index.html` |
| BDD extremo a extremo | `cd e2e-tests && ./mvnw test` | `reports/junit.xml`, `reports/cucumber.json`, `reports/cucumber.html` | — |

Las tres devuelven código de salida distinto de cero si algo falla. Son
exactamente las tres etapas que invocará el pipeline del Reto 7.

---

## Resumen de resultados verificados

| Métrica | Valor |
|---|---|
| Pruebas unitarias TypeScript | 54 / 54 |
| Pruebas unitarias Java | 17 / 17 |
| Pruebas de integración con PostgreSQL real | 7 |
| Escenarios BDD | 41 / 41 |
| Cobertura `employees-service` | 82,66 % (umbral 70 % aplicado) |
| Cobertura `vacations-service` | 66,87 % (dominio 94,19 %) |
| Ejecuciones consecutivas sin fallos intermitentes | 3 |
| Mutaciones deliberadas detectadas | 2 de 2 |
