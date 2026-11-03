"""Genera la coleccion de Postman del Reto 8 (observabilidad).

Se genera con script porque son 20 peticiones con la misma estructura: asi todas
comparten plantilla y no hay riesgo de que a una le falte el token o apunte a
otra URL base por descuido. El JSON resultante es el que se versiona.
"""
import collections
import io
import json

OD = collections.OrderedDict


def url(raw):
    sin_query = raw.split('?')[0]
    base = sin_query.split('}}', 1)
    host = base[0] + '}}'
    ruta = [p for p in (base[1] if len(base) > 1 else '').split('/') if p]
    u = OD([('raw', raw), ('host', [host]), ('path', ruta)])
    if '?' in raw:
        u['query'] = [OD([('key', k), ('value', v)]) for k, v in
                      (p.split('=', 1) for p in raw.split('?', 1)[1].split('&'))]
    return u


def peticion(nombre, metodo, raw, descripcion, test=None, prerequest=None,
             body=None, cabeceras=None, auth_basica=False):
    req = OD([
        ('method', metodo),
        ('header', list(cabeceras or [])),
        ('url', url(raw)),
        ('description', descripcion),
    ])
    if auth_basica:
        req['auth'] = OD([
            ('type', 'basic'),
            ('basic', [OD([('key', 'username'), ('value', '{{grafanaUser}}')]),
                       OD([('key', 'password'), ('value', '{{grafanaPassword}}')])]),
        ])
    if body is not None:
        req['header'] = [OD([('key', 'Content-Type'), ('value', 'application/json')])] + req['header']
        req['body'] = OD([('mode', 'raw'),
                          ('raw', json.dumps(body, indent=2, ensure_ascii=False)),
                          ('options', {'raw': {'language': 'json'}})])

    eventos = []
    if prerequest:
        eventos.append(OD([('listen', 'prerequest'),
                           ('script', OD([('type', 'text/javascript'),
                                          ('exec', prerequest.split('\n'))]))]))
    if test:
        eventos.append(OD([('listen', 'test'),
                           ('script', OD([('type', 'text/javascript'),
                                          ('exec', test.split('\n'))]))]))

    item = OD([('name', nombre), ('request', req)])
    if eventos:
        item['event'] = eventos
    return item


def carpeta(nombre, descripcion, items):
    return OD([('name', nombre), ('description', descripcion), ('item', items)])


BEARER = [OD([('key', 'Authorization'), ('value', 'Bearer {{adminToken}}')])]

# ── 0. Preparacion ────────────────────────────────────────────────────────────
prep = [
    peticion(
        '0.1 Login ADMIN', 'POST', '{{gatewayBaseUrl}}/auth/login',
        'Guarda el token en adminToken. Todo lo demas depende de esta peticion.',
        body={'usuario': '{{adminUsuario}}', 'password': '{{adminPassword}}'},
        test="""pm.test('Status 200', () => pm.response.to.have.status(200));
pm.collectionVariables.set('adminToken', pm.response.json().token);"""),

    peticion(
        '0.2 Precondicion: departamento IT', 'POST', '{{gatewayBaseUrl}}/departamentos',
        'El alta de empleado valida el departamento contra departments-service por '
        'HTTP. Ese salto es el que hace visible el cruce Java -> Node en la traza, '
        'asi que el departamento tiene que existir.',
        body={'id': 'IT', 'nombre': 'Tecnologia', 'descripcion': 'Departamento de tecnologia'},
        cabeceras=BEARER,
        test="""pm.test('IT disponible (201 creado, o 400/409 si ya existia)', () => {
    pm.expect(pm.response.code).to.be.oneOf([201, 400, 409]);
});"""),
]

# ── 1. Generar telemetria ─────────────────────────────────────────────────────
PRE_TRACEPARENT = """// El CLIENTE elige el traceId, en lugar de dejar que lo genere el gateway.
//
// Es valido: el estandar W3C Trace Context dice que si la peticion entrante trae
// la cabecera traceparent, el servicio continua esa traza en vez de abrir una
// nueva. Verificado contra este sistema: la traza con el id elegido aqui aparece
// en Zipkin con los 7 servicios.
//
// La ventaja para la demo es que deja de haber que buscar "la traza mas larga":
// las peticiones 3.1 y 4.1 van directas a ESTA traza.
const hex = (n) => Array.from({ length: n }, () =>
    '0123456789abcdef'[Math.floor(Math.random() * 16)]).join('');

const traceId = hex(32);   // 16 bytes
const spanId  = hex(16);   // 8 bytes

// Formato: version-traceId-spanId-flags. El 01 final significa "muestreada".
pm.collectionVariables.set('traceparent', `00-${traceId}-${spanId}-01`);
pm.collectionVariables.set('traceIdDemo', traceId);

// Identificadores unicos para poder repetir la coleccion sin vaciar la base.
const sufijo = String(Date.now()).slice(-9);
pm.collectionVariables.set('empleadoDemoId', 'EOBS' + sufijo);
pm.collectionVariables.set('empleadoDemoEmail', 'obs' + sufijo + '@empresa.com');
pm.collectionVariables.set('empleadoDemoNumero', 'EMP-OBS-' + sufijo);

console.log('traceId de esta demo: ' + traceId);"""

telemetria = [
    peticion(
        '1.1 Crear empleado (dispara toda la cadena)', 'POST', '{{gatewayBaseUrl}}/empleados',
        'La peticion mas valiosa del reto. Dispara en cascada: el gateway enruta a '
        'employees-service (Java), que valida el departamento llamando a '
        'departments-service (Node) por HTTP, y publica empleado.creado en RabbitMQ. '
        'Ese evento lo consumen notifications, profiles y vacations (Node) y '
        'auth-service (Java), que a su vez publica usuario.creado y vuelve a '
        'activar a los tres. Resultado medido: ~47 spans y 7 servicios en UNA sola '
        'traza, cruzando HTTP, el broker y los dos lenguajes.',
        body={
            'id': '{{empleadoDemoId}}',
            'nombre': 'Observabilidad',
            'apellido': 'Demo',
            'email': '{{empleadoDemoEmail}}',
            'numeroEmpleado': '{{empleadoDemoNumero}}',
            'cargo': 'Analista',
            'area': 'Tecnologia',
            'departamentoId': 'IT',
            'fechaIngreso': '2026-11-03',
            'estado': 'ACTIVO',
        },
        cabeceras=BEARER + [OD([('key', 'traceparent'), ('value', '{{traceparent}}')])],
        prerequest=PRE_TRACEPARENT,
        test="""pm.test('Status 201', () => pm.response.to.have.status(201));
pm.test('El empleado se creo', () => {
    pm.expect(pm.response.json().id).to.eql(pm.collectionVariables.get('empleadoDemoId'));
});
console.log('Espere ~10s antes de la carpeta 3: la cadena asincrona tarda en completarse.');"""),

    peticion(
        '1.2 Trafico: listar empleados', 'GET', '{{gatewayBaseUrl}}/empleados',
        'Ejecute esta y las tres siguientes varias veces (o use el Runner con 10 '
        'iteraciones) para que los paneles de trafico y latencia tengan datos. '
        'rate() necesita al menos dos muestras en la ventana para dibujar algo.',
        cabeceras=BEARER,
        test="pm.test('Status 200', () => pm.response.to.have.status(200));"),

    peticion(
        '1.3 Trafico: listar departamentos', 'GET', '{{gatewayBaseUrl}}/departamentos',
        'Genera metricas en un servicio Node (prom-client).', cabeceras=BEARER,
        test="pm.test('Status 200', () => pm.response.to.have.status(200));"),

    peticion(
        '1.4 Trafico: listar notificaciones', 'GET', '{{gatewayBaseUrl}}/notifications',
        'Ademas de generar trafico, deja ver el historial de eventos procesados.',
        cabeceras=BEARER,
        test="pm.test('Status 200', () => pm.response.to.have.status(200));"),

    peticion(
        '1.5 Trafico: listar perfiles', 'GET', '{{gatewayBaseUrl}}/perfiles',
        'Read model CQRS alimentado por eventos.', cabeceras=BEARER,
        test="pm.test('Status 200', () => pm.response.to.have.status(200));"),

    peticion(
        '1.6 Provocar un 404 (para el panel de errores)', 'GET',
        '{{gatewayBaseUrl}}/empleados/NO-EXISTE',
        'El panel "Errores HTTP 4xx y 5xx" necesita errores que mostrar. Un 404 es '
        'la forma inocua de generarlos: no rompe nada y aparece en el dashboard.',
        cabeceras=BEARER,
        test="""pm.test('Status 404', () => pm.response.to.have.status(404));
pm.test('Aparecera en el panel de errores 4xx', () => {
    pm.expect(String(pm.response.code)).to.match(/^4/);
});"""),
]

# ── 2. Metricas (Prometheus) ──────────────────────────────────────────────────
metricas = [
    peticion(
        '2.1 Los 7 servicios estan UP', 'GET',
        '{{prometheusBaseUrl}}/api/v1/query?query=up{job!="prometheus"}',
        'Criterio 1 del reto. up vale 1 si Prometheus consiguio raspar el servicio. '
        'Deben salir 7 series, una por microservicio, todas en 1.',
        test="""pm.test('Status 200', () => pm.response.to.have.status(200));

const series = pm.response.json().data.result;

pm.test('Hay 7 microservicios vigilados', () => {
    pm.expect(series).to.have.lengthOf(7);
});

pm.test('Todos responden al scraping', () => {
    const caidos = series.filter(s => s.value[1] !== '1').map(s => s.metric.job);
    pm.expect(caidos, 'servicios caidos: ' + caidos.join(', ')).to.be.empty;
});

pm.test('El API Gateway esta entre ellos', () => {
    pm.expect(series.map(s => s.metric.job)).to.include('api-gateway');
});

console.log('Vigilados: ' + series.map(s => s.metric.job).sort().join(', '));"""),

    peticion(
        '2.2 Trafico por servicio', 'GET',
        '{{prometheusBaseUrl}}/api/v1/query?query=sum by (job) (rate(http_server_requests_seconds_count[5m])) '
        'or sum by (job) (rate(http_requests_total[5m]))',
        'Golden Signal de trafico. La consulta lleva "or" porque Micrometer (Java) y '
        'prom-client (Node) nombran la metrica distinto: '
        'http_server_requests_seconds_count frente a http_requests_total.',
        test="""pm.test('Status 200', () => pm.response.to.have.status(200));
pm.test('Hay metricas de trafico', () => {
    pm.expect(pm.response.json().data.result.length).to.be.above(0);
});"""),

    peticion(
        '2.3 Latencia media del gateway', 'GET',
        '{{prometheusBaseUrl}}/api/v1/query?query=sum(rate(http_server_requests_seconds_sum{job="api-gateway"}[5m])) '
        '/ sum(rate(http_server_requests_seconds_count{job="api-gateway"}[5m]))',
        'Golden Signal de latencia, medida en el borde porque es la perspectiva del '
        'cliente. El SLO es < 300 ms y la alerta salta por encima de 2 s.',
        test="""pm.test('Status 200', () => pm.response.to.have.status(200));

const r = pm.response.json().data.result;
if (r.length === 0) {
    console.log('Sin trafico en la ventana: ejecute la carpeta 1 y reintente.');
} else {
    const segundos = parseFloat(r[0].value[1]);
    console.log('Latencia media del gateway: ' + (segundos * 1000).toFixed(1) + ' ms');
    pm.test('Por debajo del umbral de alerta (2s)', () => {
        pm.expect(segundos).to.be.below(2);
    });
}"""),
]

# ── 3. Trazas (Zipkin) ────────────────────────────────────────────────────────
trazas = [
    peticion(
        '3.1 La traza completa del onboarding', 'GET',
        '{{zipkinBaseUrl}}/api/v2/trace/{{traceIdDemo}}',
        'Criterio 2 del reto, y el punto fuerte de la sustentacion. Va directa al '
        'traceId que eligio la peticion 1.1, asi que no hay que buscar nada. '
        'Comprueba que la traza cruza los dos lenguajes y que incluye el salto por '
        'RabbitMQ.',
        test="""pm.test('Status 200', () => pm.response.to.have.status(200));

const spans = pm.response.json();
const servicios = [...new Set(spans.map(s => s.localEndpoint && s.localEndpoint.serviceName))];

pm.test('La traza existe y tiene profundidad', () => {
    pm.expect(spans.length, 'spans').to.be.above(15);
});

pm.test('Cruza los dos lenguajes del ecosistema', () => {
    pm.expect(servicios).to.include('employees-service');    // Java
    pm.expect(servicios).to.include('departments-service');  // Node
});

pm.test('Incluye el salto asincrono por RabbitMQ', () => {
    const haySalto = spans.some(s => s.kind === 'PRODUCER');
    const hayConsumo = spans.some(s => s.kind === 'CONSUMER');
    pm.expect(haySalto, 'span PRODUCER').to.be.true;
    pm.expect(hayConsumo, 'span CONSUMER').to.be.true;
});

pm.test('Llega hasta la base de datos', () => {
    pm.expect(spans.some(s => (s.name || '').startsWith('pg.'))).to.be.true;
});

const lento = spans.reduce((a, b) => (a.duration > b.duration ? a : b));
console.log('--------------------------------------------------');
console.log('traceId:   ' + pm.collectionVariables.get('traceIdDemo'));
console.log('spans:     ' + spans.length);
console.log('servicios: ' + servicios.sort().join(', '));
console.log('span mas lento: ' + lento.name + ' (' +
            (lento.localEndpoint && lento.localEndpoint.serviceName) + ') ' +
            (lento.duration / 1000).toFixed(1) + ' ms');
console.log('Abralo en: ' + pm.variables.replaceIn('{{zipkinBaseUrl}}') +
            '/zipkin/traces/' + pm.collectionVariables.get('traceIdDemo'));
console.log('--------------------------------------------------');"""),

    peticion(
        '3.2 Zipkin conoce los 7 servicios', 'GET', '{{zipkinBaseUrl}}/api/v2/services',
        'Si un servicio no aparece aqui es que no esta instrumentado o no alcanza a '
        'Zipkin. Es la comprobacion mas rapida de que la instrumentacion llego a '
        'todos.',
        test="""pm.test('Status 200', () => pm.response.to.have.status(200));

const esperados = ['api-gateway', 'auth-service', 'employees-service',
                   'departments-service', 'notifications-service',
                   'profiles-service', 'vacations-service'];
const presentes = pm.response.json();

pm.test('Los 7 servicios envian trazas', () => {
    const faltan = esperados.filter(s => !presentes.includes(s));
    pm.expect(faltan, 'sin instrumentar o sin trafico: ' + faltan.join(', ')).to.be.empty;
});"""),
]

# ── 4. Logs (Loki) ────────────────────────────────────────────────────────────
PRE_LOKI = """// Loki espera el rango en nanosegundos desde epoch.
const ahora = Date.now();
pm.collectionVariables.set('lokiDesde', (ahora - 15 * 60 * 1000) * 1000000);
pm.collectionVariables.set('lokiHasta', ahora * 1000000);"""

logs = [
    peticion(
        '4.1 Los logs de ESA traza, en todos los servicios', 'GET',
        '{{lokiBaseUrl}}/loki/api/v1/query_range?query={job="docker"} | json | '
        'traceId = "{{traceIdDemo}}"&start={{lokiDesde}}&end={{lokiHasta}}&limit=100',
        'Criterio 3, y la demostracion de que los tres pilares estan correlacionados. '
        'Devuelve los logs de TODOS los servicios que participaron en la peticion '
        '1.1, unidos por el traceId. La metrica dice que algo va mal, la traza dice '
        'por donde paso, y el log dice que ocurrio exactamente en cada paso.',
        prerequest=PRE_LOKI,
        test="""pm.test('Status 200', () => pm.response.to.have.status(200));

const flujos = pm.response.json().data.result;
const lineas = flujos.flatMap(f => f.values.map(v => v[1]));
const servicios = new Set();
lineas.forEach(l => { try { servicios.add(JSON.parse(l).service); } catch (e) {} });

pm.test('Hay logs de esta traza', () => {
    pm.expect(lineas.length, 'lineas encontradas').to.be.above(0);
});

pm.test('Los logs son JSON estructurado', () => {
    const j = JSON.parse(lineas[0]);
    ['timestamp', 'level', 'service', 'traceId', 'message'].forEach(c => {
        pm.expect(j, 'falta el campo ' + c).to.have.property(c);
    });
});

pm.test('Varios servicios comparten el mismo traceId', () => {
    pm.expect(servicios.size, 'servicios: ' + [...servicios].join(', ')).to.be.above(1);
});

console.log('Logs: ' + lineas.length + ' lineas de ' + servicios.size +
            ' servicios (' + [...servicios].sort().join(', ') + ')');"""),

    peticion(
        '4.2 Loki recoge los 7 servicios', 'GET',
        '{{lokiBaseUrl}}/loki/api/v1/label/service/values',
        'Promtail descubre los contenedores por el socket de Docker, asi que aqui '
        'aparecen tambien las bases de datos y el propio stack. Lo que importa es '
        'que los 7 microservicios esten.',
        test="""pm.test('Status 200', () => pm.response.to.have.status(200));

const esperados = ['api-gateway', 'auth-service', 'employees-service',
                   'departments-service', 'notifications-service',
                   'profiles-service', 'vacations-service'];
const presentes = pm.response.json().data || [];

pm.test('Promtail recoge los logs de los 7 microservicios', () => {
    const faltan = esperados.filter(s => !presentes.includes(s));
    pm.expect(faltan, 'sin logs en Loki: ' + faltan.join(', ')).to.be.empty;
});"""),
]

# ── 5. Grafana ────────────────────────────────────────────────────────────────
grafana = [
    peticion(
        '5.1 El dashboard esta aprovisionado', 'GET',
        '{{grafanaBaseUrl}}/api/dashboards/uid/system-hr-observabilidad',
        'Criterio 4. El dashboard no se creo clicando: esta en un JSON versionado en '
        'Git y Grafana lo carga al arrancar. Esta peticion lo demuestra sin abrir el '
        'navegador.',
        auth_basica=True,
        test="""pm.test('Status 200', () => pm.response.to.have.status(200));

const d = pm.response.json();
const paneles = d.dashboard.panels.filter(p => p.type !== 'row');

pm.test('Tiene al menos los 4 paneles requeridos', () => {
    pm.expect(paneles.length).to.be.at.least(4);
});

pm.test('Esta en la carpeta System_HR', () => {
    pm.expect(d.meta.folderTitle).to.eql('System_HR');
});

console.log('Paneles: ' + paneles.map(p => p.title).join(' | '));"""),

    peticion(
        '5.2 Las reglas de alerta estan activas', 'GET',
        '{{grafanaBaseUrl}}/api/v1/provisioning/alert-rules',
        'Criterio 5. Tres reglas: servicio caido, alta tasa de errores y alta '
        'latencia. Cada umbral se deriva de un SLO documentado en el README, no de '
        'un numero elegido al azar.',
        auth_basica=True,
        test="""pm.test('Status 200', () => pm.response.to.have.status(200));

const reglas = pm.response.json();

pm.test('Al menos 2 reglas, como pide el enunciado', () => {
    pm.expect(reglas.length).to.be.at.least(2);
});

pm.test('Incluye la deteccion de servicio caido', () => {
    pm.expect(reglas.map(r => r.title)).to.include('Servicio caído');
});

reglas.forEach(r => console.log(r.title + ' | for: ' + r.for +
                                ' | severidad: ' + (r.labels || {}).severity));"""),

    peticion(
        '5.3 El canal de Discord esta configurado', 'GET',
        '{{grafanaBaseUrl}}/api/v1/provisioning/contact-points',
        'El canal externo al que Grafana envia las alertas. La URL del webhook NO '
        'esta en el repositorio: llega por la variable de entorno '
        'DISCORD_WEBHOOK_URL, porque un webhook es una credencial.',
        auth_basica=True,
        test="""pm.test('Status 200', () => pm.response.to.have.status(200));

const canales = pm.response.json();

pm.test('Hay un canal de Discord', () => {
    pm.expect(canales.some(c => c.type === 'discord'),
              'canales: ' + canales.map(c => c.type).join(', ')).to.be.true;
});"""),
]

# ── 6. Caos ───────────────────────────────────────────────────────────────────
caos = [
    peticion(
        '6.1 [MANUAL] Pare departments-service y espere 100s', 'GET',
        '{{prometheusBaseUrl}}/api/v1/query?query=up{job="departments-service"}',
        'PASO MANUAL. En la terminal:\n\n'
        '    docker compose stop departments-service\n\n'
        'Tiempos medidos en esta maquina:\n'
        '  ~22 s  Prometheus marca el target DOWN\n'
        '  ~95 s  Grafana dispara la alerta\n'
        '         Discord recibe el mensaje\n\n'
        'Ese minuto y medio no es lentitud: la regla tiene for: 1m para que un '
        'reinicio normal del contenedor no despierte a nadie. Ejecute esta peticion '
        'pasados ~25 s.',
        test="""pm.test('Status 200', () => pm.response.to.have.status(200));

const r = pm.response.json().data.result;
pm.test('Prometheus detecto la caida', () => {
    pm.expect(r.length, 'no hay serie up para departments-service').to.be.above(0);
    pm.expect(r[0].value[1], 'up deberia ser 0').to.eql('0');
});"""),

    peticion(
        '6.2 La alerta esta disparada', 'GET',
        '{{grafanaBaseUrl}}/api/alertmanager/grafana/api/v2/alerts',
        'Ejecutela ~100 s despues de parar el servicio. Debe aparecer la alerta '
        '"Servicio caido" en estado active, y el mensaje debe haber llegado a '
        'Discord.',
        auth_basica=True,
        test="""pm.test('Status 200', () => pm.response.to.have.status(200));

const alertas = pm.response.json();
const caidos = alertas.filter(a => (a.labels || {}).alertname === 'Servicio caído');

pm.test('La alerta de servicio caido esta activa', () => {
    pm.expect(caidos.length, 'aun no se dispara: espere a los 95s').to.be.above(0);
});

caidos.forEach(a => console.log('ALERTA: ' + a.labels.alertname +
                                ' | job=' + a.labels.job +
                                ' | estado=' + a.status.state));"""),

    peticion(
        '6.3 Degradacion: el alta falla con 503', 'POST', '{{gatewayBaseUrl}}/empleados',
        'Con departments-service caido, el alta de un empleado en un departamento '
        'NUNCA consultado antes responde 503: el circuit breaker del Reto 3 abre y '
        'el cache de contingencia no tiene entrada que servir. Si usara IT, el cache '
        'responderia y el alta funcionaria — que es justo el comportamiento '
        'resiliente que se diseno entonces, y que ahora ademas se VE en Zipkin.',
        body={
            'id': '{{empleadoDemoId}}-CAOS',
            'nombre': 'Caos',
            'apellido': 'Demo',
            'email': 'caos-{{empleadoDemoEmail}}',
            'numeroEmpleado': 'CAOS-{{empleadoDemoNumero}}',
            'cargo': 'Analista',
            'area': 'Tecnologia',
            'departamentoId': 'NUNCA-CONSULTADO',
            'fechaIngreso': '2026-11-03',
            'estado': 'ACTIVO',
        },
        cabeceras=BEARER,
        test="""pm.test('Responde 503, no 500', () => {
    pm.expect(pm.response.code, 'cuerpo: ' + pm.response.text()).to.eql(503);
});
pm.test('El error explica que la dependencia no esta disponible', () => {
    pm.expect(pm.response.text().toLowerCase()).to.match(/disponible|departamento/);
});"""),

    peticion(
        '6.4 [MANUAL] Reinicie y verifique la recuperacion', 'GET',
        '{{prometheusBaseUrl}}/api/v1/query?query=up{job="departments-service"}',
        'PASO MANUAL. En la terminal:\n\n'
        '    docker compose start departments-service\n\n'
        'Tiempos medidos: ~21 s hasta up=1, ~31 s hasta que la alerta se resuelve y '
        'Discord recibe el aviso de recuperacion. Saber que el servicio volvio es '
        'tan importante como saber que se cayo: evita que alguien siga investigando '
        'un problema ya resuelto.',
        test="""pm.test('Status 200', () => pm.response.to.have.status(200));
pm.test('El servicio volvio', () => {
    const r = pm.response.json().data.result;
    pm.expect(r.length).to.be.above(0);
    pm.expect(r[0].value[1], 'espere ~25s tras el start').to.eql('1');
});"""),
]

coleccion = OD([
    ('info', OD([
        ('name', 'Observabilidad (Reto 8)'),
        ('description',
         'Verifica el stack de observabilidad del ecosistema System_HR: metricas en '
         'Prometheus, trazas distribuidas en Zipkin, logs estructurados en Loki, el '
         'dashboard y las alertas de Grafana, y la simulacion de caos.\n\n'
         'ORDEN DE EJECUCION: las carpetas 0 a 5 se pueden lanzar de corrido con el '
         'Runner, esperando ~10 s entre la carpeta 1 y la 3 para que la cadena '
         'asincrona termine. La carpeta 6 tiene dos pasos manuales en la terminal.\n\n'
         'NOTA IMPORTANTE: los microservicios usan expose y no ports, asi que solo el '
         'gateway esta publicado al host. El /health de cada servicio no es accesible '
         'desde Postman por diseno (ADR-008: impide esquivar la validacion del JWT); '
         'la salud por servicio se comprueba con la metrica up de Prometheus, que es '
         'la peticion 2.1.'),
        ('schema', 'https://schema.getpostman.com/json/collection/v2.1.0/collection.json'),
    ])),
    ('item', [
        carpeta('0. Preparacion', 'Token de administrador y departamento de prueba.', prep),
        carpeta('1. Generar telemetria',
                'Crea trafico y eventos para que haya algo que observar. La peticion '
                '1.1 elige su propio traceId, de modo que las carpetas 3 y 4 van '
                'directas a esa traza.', telemetria),
        carpeta('2. Metricas (Prometheus)', 'Criterio 1: modelo pull, 7 targets.', metricas),
        carpeta('3. Trazas (Zipkin)',
                'Criterio 2: una traza que cruza HTTP, RabbitMQ y dos lenguajes.', trazas),
        carpeta('4. Logs (Loki)',
                'Criterio 3: logs JSON correlacionados con la traza por traceId.', logs),
        carpeta('5. Dashboard y alertas (Grafana)',
                'Criterios 4 y 5: todo aprovisionado como codigo.', grafana),
        carpeta('6. Simulacion de caos',
                'Criterio 6. Dos pasos se ejecutan en la terminal; la coleccion '
                'verifica el efecto.', caos),
    ]),
    ('variable', [
        OD([('key', 'adminToken'), ('value', '')]),
        OD([('key', 'traceparent'), ('value', '')]),
        OD([('key', 'traceIdDemo'), ('value', '')]),
        OD([('key', 'empleadoDemoId'), ('value', '')]),
        OD([('key', 'empleadoDemoEmail'), ('value', '')]),
        OD([('key', 'empleadoDemoNumero'), ('value', '')]),
        OD([('key', 'lokiDesde'), ('value', '')]),
        OD([('key', 'lokiHasta'), ('value', '')]),
    ]),
])

destino = 'postman/observabilidad-reto8.postman_collection.json'
io.open(destino, 'w', encoding='utf-8', newline='\n').write(
    json.dumps(coleccion, indent=2, ensure_ascii=False) + '\n')

total = sum(len(c['item']) for c in coleccion['item'])
print('Escrito %s' % destino)
print('%d carpetas, %d peticiones' % (len(coleccion['item']), total))
