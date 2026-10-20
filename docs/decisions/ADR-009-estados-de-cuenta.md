# ADR-009: Estado de cuenta como enumeración, no como booleano

## Estado

Aceptada — 2026-10-20

## Contexto

El `auth-service` desactiva y reactiva cuentas a partir de eventos:

- `vacaciones.iniciadas` → desactivación **temporal**
- `vacaciones.finalizadas` → reactivación
- `empleado.retirado` → desactivación **permanente**

Modelar esto con un booleano (`activa: true|false`) produce un error concreto y
fácil de alcanzar: si un empleado es retirado **mientras está de vacaciones**, al
llegar la `fechaFin` el scheduler publica `vacaciones.finalizadas` sobre una
cuenta ya dada de baja. Con un booleano, el manejador no tiene forma de saber si
ese `false` vino de unas vacaciones o de un retiro, y reactivaría la cuenta de un
empleado que ya no pertenece a la empresa.

Hay además un cuarto momento que el booleano tampoco distingue: la cuenta que
acaba de crearse desde `empleado.creado`, que existe pero todavía no tiene
contraseña.

## Decisión

El estado se modela como una enumeración de cuatro valores, persistida como texto
con una restricción `CHECK` en el esquema:

| Estado | Cómo se llega | ¿Login? | ¿Reactivable por `vacaciones.finalizadas`? |
|---|---|---|---|
| `PENDIENTE_ACTIVACION` | `empleado.creado` | No | No |
| `ACTIVA` | `reset-password` o fin de vacaciones | **Sí** | — |
| `SUSPENDIDA_TEMPORAL` | `vacaciones.iniciadas` | No | **Sí** |
| `DESACTIVADA_PERMANENTE` | `empleado.retirado` | No | **No** |

La regla del caso borde queda expresada en el propio modelo, en
`AccountStatus.esReactivablePorVacaciones()`, que devuelve `true` únicamente para
`SUSPENDIDA_TEMPORAL`. El manejador de `vacaciones.finalizadas` consulta ese
método: una cuenta `DESACTIVADA_PERMANENTE` no se toca, y el login del empleado
retirado sigue fallando.

El enunciado sugiere la lista `ACTIVA | SUSPENDIDA_TEMPORAL |
DESACTIVADA_PERMANENTE` como ejemplo. Se añade `PENDIENTE_ACTIVACION` porque
conflactar "nunca activada" con "suspendida por vacaciones" tendría la misma clase
de consecuencia que el problema original: una cuenta recién creada y nunca
activada se volvería `ACTIVA` al terminar unas vacaciones que, además, nunca
tuvo.

## Alternativas consideradas

**Booleano `activa` más un booleano `retirado`.**
Descartada. Dos booleanos representan cuatro combinaciones, de las cuales dos son
imposibles (`activa=true, retirado=true`). Un estado inválido que el tipo permite
es un estado que alguien acabará escribiendo.

**Booleano `activa` más un campo `motivoDesactivacion` de texto libre.**
Descartada. Funciona, pero la invariante pasa a depender de comparar cadenas
("¿era 'RETIRO' o 'retiro'?") en lugar de estar en el tipo.

**Tipo `ENUM` nativo de PostgreSQL.**
Descartada por coste de evolución: añadir un valor a un enum nativo requiere
`ALTER TYPE` y coordinación con el despliegue. `VARCHAR` con `CHECK` da la misma
garantía en base de datos y se modifica con una migración ordinaria.

## Consecuencias

**Positivas**
- El caso borde del retiro durante vacaciones se resuelve por construcción: no
  hay rama condicional que se pueda olvidar.
- Los mensajes de error del login son precisos: el usuario sabe si debe activar su
  cuenta, si está de vacaciones o si fue dada de baja.
- La restricción `CHECK` impide que un estado inventado entre en la tabla, incluso
  si un futuro cambio de código lo intentara.

**Negativas / asumidas**
- Cuatro estados son más que los tres del enunciado; la diferencia queda
  documentada aquí y en el README del servicio para que no parezca arbitraria.
- Añadir un estado nuevo exige una migración que actualice el `CHECK`, no solo un
  valor en el enum de Java. Es el precio de que la base de datos también valide.
- `PENDIENTE_ACTIVACION` y `SUSPENDIDA_TEMPORAL` impiden el login por razones
  distintas pero con el mismo código HTTP (`401`): el detalle va en el mensaje,
  no en el status.
