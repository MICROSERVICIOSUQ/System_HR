# ADR-003: Unicidad en dos capas: consulta previa + restricción `UNIQUE`

## Estado

Aceptada — 2026-09-15

## Contexto

`employees-service` exige que `email` y `numeroEmpleado` sean únicos. Hay dos
formas de garantizarlo y no son excluyentes:

1. **Consultar antes de insertar** (`existsByEmail`, `existsByNumeroEmpleado`) y
   rechazar con un mensaje descriptivo si ya existe.
2. **Restricción `UNIQUE` en el esquema**, de modo que la propia base de datos
   rechace el duplicado al insertar.

La consulta previa, por sí sola, tiene una ventana: dos peticiones con el mismo
email que lleguen casi a la vez pueden **ambas** pasar la comprobación (ninguna ve
a la otra porque ninguna ha insertado aún) y **ambas** insertar. Es una condición
de carrera clásica de *check-then-act*, y con un servicio atendido por varios hilos
—o varias réplicas— no es teórica.

## Decisión

Se implementan **las dos capas**, cada una con un propósito distinto:

**Capa 1 — Consulta previa en `EmployeeService.register`.**
Cubre el caso común (el usuario reintenta un registro que ya hizo, o dos personas
comparten un correo) y permite devolver un mensaje preciso: `El email
'juan.perez@empresa.com' ya está registrado`. Esta capa es la que **produce buenos
mensajes**, no la que **garantiza** la unicidad.

**Capa 2 — Restricciones `UNIQUE` en la migración `V1__create_employees.sql`.**

```sql
email           VARCHAR(255) NOT NULL UNIQUE,
employee_number VARCHAR(255) NOT NULL UNIQUE,
```

Es la única barrera que PostgreSQL hace cumplir de forma atómica: en la carrera
descrita, la segunda inserción falla con violación de unicidad aunque haya pasado
la consulta previa. Esta capa es la que **garantiza** el invariante.

**Traducción del error de la capa 2.** Cuando la restricción salta, JPA lanza
`DataIntegrityViolationException`. El `GlobalExceptionHandler` la captura y responde
`400 Bad Request` con `El email o número de empleado ya está registrado`: el cliente
recibe el mismo código y un mensaje descriptivo, sin distinguir si lo detectó la
consulta o la base. La carrera se resuelve de forma correcta y silenciosa.

## Alternativas consideradas

**Solo consulta previa.**
Descartada. Deja la ventana de carrera abierta y confía en que "no pasará". Con dos
réplicas del servicio detrás de un balanceador, pasaría.

**Solo restricción `UNIQUE`, sin consulta.**
Descartada. Funcionalmente suficiente, pero el mensaje al cliente sería el genérico
de violación de integridad; distinguir si chocó el email o el número obliga a parsear
el nombre de la restricción del error de PostgreSQL, que es frágil y depende del
motor. La consulta previa da ese detalle de forma limpia en el 99 % de los casos.

**Bloqueo pesimista o serialización de transacciones.**
Descartada. Resuelve la carrera, pero a costa de contención en cada registro. La
restricción `UNIQUE` logra lo mismo sin bloquear a nadie.

## Consecuencias

**Positivas**
- El invariante de unicidad no depende del código de la aplicación: aunque un
  refactor futuro elimine la consulta previa por error, la base seguirá
  rechazando duplicados.
- Mensajes descriptivos en el caso común, corrección garantizada en el caso raro.
- Se aplica también al `id` a través de la clave primaria (`PRIMARY KEY` implica
  unicidad).

**Negativas / asumidas**
- Dos lecturas adicionales (`existsBy...`) por cada registro. Despreciable frente
  al costo de la llamada HTTP a `departments-service` que sigue después.
- Las dos capas no coinciden exactamente en sensibilidad a mayúsculas: la consulta
  ignora mayúsculas en el email (`existsByEmailIgnoreCase`) y la restricción `UNIQUE`
  no. La aplicación es más estricta que la base, lo cual es el lado seguro.
- El mensaje de la capa 2 es menos específico ("email o número") porque en ese punto
  no se sabe cuál de los dos chocó sin inspeccionar el error del motor.
