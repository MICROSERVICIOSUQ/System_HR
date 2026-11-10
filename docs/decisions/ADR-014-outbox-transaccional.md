# ADR-014: Publicar los eventos mediante un outbox transaccional

## Estado

Aceptada — 2026-11-09

## Contexto

Hasta ahora `employees-service` publicaba así:

```java
Employee saved = repository.save(employee);

try {
    eventPublisher.publishEmployeeCreated(saved);
} catch (Exception ex) {
    // El error queda registrado en los logs por el publisher.
    // Para mantener la consistencia de la respuesta HTTP, no se altera la
    // respuesta de persistencia.
}
```

Son **dos operaciones contra dos sistemas distintos**, sin nada que las ate. La
primera es una escritura en PostgreSQL; la segunda, un mensaje a RabbitMQ. Si el
broker no está disponible en ese instante, la primera se confirma y la segunda no
ocurre nunca: el `catch` escribe una línea en el log y la petición responde 201.

Las consecuencias no se ven en el momento. `empleado.creado` es el evento que
dispara toda la cadena de alta:

- `auth-service` crea la cuenta y emite el token de activación,
- `profiles-service` crea el perfil del read model,
- `vacations-service` crea el saldo de vacaciones,
- `notifications-service` compone el correo de bienvenida.

Un evento perdido deja un empleado que existe en la base de datos de empleados y
**en ninguna otra parte**: no puede iniciar sesión porque no tiene cuenta, no
aparece en los perfiles y no tiene saldo de vacaciones. Nadie reintenta, porque
nadie sabe que hacía falta. El síntoma aparece días después, con el empleado
pidiendo acceso, y la reparación es manual.

El informe de auditoría lo registró como faltante de `employees-service`
—«3. Outbox en RabbitMQ»— y en los vacíos del Dev 1:

> Comprobar que al retirar un empleado (`DELETE /empleados/{id}`), el evento
> `empleado.retirado` no se pierda si RabbitMQ cae (*fire-and-forget* actual).
> Diseñar la tabla o mecanismo Outbox.

El `catch` vacío no era descuido: estaba puesto a propósito para que un fallo del
broker no convirtiera un alta correcta en un error HTTP. El problema es que, al
elegir entre «pierdo el evento» y «fallo la petición», ninguna de las dos es
buena, y el patrón Outbox evita tener que elegir.

## Decisión

El evento se escribe en una tabla `outbox_evento` **en la misma transacción** que
el cambio de negocio, y un publicador periódico la vacía contra el broker.

```java
@Transactional
public Employee register(Employee employee) {
    ...
    Employee saved = repository.save(employee);
    eventPublisher.publishEmployeeCreated(saved);   // escribe en outbox_evento
    return saved;
}
```

Lo esencial: **la tabla del outbox vive en la misma base de datos que los
empleados**. Por eso basta una transacción local y no hace falta una transacción
distribuida ni un coordinador XA. O se confirman el empleado y su evento, o no se
confirma ninguno de los dos.

### Las piezas

| Pieza | Responsabilidad |
|---|---|
| `outbox_evento` (tabla, migración `V4`) | Guarda el sobre serializado, con `publicado_en` nulo mientras espera |
| `EmployeeEventPublisher` | Escribe en la tabla, dentro de la transacción de quien llama |
| `PublicadorDeOutbox` | `@Scheduled` cada 5 s: lee lo pendiente, publica y marca |
| `OutboxRepository` | Consulta lo pendiente, por antigüedad y con tope |

### Lo que garantiza

**Entrega al menos una vez.** Un corte entre el envío y la marca de publicado
reenvía el evento. No se intenta garantizar exactamente una vez porque **no hace
falta**: los consumidores ya descartan los repetidos por `eventId` (ADR-007).
Esa decisión previa es la que permite que este patrón sea simple; sin
idempotencia en el consumidor, un outbox genera duplicados que sí causarían daño.

**Orden por antigüedad.** El publicador procesa por `creado_en` ascendente, que
es el orden en el que ocurrieron los hechos. No es un orden total estricto —dos
instancias del servicio podrían intercalarse— pero sí respeta la secuencia de un
mismo empleado en el caso de una sola instancia, que es el despliegue actual.

### Cinco decisiones de detalle

1. **Cada evento se marca por separado, no el lote.** Con una sola transacción
   para los cincuenta, un fallo en el tercero deshacía las marcas de los dos
   primeros y los reenviaba. Multiplicar duplicados sin necesidad es gratuito y
   evitable.

2. **Un fallo del broker corta el ciclo; un fallo del evento no.** Si no hay
   conexión, insistir con los 49 restantes solo llena el log. Pero un sobre que
   no se puede deserializar fallaría siempre, y cortar por él atascaría a todos
   los que vienen detrás, así que se salta y se sigue. La distinción se hace por
   el tipo de la excepción (`AmqpException`, `IOException`, `ConnectException`).

3. **Tope de eventos por ciclo (50).** Tras una caída larga puede haber miles
   pendientes. Sin tope, un ciclo intentaría enviarlos todos y mantendría la
   transacción abierta demasiado tiempo. Con tope se vacía en varios ciclos y el
   servicio sigue atendiendo peticiones.

4. **`fixedDelay` y no `fixedRate`.** La espera cuenta desde que termina el ciclo
   anterior, así que un broker lento no encadena ejecuciones solapadas.

5. **Se guarda el sobre serializado, no los datos del empleado.** Al publicar se
   deserializa y se envía con el mismo converter que antes, de modo que lo que
   llega al broker es byte a byte lo que llegaba con la publicación directa. El
   outbox cambia *cuándo* se envía, no *qué*. Si se guardaran los datos y el
   sobre se construyera al publicar, el `occurredAt` del evento sería el del
   envío y no el del hecho.

### Lo que cambió en el contrato de las operaciones

Antes, un fallo al publicar no afectaba a la respuesta. Ahora **un fallo al
encolar deshace la transacción** y el alta falla. Puede parecer un paso atrás y
es lo contrario: encolar es una escritura en la misma base de datos que ya se
está usando, así que solo falla si la base falla —y entonces el alta tampoco se
habría guardado— o si el sobre no se puede serializar, que es un error de
programación. El caso que antes rompía las cosas, el broker caído, ya no ocurre
en esta operación: el servicio ya no habla con el broker al atender la petición.

Dos pruebas unitarias afirmaban el comportamiento anterior
(`elFalloDelBrokerNoRompeElRegistro`, `elFalloDelBrokerNoImpideElRetiro`) y se
invirtieron: ahora comprueban que un evento que no se puede encolar impide
confirmar el cambio.

## Consecuencias

### Positivas

- **Un evento no se pierde por una caída del broker.** Verificado de extremo a
  extremo: con RabbitMQ detenido, el alta responde 201, el evento queda pendiente
  con su motivo (`UnknownHostException: rabbitmq`), y al volver el broker se
  publica solo y el perfil del empleado aparece sin intervención.
- **La tabla es el diagnóstico.** `SELECT * FROM outbox_evento WHERE publicado_en
  IS NULL` responde «qué eventos no han salido», con sus intentos y el último
  error. Antes esa pregunta solo se podía contestar leyendo logs.
- **El alta deja de depender del broker.** La latencia de la petición ya no
  incluye un viaje a RabbitMQ, y un broker lento no ralentiza la API.

### Negativas

- **Latencia añadida a la entrega.** El evento sale hasta 5 s después del cambio,
  no en el mismo instante. Para esta cadena —crear una cuenta, un perfil, un
  saldo— es irrelevante; para un caso que necesitara reacción inmediata habría
  que bajar el intervalo o despertar el publicador al confirmar la transacción.

  Donde sí se nota es en las pruebas: la suite BDD pasó de **38 s a 115 s** con
  los mismos 39 escenarios en verde, porque casi todos registran un empleado y
  esperan el efecto de su evento. Es el precio visible de la garantía, y el
  intervalo es configurable (`outbox.intervalo-ms`) si alguna vez molesta.
- **La tabla crece.** No hay purga: los eventos publicados se conservan, lo que
  resulta útil como auditoría pero no es sostenible indefinidamente. Falta un
  borrado de los publicados con más de N días.
- **Una sola instancia.** Dos réplicas leerían las mismas filas pendientes y las
  publicarían las dos. Los consumidores lo absorben por idempotencia, pero el
  trabajo se duplica. La solución es `SELECT ... FOR UPDATE SKIP LOCKED`, que no
  se implementa porque el despliegue actual es de una instancia —la misma
  limitación conocida que el scheduler de vacaciones—.
- **Solo está en `employees-service`.** `auth-service` y `vacations-service`
  siguen publicando directamente, así que sus eventos aún se pueden perder. El
  informe solo lo exigía en empleados, pero la inconsistencia queda: un retiro
  publica `empleado.retirado` de forma fiable y la suspensión de cuenta que
  provoca, no.

## Alternativas consideradas

- **Publisher confirms de RabbitMQ.** Confirman que el broker recibió el
  mensaje, lo que detecta el fallo, pero no lo resuelven: si el broker está
  caído no hay confirmación que esperar y el evento sigue sin tener dónde
  guardarse. Son complementarios, no una alternativa.
- **Reintentar en memoria con `@Retryable`.** Resuelve un corte de un segundo y
  nada más: si el proceso se reinicia o el broker tarda minutos, el evento se
  pierde igual. La diferencia del outbox es que el evento está **persistido**.
- **Una transacción distribuida (XA) entre PostgreSQL y RabbitMQ.** Resuelve el
  problema de raíz y a un coste desproporcionado: coordinador de transacciones,
  configuración en los dos recursos, bloqueos más largos y un modo de fallo —el
  coordinador— nuevo. El outbox obtiene la misma garantía práctica con una tabla.
- **Change Data Capture con Debezium.** Lee el WAL de PostgreSQL y publica sin
  tabla intermedia ni publicador propio. Es la evolución natural de esto, pero
  añade Kafka Connect o equivalente a una infraestructura que ya tiene siete
  servicios, y la auditoría pedía un mecanismo, no una pieza más.
- **Dejarlo documentado como limitación conocida.** Es lo que había, y era
  defendible mientras fuera explícito. Dejó de serlo cuando el informe lo marcó
  como faltante: la cadena de alta completa depende de ese evento.
