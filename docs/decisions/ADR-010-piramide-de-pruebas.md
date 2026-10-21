# ADR-010: Pirámide de pruebas con dobles en la base y Docker solo en la cima

## Estado

Aceptada — 2026-10-21

## Contexto

El sistema está formado por siete servicios en dos lenguajes, dos bases de datos
por familia de servicio y un broker de mensajes. Verificar una regla de negocio
—por ejemplo, que dos períodos de vacaciones que comparten un solo día se
rechazan— admite al menos tres caminos:

1. Llamar a la función de dominio con fechas concretas.
2. Levantar el servicio con su base de datos y hacer un `POST /vacaciones`.
3. Levantar el ecosistema completo y hacer la petición a través del gateway.

Los tres comprueban la misma regla, pero cuestan tiempos muy distintos y fallan
por razones distintas. Si toda la verificación se hace por el camino 3, una regla
de negocio mal escrita y un contenedor que no arrancó producen el mismo síntoma:
un escenario rojo. Y si toda se hace por el camino 1, nunca se comprueba que la
restricción `UNIQUE` exista de verdad en el esquema.

El punto de partida de este reto tenía además un problema concreto: las pruebas
unitarias del `vacations-service` no se podían ejecutar con un `npm test` recién
clonado el repositorio, porque la cadena de imports llegaba hasta
`src/config/db.ts`, que falla de inmediato si no hay `DATABASE_URL`. Una prueba
unitaria que exige una base de datos configurada no es una prueba unitaria.

## Decisión

Tres niveles, cada uno con una responsabilidad distinta y un criterio explícito
de qué **no** le corresponde comprobar:

| Nivel | Qué verifica | Qué necesita para correr | Dónde vive |
|---|---|---|---|
| Unitario | Reglas de negocio y transiciones de estado | Solo el runtime del lenguaje | `services/*/src/test*` |
| Integración | Que el esquema y las restricciones existan en PostgreSQL | Docker (Testcontainers) | `EmployeeRepositoryIntegrationTest` |
| BDD / E2E | Flujos de negocio completos, incluidos los asíncronos | El ecosistema desplegado | `e2e-tests/` |

Reglas que se derivan de esa separación:

- **Ninguna prueba unitaria abre un socket ni una conexión a base de datos.** Las
  dependencias se sustituyen por dobles: `@Mock` con Mockito en Java,
  `RepositorioFake` y `PublisherFake` escritos a mano en TypeScript. Para que el
  `vacations-service` cumpla esto se añadió
  `src/tests/entorno-pruebas.ts`, que se carga con `--import` e inyecta una
  `DATABASE_URL` de marcador antes de que se evalúe cualquier import de la
  aplicación. Prisma no abre la conexión hasta la primera consulta y los dobles
  garantizan que no haya ninguna, de modo que la suite corre sin base de datos;
  si alguna prueba intentara consultar, fallaría al conectar en lugar de
  contaminar una base real.
- **El doble no puede contradecir a producción.** `RepositorioFake` resuelve los
  solapamientos reutilizando `periodosSolapados`, la misma función que usa el
  repositorio real. Un doble con su propia copia de la regla puede quedar en
  verde mientras producción está roto.
- **La lógica se extrae a un módulo puro cuando la prueba no la alcanzaría de
  otro modo.** El predicado de intersección de intervalos vivía dentro de una
  consulta de Prisma, donde solo se podía verificar con una base de datos real.
  Se movió a `src/domain/vacation-rules.ts`; la consulta sigue filtrando por
  empleado y estado —conjunto acotado— y el predicado se aplica en memoria. Así
  la prueba unitaria cubre el código que de verdad corre en producción, no una
  copia.
- **La prueba de integración solo comprueba lo que el nivel inferior no puede.**
  `EmployeeRepositoryIntegrationTest` verifica que Flyway aplicó la migración,
  que las restricciones `UNIQUE` están en el esquema y que el retiro conserva la
  fila. No vuelve a comprobar las reglas de negocio.

La cobertura se mide con exclusiones alineadas en los dos lenguajes: quedan fuera
el arranque, la configuración y los DTO, y quedan dentro el servicio, el
repositorio y el dominio. En `employees-service` hay además un umbral que rompe
el build por debajo del 70 % de líneas.

## Consecuencias

### Positivas

- Un fallo señala su propia causa: 54 pruebas unitarias en 2 s apuntan a una
  regla; un escenario BDD rojo con las unitarias verdes apunta al despliegue o a
  la integración entre servicios.
- La suite unitaria corre en cualquier máquina con solo el runtime instalado, lo
  que la hace apta para una verificación previa al commit y para un agente de CI
  sin Docker.
- Las pruebas de integración se omiten limpiamente donde no hay Docker
  (`@Testcontainers(disabledWithoutDocker = true)`) en lugar de fallar en rojo y
  enterrar los fallos reales.

### Negativas

- Un doble escrito a mano hay que mantenerlo cuando cambia la interfaz del
  repositorio. Se acepta a cambio de poder verificar el servicio sin base de
  datos; el riesgo de divergencia se reduce reutilizando las funciones de dominio
  dentro del doble.
- Extraer el predicado de solapamiento de la consulta SQL traslada el filtrado
  final a memoria. Es admisible porque el conjunto ya viene acotado por empleado
  y estado; con millones de períodos por empleado habría que volver a resolverlo
  en la base de datos y cubrirlo con una prueba de integración.
- Las pruebas de integración y BDD siguen necesitando Docker, así que el
  `vacations-service` no alcanza el 70 % con pruebas unitarias: el repositorio y
  el publicador de AMQP se quedan en la mitad de sus líneas. Se reporta el número
  real (66,87 %) en lugar de excluir esos archivos para maquillarlo.

## Alternativas consideradas

- **Solo pruebas de extremo a extremo.** Es lo que ya hacían las colecciones de
  Postman de los retos anteriores. Verifica el sistema tal como lo ve un cliente,
  pero necesita el ecosistema arriba, tarda minutos y no distingue una regla mal
  escrita de un contenedor caído. Se mantiene como nivel superior, no como único.
- **Levantar PostgreSQL con Testcontainers también en las pruebas unitarias.**
  Daría más realismo, pero cada prueba pasaría de milisegundos a segundos y la
  suite dejaría de poder ejecutarse sin Docker. Se reserva para el único punto
  donde el realismo es el objetivo: el esquema.
- **Inyectar un `PrismaClient` en memoria o un SQLite.** Habría obligado a
  mantener dos dialectos y, al no ser PostgreSQL, no probaría las restricciones
  reales. Se descartó por dar una falsa sensación de integración.
