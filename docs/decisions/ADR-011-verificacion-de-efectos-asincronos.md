# ADR-011: Verificar efectos asíncronos con polling y disparar el tiempo a mano

## Estado

Aceptada — 2026-10-21

## Contexto

Casi todo lo interesante del sistema ocurre después de que la respuesta HTTP ya
se devolvió. Un `POST /empleados` responde 201 y a partir de ahí:

```
empleado.creado → auth-service crea la cuenta → usuario.creado
               → notifications-service registra la notificación con el token
```

Cuando la suite BDD recibe el 201, ninguno de esos pasos ha terminado. Consultar
de inmediato el historial de notificaciones devuelve una lista sin la
notificación esperada, y la prueba falla aunque el sistema esté correcto.

Hay un segundo tipo de espera, distinta de la propagación de un evento: el paso
del tiempo. La cuenta se suspende cuando el scheduler del `vacations-service`
detecta que llegó la `fechaInicio` de un período. Un escenario que programe
vacaciones para mañana y espere la suspensión tendría que esperar hasta mañana.

Y hay un tercer caso, el más engañoso: comprobar que algo **deja** de funcionar.
«El empleado ya no puede iniciar sesión» es cierto tanto si el evento se procesó
y la cuenta se suspendió, como si el evento aún no llegó y el login está fallando
por otra razón.

## Decisión

### 1. Polling con reintentos, nunca una espera fija

Todos los pasos cuyo texto empieza por «eventualmente» se resuelven con
`support/Espera.java`, que reintenta la condición hasta que se cumple o se agota
el plazo. Una `Thread.sleep` fija se descartó por dos razones opuestas y
simultáneas: paga siempre su peor caso —esperar 5 s cuando el evento tarda
200 ms regala 4,8 s por escenario— y a la vez falla de forma intermitente el día
que el evento tarda 5,1 s.

Las excepciones durante el polling se tratan como «todavía no». Un 404 mientras
el evento viaja es un estado esperado, no un error.

### 2. Los parámetros del polling se justifican con una medición

| Parámetro | Valor | Variable de entorno |
|---|---|---|
| Intentos | 15 | `POLL_MAX_INTENTOS` |
| Intervalo | 1000 ms | `POLL_INTERVALO_MS` |
| Tolerancia total | ~15 s | — |

La cadena asíncrona más larga del ecosistema se completó en **200–600 ms** en las
ejecuciones observadas. La tolerancia de 15 s equivale a unas 25 veces ese
tiempo: cubre el arranque en frío del broker y un agente de CI cargado, sin que
un fallo real tarde más de 15 s en delatarse. Como el polling termina en cuanto
la condición se cumple, el caso normal cuesta un solo intento.

### 3. El tiempo se dispara a mano, no se espera

Las transiciones del período se provocan con
`POST /vacaciones/{id}/forzar-inicio` y `POST /vacaciones/{id}/forzar-fin`, que
existen desde el Reto 5, están protegidos con rol `ADMIN` y ejecutan **la misma
transición y publican el mismo evento** que el scheduler.

La alternativa —bajar la frecuencia del scheduler durante las pruebas— acopla la
suite a un detalle de configuración: cambiar el intervalo rompería las pruebas, y
cada transición costaría al menos un ciclo completo. Lo que la prueba debe
verificar es que, *cuando* el período inicia, la cuenta se suspende; que el
scheduler detecte la fecha a la hora correcta es responsabilidad del scheduler y
se verifica en sus pruebas unitarias.

### 4. Las condiciones negativas se confirman, no se observan una vez

`Espera.establemente(...)` espera a que la condición se cumpla y después la
confirma varias veces separadas en el tiempo. Así «el empleado eventualmente no
puede iniciar sesión» distingue un estado definitivo de uno transitorio.

### 5. El token de activación se obtiene por API, no de los logs

El token nunca aparece en una respuesta HTTP: viaja dentro de `usuario.creado` y
el `notifications-service` lo persiste en el `payload` del envelope. La suite lo
recupera con `GET /notifications` y lo extrae de `payload.data.token`. Que haya
que buscarlo ahí no es un rodeo: es la prueba de que el sistema no lo expone.

## Consecuencias

### Positivas

Las mediciones siguientes corresponden a la versión de 41 escenarios existente al
aprobar esta decisión; las evidencias conservan esos resultados históricos.

- La suite completa de 41 escenarios tarda entre 31 s y 44 s, no los minutos que
  costarían las esperas fijas equivalentes.
- Tres ejecuciones consecutivas dieron 41/41 sin un solo fallo intermitente.
- Los parámetros son variables de entorno, así que un agente de CI lento se
  ajusta sin recompilar ni tocar el código.
- El escenario del ciclo completo de vacaciones —login correcto, inicio,
  login bloqueado, fin, login correcto de nuevo— se ejecuta en segundos y
  verifica un comportamiento que en producción tarda semanas.

### Negativas

- Los endpoints de disparo manual son superficie de ataque añadida. Se mitigan
  exigiendo rol `ADMIN` en el gateway, y conviene deshabilitarlos por
  configuración en un despliegue real.
- Un fallo real tarda hasta 15 s en reportarse, porque la suite no puede
  distinguir «roto» de «lento» antes de agotar los reintentos.
- La prueba no verifica que el scheduler se dispare a la hora correcta; esa parte
  queda cubierta solo por las pruebas unitarias del scheduler.

## Alternativas consideradas

- **`Thread.sleep` fijo generoso.** Más simple de escribir y el enunciado del
  reto lo prohíbe explícitamente. Habría multiplicado por diez la duración de la
  suite y seguiría siendo intermitente.
- **Consumir RabbitMQ directamente desde la suite.** Permitiría esperar el evento
  exacto, pero ataría las pruebas a la infraestructura interna en lugar de a la
  API pública, y comprobaría que el evento se publicó, no que el consumidor lo
  procesó, que es lo que importa.
- **Leer los logs de los contenedores con `docker logs`.** Funciona, pero
  convierte un formato de log en un contrato y obliga a la suite a tener acceso
  al demonio de Docker. `GET /notifications` ofrece el mismo dato por una API
  estable.
