# Guía de sustentación — Reto 8 (Observabilidad y Monitoreo)

Guion para la presentación: qué ejecutar, en qué orden, qué mostrar en cada
pantalla y qué decir. Los tiempos son **medidos en esta máquina**, no estimados.

Duración aproximada: 12–15 minutos sin contar preguntas.

---

## Antes de empezar (5 minutos antes de presentar)

```bash
cd microservicios/System_HR
docker compose up -d
docker compose ps
```

Espera a que los **19 contenedores** estén arriba. Los 14 del ecosistema más los
5 de observabilidad. `grafana` y `promtail` aparecen como `Up` sin `(healthy)`
porque no tienen healthcheck definido; eso es normal.

Comprueba las cuatro interfaces antes de empezar, para no descubrir en directo
que una no abre:

| Interfaz | URL | Credenciales |
|---|---|---|
| Prometheus | http://localhost:9090 | — |
| Grafana | http://localhost:3000 | `admin` / `admin` |
| Zipkin | http://localhost:9411 | — |
| RabbitMQ | http://localhost:15672 | `admin` / `admin` |

Deja abiertas cinco pestañas en este orden, para no perder tiempo navegando:

1. Prometheus → **Status → Targets**
2. Grafana → dashboard **System_HR - Observabilidad del Ecosistema**
3. Zipkin
4. Grafana → **Explore** con la fuente **Loki** seleccionada
5. Discord → canal `#alertas-grafana`

Y una terminal en `microservicios/System_HR`.

---

## Paso 1 — El problema que resuelve el reto (1 min, sin pantalla)

Antes de mostrar nada, enuncia el problema. Es lo que da sentido a todo lo demás:

> «El sistema tiene siete microservicios en dos lenguajes. Si una petición tarda
> cinco segundos, ¿es culpa del servicio de empleados, de su base de datos, del
> broker, o del servicio de departamentos al que llama? Hasta el Reto 7 no había
> forma de saberlo: el sistema era una caja negra. El Reto 8 lo instrumenta para
> responder tres preguntas: ¿está vivo?, ¿cómo se comporta?, ¿qué hizo
> exactamente?»

---

## Paso 2 — Prometheus: ¿está vivo el sistema? (2 min)

**Pestaña de Prometheus → Status → Targets.**

Deben verse **8 targets en UP**: los 7 microservicios más Prometheus vigilándose
a sí mismo.

Qué señalar:

- **El API Gateway es el primer job.** No es casualidad: al ser el único punto de
  entrada, su latencia y su tasa de error son lo más parecido a lo que percibe el
  cliente.
- **Las rutas de métricas son distintas según el lenguaje.** Los tres servicios
  Java exponen `/actuator/prometheus` (Micrometer) y los cuatro Node exponen
  `/metrics` (prom-client). Por eso `metrics_path` se configura por job y no
  globalmente.
- **El modelo es PULL.** Nadie envía métricas: es Prometheus quien visita cada
  endpoint cada 15 segundos. La consecuencia importante: *un servicio que no esté
  en `prometheus.yml` no se vigila, y su caída pasa desapercibida.*

Si quieres mostrar una métrica cruda, en la pestaña **Graph**:

```promql
up{job!="prometheus"}
```

---

## Paso 3 — Generar tráfico (1 min, en la terminal)

Las métricas necesitan tráfico para tener algo que mostrar. Este bloque crea un
empleado —que dispara toda la cadena asíncrona— y hace 40 consultas:

```bash
TK=$(curl -s -X POST http://localhost:8080/auth/login -H "Content-Type: application/json" -d '{"usuario":"admin@empresa.com","password":"Admin12345"}' | python -c "import sys,json;print(json.load(sys.stdin)['token'])")
```

```bash
S=$(date +%s); curl -s -o /dev/null -X POST http://localhost:8080/empleados -H "Content-Type: application/json" -H "Authorization: Bearer $TK" -d "{\"id\":\"EDEMO$S\",\"nombre\":\"Demo\",\"apellido\":\"Sustentacion\",\"email\":\"demo$S@empresa.com\",\"numeroEmpleado\":\"EMP-DEMO-$S\",\"cargo\":\"Analista\",\"area\":\"Tecnologia\",\"departamentoId\":\"IT\",\"fechaIngreso\":\"2026-11-03\",\"estado\":\"ACTIVO\"}"; echo "empleado EDEMO$S creado"
```

```bash
for i in $(seq 1 10); do curl -s -o /dev/null http://localhost:8080/empleados -H "Authorization: Bearer $TK"; curl -s -o /dev/null http://localhost:8080/departamentos -H "Authorization: Bearer $TK"; curl -s -o /dev/null http://localhost:8080/notifications -H "Authorization: Bearer $TK"; curl -s -o /dev/null http://localhost:8080/perfiles -H "Authorization: Bearer $TK"; done; echo "trafico generado"
```

**Apunta el id del empleado** (`EDEMO...`): lo vas a necesitar en el paso 7.

> **Ojo con el acento.** No pongas `Tecnología` con tilde en el cuerpo JSON desde
> la terminal: la codificación lo corrompe y el servicio responde 400 con
> «el cuerpo de la petición no es un JSON válido». Usa `Tecnologia`.

---

## Paso 4 — Grafana: ¿cómo se comporta? (3 min)

**Pestaña de Grafana → carpeta `System_HR` → dashboard
`System_HR - Observabilidad del Ecosistema`.**

Pon el rango de tiempo arriba a la derecha en **Last 15 minutes** y el refresco
en **10s**.

Recorre las tres filas en orden:

**Fila 1 — Resumen del Sistema**

- *Estado de los servicios*: siete recuadros verdes. Es la señal más barata y la
  más importante.
- *Servicios arriba*: `7`.
- *Tasa de error 5xx*: `0`.

**Fila 2 — Comportamiento del Tráfico (Golden Signals)**

Menciona las cuatro Golden Signals de Google por su nombre y señala dónde está
cada una:

| Golden Signal | Panel |
|---|---|
| Tráfico | Peticiones por segundo |
| Latencia | Latencia media y Latencia p95 |
| Errores | Errores HTTP 4xx y 5xx |
| Saturación | CPU y Memoria (fila 3) |

Dos cosas que vale la pena señalar aquí, porque demuestran criterio propio:

1. **Cada panel tiene dos consultas.** Micrometer publica
   `http_server_requests_seconds_count` y prom-client publica
   `http_requests_total`. Son nombres distintos para lo mismo, así que cada panel
   consulta las dos familias y agrupa por `job`.
2. **Están la media y el p95.** La media esconde las colas: puede verse bien con
   un 5% de usuarios sufriendo. El p95 dice qué experiencia tiene ese 5% peor.
   Se calcula con `histogram_quantile` sobre los baldes del histograma, que es la
   razón por la que las métricas se publican como `Histogram` y no como `Summary`
   — los histogramas se pueden agregar entre instancias, los percentiles de un
   Summary no.

**Fila 3 — Saturación**: CPU y heap de los dos runtimes.

Si el profesor pregunta por el aprovisionamiento: el dashboard **no se creó
clicando**. Está en
`observability/grafana/provisioning/dashboards/system-hr-observabilidad.json`,
versionado en Git, y Grafana lo carga al arrancar. Un dashboard hecho a mano se
pierde al recrear el contenedor y nadie sabe qué paneles tenía.

---

## Paso 5 — Zipkin: ¿qué hizo exactamente? (3 min) ← **el punto fuerte**

**Pestaña de Zipkin → botón `RUN QUERY`** (sin filtros, trae las últimas trazas).

Busca la traza más larga, la del `POST /empleados`. Debe tener **unos 40 spans y
7 servicios**. Ábrela.

Lo que se ve, y el orden en que conviene contarlo:

```
api-gateway (Java)
 └─ employees-service (Java)
     ├─ departments-service (Node) → pg.query
     └─ hr.events/ send                        [PRODUCER]
         ├─ notifications-service  consume empleado.creado → pg.insert
         ├─ profiles-service       consume empleado.creado → pg.insert
         ├─ vacations-service      consume empleado.creado → pg.insert
         └─ auth-service (Java)    auth.queue receive
             └─ hr.events/ send    (usuario.creado)        [PRODUCER]
                 └─ notifications-service consume usuario.creado → pg.insert
```

Los tres puntos que hay que decir:

1. **Una sola traza atraviesa Java y Node.** El `traceId` nace en el gateway
   (Java), viaja en la cabecera `traceparent` del estándar **W3C Trace Context**
   y lo recoge un servicio escrito en TypeScript. Ninguno de los dos sabe en qué
   lenguaje está el otro.
2. **La traza baja hasta la consulta SQL.** Los spans `pg.query` vienen de la
   instrumentación automática del driver, sin una línea de código nuestro.
3. **La mitad asíncrona también está dentro.** El span `hr.events/ send` es el
   salto por RabbitMQ, y los `consume ...` que cuelgan de él son los tres
   consumidores. Esto **no lo hace el SDK solo**: por HTTP sí, por el broker hay
   que inyectar el contexto en las cabeceras del mensaje al publicar y extraerlo
   al consumir. Está implementado en
   `services/*/src/observability/amqp-tracing.ts` para Node y con
   `template.setObservationEnabled(true)` para Java.

### El caso difícil: una traza sin petición que la origine

Este es el que el enunciado marca explícitamente. En el buscador de Zipkin filtra
por **serviceName = `vacations-service`** y busca una traza cuyo span raíz se
llame **`scheduler.vacaciones.iniciar`**.

Si no hay ninguna reciente, provócala: programa unas vacaciones que empiecen hoy
y espera un minuto a que el cron se dispare.

```bash
HOY=$(date -u +%Y-%m-%d); curl -s -X POST http://localhost:8080/vacaciones -H "Content-Type: application/json" -H "Authorization: Bearer $TK" -d "{\"empleadoId\":\"PON_AQUI_EL_ID\",\"fechaInicio\":\"$HOY\",\"fechaFin\":\"$HOY\"}"
```

> Usa `date -u`: los contenedores van en UTC y la máquina en UTC‑5, así que
> «hoy» puede diferir y el servicio rechaza la fecha como pasada.

Qué decir:

> «Esta traza no la originó ninguna petición HTTP. La disparó el scheduler, solo,
> a la hora que le tocaba. La instrumentación automática no puede crear una traza
> aquí porque no hay petición entrante, así que el span raíz se crea a mano antes
> de publicar el evento. Sin eso, estas 26 líneas serían tres trazas
> independientes sin relación entre sí — y es justo el flujo que más cuesta
> depurar, porque ocurre de madrugada y sin nadie mirando.»

**Copia el `traceId` de esta traza**: lo usas en el paso siguiente.

---

## Paso 6 — Loki: los tres pilares correlacionados (2 min)

**Pestaña de Grafana → Explore → fuente de datos `Loki`.**

Primera consulta, para mostrar que los logs son estructurados:

```logql
{job="docker", service="employees-service"} | json | level = "INFO"
```

Despliega una línea: es JSON con `timestamp`, `level`, `service`, `traceId`,
`spanId` y `message`.

Segunda consulta — **esta es la que demuestra el criterio**. Pega el `traceId`
que copiaste de Zipkin:

```logql
{job="docker"} | json | traceId = "PEGA_AQUI_EL_TRACEID"
```

Devuelve los logs de **todos los servicios** que participaron en esa única
petición, ordenados en el tiempo.

Qué decir:

> «Este es el valor de los tres pilares juntos. La métrica me dijo que algo iba
> mal, la traza me dijo por dónde pasó la petición y dónde se fue el tiempo, y el
> log me dice exactamente qué ocurrió en cada paso. El `traceId` es el hilo que
> los une: sin él, tendría tres herramientas que no se hablan.»

Un detalle de diseño que merece mención si hay tiempo: **el `traceId` no es una
etiqueta de Loki, es un campo**. Como etiqueta tendría cardinalidad altísima —una
por petición— y crearía un flujo nuevo por cada traza, degradando el índice. Las
etiquetas son `service`, `level` y `container`, que son pocas y estables.

---

## Paso 7 — Simulación de caos (3 min) ← **el cierre**

Es obligatoria según el enunciado. **Tiempos medidos**, para que sepas cuánto
esperar y no te quedes en silencio:

| Momento | Cuándo |
|---|---|
| Prometheus marca el target `DOWN` | **~22 s** |
| Grafana dispara la alerta | **~95 s** |
| Discord recibe el mensaje | pocos segundos después de la alerta |
| Al reiniciar, target `UP` | ~21 s |
| Alerta resuelta y aviso de recuperación | ~31 s |

**Apaga un servicio:**

```bash
docker compose stop departments-service
```

Mientras esperas, explica qué va a pasar y por qué tarda lo que tarda:

> «La alerta tiene `for: 1m`. Eso significa que la condición debe mantenerse un
> minuto antes de notificar: un reinicio normal del contenedor tarda menos y no
> debe despertar a nadie. A eso se suman los 15 segundos del intervalo de
> scraping y los 30 del ciclo de evaluación de Grafana. Un minuto y medio no es
> lentitud, es el margen que evita las falsas alarmas.»

Luego muestra, en este orden:

1. **Prometheus → Targets**: `departments-service` en rojo, estado `DOWN`, con el
   mensaje de conexión rechazada.
2. **Grafana → dashboard**: el recuadro de `departments-service` pasa a rojo y
   *Servicios arriba* baja a `6`.
3. **Grafana → Alerting → Alert rules**: la regla *Servicio caído* en estado
   `Firing`.
4. **Discord → `#alertas-grafana`**: el mensaje con el nombre de la regla, el
   servicio, la severidad y la hora.

**Restaura:**

```bash
docker compose start departments-service
```

Y muestra el mensaje de **recuperación** en Discord. Vale la pena decir por qué
se envía:

> «Saber que el servicio volvió es tan importante como saber que se cayó: evita
> que alguien siga investigando un problema ya resuelto.»

### Variante opcional: degradación en lugar de caída

Si quieres mostrar algo más fino, con `departments-service` apagado intenta crear
un empleado:

```bash
S=$(date +%s); curl -s -X POST http://localhost:8080/empleados -H "Content-Type: application/json" -H "Authorization: Bearer $TK" -d "{\"id\":\"ECAOS$S\",\"nombre\":\"Caos\",\"apellido\":\"Prueba\",\"email\":\"caos$S@empresa.com\",\"numeroEmpleado\":\"EMP-CAOS-$S\",\"cargo\":\"Analista\",\"area\":\"Tecnologia\",\"departamentoId\":\"NUNCA-VISTO\",\"fechaIngreso\":\"2026-11-03\",\"estado\":\"ACTIVO\"}"
```

Responde `503`, y en Zipkin la traza aparece con el span en error. Conecta con el
Reto 3: el circuit breaker y el caché de contingencia siguen ahí, y ahora además
**se ven**.

---

## Las cuatro capturas que pide el enunciado

Tómalas durante el recorrido, no al final:

1. **Dashboard de Grafana** con métricas reales (paso 4).
2. **Traza en Zipkin** mostrando la cascada completa (paso 5).
3. **Alerta recibida en Discord** (paso 7).
4. **Consulta LogQL** por `traceId` en Grafana (paso 6) — refuerza el criterio 3.

> En las capturas de Discord y de la configuración, **no debe aparecer la URL del
> webhook**. Es una credencial: quien la tenga puede publicar en el canal.

---

## Preguntas probables y cómo responderlas

**«¿Por qué Zipkin y no Jaeger?»**
Un solo contenedor, sin dependencias externas, y exportador directo tanto en
Spring Boot como en el SDK de Node. Jaeger tiene mejor UI —comparación de trazas,
análisis de dependencias— pero más piezas que configurar. Para el alcance del
reto, menos cosas que puedan fallar en la sustentación.

**«¿Por qué Loki y no ELK?»**
Loki indexa solo las etiquetas, no el contenido, así que es mucho más liviano. Y
se integra en el mismo Grafana que ya estaba configurado, añadiendo una fuente de
datos en lugar de levantar Elasticsearch, Logstash y Kibana.

**«¿Muestreo al 100%? Eso no escala.»**
Correcto, y es deliberado: en desarrollo y QA se quiere visibilidad total. En
producción sería head-based sampling del 10–20%, o tail-based en un OTel
Collector para conservar el 100% de las trazas con error o latencia alta. Está
como variable de entorno (`OTEL_TRACES_SAMPLER_ARG`), así que se cambia sin
reconstruir imágenes.

**«¿Por qué no usaron el OTel Collector?»**
El enunciado lista cinco servicios y el Collector no está entre ellos. Los
servicios exportan directo a Zipkin, que para este alcance es una pieza menos. El
Collector tendría sentido al añadir tail-based sampling o al querer cambiar de
backend sin tocar los servicios — es el paso natural siguiente.

**«¿Qué servicio tardó más en responder y cómo lo identificaron?»**
La respuesta está en el README con los datos medidos. En resumen: el span más
largo de la cadena del onboarding es el de `auth-service` consumiendo
`empleado.creado` (~485 ms en la traza medida), porque dentro de ese span crea la
cuenta, genera el JWT de activación con BCrypt y publica un segundo evento. Se
identificó abriendo la traza en Zipkin y ordenando por duración: no hubo que
revisar ningún log ni añadir cronómetros al código.

**«Si se cae Prometheus, ¿quién avisa?»**
Nadie, y es una limitación real. Prometheus se vigila a sí mismo, lo que sirve
para detectar degradación pero no su propia caída. En producción se resuelve con
un segundo Prometheus que vigile al primero (*meta-monitoring*) o con un
dead man's switch: una alerta que se dispara si *deja* de llegar una señal
periódica.

---

## Si algo falla en directo

| Síntoma | Causa más probable | Qué hacer |
|---|---|---|
| Algún target en `DOWN` al empezar | El servicio aún arrancaba | Espera 30 s y recarga; el `start_period` es de 120 s |
| Panel del dashboard vacío | No hay tráfico en la ventana | Ejecuta el paso 3 y pon el rango en *Last 15 minutes* |
| Zipkin sin trazas | Se reinició el contenedor | Zipkin guarda en memoria: genera tráfico otra vez |
| Explore de Loki sin resultados | Rango de tiempo muy corto | Amplía a *Last 1 hour* |
| `docker compose up` falla con `rabbitmq is unhealthy` | Arranque en frío lento | Vuelve a lanzar `docker compose up -d`; el segundo arranque es más rápido |
| No llega nada a Discord | Falta `DISCORD_WEBHOOK_URL` en `.env` | Compruébalo y reinicia Grafana: `docker compose restart grafana` |
