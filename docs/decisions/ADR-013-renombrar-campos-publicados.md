# ADR-013: Renombrar un campo publicado conservando el nombre antiguo como alias

## Estado

Aceptada — 2026-11-08

## Contexto

El modelo canónico del sistema usa nombres en español: `empleadoId`,
`departamentoId`, `numeroEmpleado`, `fechaIngreso`, `fechaRetiro`. Dos campos se
salían de esa convención y la auditoría pidió corregirlos:

- `profiles-service` llamaba `employeeId` a la clave de negocio del perfil.
- `employees-service` expone `employeeId` como alias de su `id`.

El problema no es estético. Un nombre fuera de convención obliga a traducir en
cada frontera, y esa traducción es el sitio donde aparecen los errores: alguien
lee `employeeId` donde el resto del sistema escribe `empleadoId`, el campo llega
vacío y el fallo no se nota hasta que un dato aparece en blanco en producción.

Pero el nombre estaba **publicado**. `employeeId` formaba parte de la respuesta
de `GET /perfiles/{id}`, aparecía en el `sortBy` del listado y lo usaban las
colecciones de Postman. Renombrarlo y nada más es una ruptura de contrato: todo
cliente que lo lea recibe `undefined` sin ningún error que lo avise.

Y había precedente en el propio repositorio: `employees-service` ya devuelve `id`
y `employeeId` apuntando al mismo valor, precisamente porque en su momento se
quiso cambiar el nombre sin romper a quien usaba el anterior.

## Decisión

Al renombrar un campo que ya forma parte de una respuesta publicada:

1. **La columna y el código interno adoptan el nombre canónico.** En la base de
   datos se renombra con `ALTER TABLE ... RENAME COLUMN`, que conserva los datos
   y los índices.
2. **La respuesta incluye los dos nombres**, apuntando al mismo valor. El
   canónico es el nuevo; el antiguo queda marcado como `@deprecated` en el
   código.
3. **El alias se añade en la frontera HTTP**, no en el modelo de datos. Un
   mapeador explícito (`presentarPerfil`) construye la respuesta, de modo que el
   alias no se filtra al repositorio ni a las consultas.
4. **Los parámetros de entrada aceptan solo el nombre canónico.** Un alias de
   lectura no cuesta nada; uno de escritura duplica los caminos de validación y
   abre la pregunta de qué hacer si llegan los dos con valores distintos.
5. **El alias se retira cuando ningún cliente lo consulte**, no antes y no en la
   misma entrega.

Aplicado al caso concreto:

| | Antes | Ahora |
|---|---|---|
| Columna | `"employeeId"` | `"empleadoId"` |
| Índice | `EmployeeProfile_employeeId_key` | `EmployeeProfile_empleadoId_key` |
| Respuesta | `employeeId` | `empleadoId` + `employeeId` (alias) |
| `sortBy` | `employeeId` | `empleadoId` |

## Consecuencias

### Positivas

- El renombrado se puede desplegar sin coordinar con ningún cliente. La suite
  BDD (39 escenarios) y las cinco colecciones de Postman pasaron sin tocar una
  sola aserción relacionada con el campo.
- El nombre canónico queda disponible de inmediato para el código nuevo, en
  lugar de esperar a una ventana de migración que suele no llegar nunca.
- La deuda queda registrada en un sitio visible —un campo marcado
  `@deprecated`— y no en la memoria de quien hizo el cambio.

### Negativas

- **La respuesta transporta información duplicada.** Dos claves con el mismo
  valor en cada perfil de cada listado. Es poco, pero crece con el tamaño de la
  página.
- **Un cliente nuevo puede elegir el campo equivocado.** El alias está
  documentado como obsoleto en el código y en el OpenAPI, pero nada impide
  usarlo. El riesgo real es que el alias se vuelva permanente por inercia: nadie
  comprueba si alguien lo usa y nadie se atreve a quitarlo.
- **Hay que acordarse de retirarlo.** Sin una fecha o un disparador concreto, el
  paso 5 de la decisión no ocurre. Mitigación: el alias se revisa al cerrar cada
  reto, y se retira cuando una búsqueda en el repositorio y en las colecciones no
  lo encuentre.

## Alternativas consideradas

- **Renombrar sin alias.** Es lo que pedía literalmente la auditoría y lo más
  limpio en el código. Se descartó porque rompe en silencio: un cliente que lea
  `employeeId` recibe `undefined`, no un error, así que el fallo aparece lejos de
  su causa. En un sistema de un solo equipo el coste sería asumible; aquí hay
  cuatro desarrolladores trabajando en paralelo sobre servicios que se consumen
  entre sí.
- **Versionar la API (`/v2/perfiles`).** La solución correcta para un cambio de
  contrato grande, pero desproporcionada para renombrar un campo: obligaría a
  mantener dos rutas, dos contratos OpenAPI y dos juegos de pruebas por un alias
  de una línea.
- **Devolver el alias solo si el cliente lo pide** (por cabecera o por un
  parámetro `?compat=true`). Más limpio en la respuesta por omisión, pero añade
  una rama de comportamiento que hay que probar en las dos formas, y ningún
  cliente existente sabría pedirla.
- **Mantener `employeeId` y renunciar al cambio.** Evita todo el trabajo, pero
  deja la inconsistencia que obliga a traducir en cada frontera, que es el
  problema que se quería resolver.
