"""Genera el dashboard de Grafana del Reto 8.

Se construye con un script en lugar de a mano porque el JSON de Grafana es muy
verboso y repetitivo: así los paneles comparten la misma plantilla y no hay
riesgo de que uno quede con una unidad o un datasource distinto por descuido.
El JSON resultante es el que se versiona en Git.

Nota sobre las consultas: los servicios Java (Micrometer) publican
http_server_requests_seconds_* y los Node (prom-client) http_requests_total y
http_request_duration_seconds_*. Son nombres distintos para lo mismo, asi que
cada panel lleva dos consultas, una por familia, y se agrupan por job. Unificar
los nombres habria exigido reimplementar a mano lo que Micrometer ya da hecho.
"""
import io
import json

PROM = {"type": "prometheus", "uid": "prometheus"}


def destino(expr, leyenda, ref):
    return {
        "datasource": PROM,
        "editorMode": "code",
        "expr": expr,
        "legendFormat": leyenda,
        "range": True,
        "refId": ref,
    }


def fila(titulo, y, uid):
    return {
        "type": "row",
        "title": titulo,
        "gridPos": {"h": 1, "w": 24, "x": 0, "y": y},
        "id": uid,
        "collapsed": False,
        "panels": [],
    }


def serie_temporal(titulo, descripcion, destinos, unidad, x, y, w, h, uid, minimo=0):
    return {
        "type": "timeseries",
        "title": titulo,
        "description": descripcion,
        "id": uid,
        "datasource": PROM,
        "gridPos": {"h": h, "w": w, "x": x, "y": y},
        "targets": destinos,
        "fieldConfig": {
            "defaults": {
                "unit": unidad,
                "min": minimo,
                "custom": {
                    "drawStyle": "line",
                    "lineWidth": 2,
                    "fillOpacity": 8,
                    "showPoints": "never",
                    "spanNulls": True,
                },
            },
            "overrides": [],
        },
        "options": {
            "legend": {"displayMode": "table", "placement": "right",
                       "showLegend": True, "calcs": ["lastNotNull", "max"]},
            "tooltip": {"mode": "multi", "sort": "desc"},
        },
    }


paneles = []

# ── Fila 1: resumen del sistema ───────────────────────────────────────────────
paneles.append(fila("Resumen del Sistema", 0, 1))

paneles.append({
    "type": "stat",
    "title": "Estado de los servicios",
    "description": (
        "up vale 1 si Prometheus consiguio raspar el servicio y 0 si no. "
        "Es la senal mas barata y la mas importante: si un servicio no esta en "
        "prometheus.yml no aparece aqui, y su caida pasa desapercibida."
    ),
    "id": 2,
    "datasource": PROM,
    "gridPos": {"h": 6, "w": 16, "x": 0, "y": 1},
    "targets": [destino('up{job!="prometheus"}', "{{job}}", "A")],
    "fieldConfig": {
        "defaults": {
            "mappings": [{
                "type": "value",
                "options": {
                    "0": {"text": "CAIDO", "color": "red", "index": 0},
                    "1": {"text": "ARRIBA", "color": "green", "index": 1},
                },
            }],
            "thresholds": {"mode": "absolute", "steps": [
                {"color": "red", "value": None},
                {"color": "green", "value": 1},
            ]},
            "noValue": "SIN DATOS",
        },
        "overrides": [],
    },
    "options": {
        "colorMode": "background",
        "graphMode": "none",
        "justifyMode": "center",
        "textMode": "value_and_name",
        "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
    },
})

paneles.append({
    "type": "stat",
    "title": "Servicios arriba",
    "description": "Cuantos de los 7 microservicios responden al scraping.",
    "id": 3,
    "datasource": PROM,
    "gridPos": {"h": 6, "w": 4, "x": 16, "y": 1},
    "targets": [destino('sum(up{job!="prometheus"})', "arriba", "A")],
    "fieldConfig": {
        "defaults": {
            "thresholds": {"mode": "absolute", "steps": [
                {"color": "red", "value": None},
                {"color": "orange", "value": 5},
                {"color": "green", "value": 7},
            ]},
            "max": 7,
            "min": 0,
        },
        "overrides": [],
    },
    "options": {
        "colorMode": "value",
        "graphMode": "area",
        "textMode": "value",
        "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
    },
})

paneles.append({
    "type": "stat",
    "title": "Tasa de error 5xx (gateway)",
    "description": (
        "Porcentaje de respuestas 5xx en el API Gateway. Se mide en el borde "
        "porque es la perspectiva del cliente: un 500 interno que el gateway "
        "resuelve con un fallback no es un error para quien llama. "
        "El SLO es < 1%; la alerta se dispara por encima del 10%."
    ),
    "id": 4,
    "datasource": PROM,
    "gridPos": {"h": 6, "w": 4, "x": 20, "y": 1},
    "targets": [destino(
        '100 * sum(rate(http_server_requests_seconds_count{job="api-gateway", status=~"5.."}[5m]))'
        ' / sum(rate(http_server_requests_seconds_count{job="api-gateway"}[5m]))',
        "5xx %", "A")],
    "fieldConfig": {
        "defaults": {
            "unit": "percent",
            "decimals": 2,
            "thresholds": {"mode": "absolute", "steps": [
                {"color": "green", "value": None},
                {"color": "orange", "value": 1},
                {"color": "red", "value": 10},
            ]},
            "noValue": "0",
        },
        "overrides": [],
    },
    "options": {
        "colorMode": "value",
        "graphMode": "area",
        "textMode": "value",
        "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
    },
})

# ── Fila 2: comportamiento del trafico ───────────────────────────────────────
paneles.append(fila("Comportamiento del Trafico (Golden Signals)", 7, 10))

paneles.append(serie_temporal(
    "Trafico: peticiones por segundo",
    "Segunda Golden Signal. Dos consultas porque Micrometer (Java) y "
    "prom-client (Node) nombran la metrica distinto.",
    [
        destino('sum by (job) (rate(http_server_requests_seconds_count[1m]))', "{{job}} (java)", "A"),
        destino('sum by (job) (rate(http_requests_total[1m]))', "{{job}} (node)", "B"),
    ],
    "reqps", 0, 8, 12, 8, 11))

paneles.append(serie_temporal(
    "Latencia media por servicio",
    "Primera Golden Signal: suma de duraciones dividida por numero de "
    "peticiones. La media esconde las colas, por eso al lado esta el p95.",
    [
        destino('sum by (job) (rate(http_server_requests_seconds_sum[1m]))'
                ' / sum by (job) (rate(http_server_requests_seconds_count[1m]))',
                "{{job}} (java)", "A"),
        destino('sum by (job) (rate(http_request_duration_seconds_sum[1m]))'
                ' / sum by (job) (rate(http_request_duration_seconds_count[1m]))',
                "{{job}} (node)", "B"),
    ],
    "s", 12, 8, 12, 8, 12))

paneles.append(serie_temporal(
    "Errores HTTP 4xx y 5xx",
    "Tercera Golden Signal. Separar 4xx de 5xx importa: un 4xx suele ser el "
    "cliente pidiendo algo invalido (y el sistema rechazandolo bien), "
    "mientras que un 5xx es culpa nuestra. "
    "Ventana de 5m y no de 1m porque los errores son escasos: rate() necesita "
    "al menos dos muestras en la ventana, y con 1m un error puntual no aparece.",
    [
        destino('sum by (status) (rate(http_server_requests_seconds_count{status=~"[45].."}[5m]))',
                "{{status}} (java)", "A"),
        destino('sum by (status) (rate(http_requests_total{status=~"[45].."}[5m]))',
                "{{status}} (node)", "B"),
    ],
    "reqps", 0, 16, 12, 8, 13))

paneles.append(serie_temporal(
    "Latencia p95 por servicio",
    "Percentil 95 calculado con histogram_quantile sobre los baldes del "
    "histograma. Es la metrica honesta: dice que experiencia tiene el 5% peor, "
    "mientras la media puede verse bien con un 5% de usuarios sufriendo.",
    [
        destino('histogram_quantile(0.95, sum by (job, le) '
                '(rate(http_server_requests_seconds_bucket[5m])))', "{{job}} (java)", "A"),
        destino('histogram_quantile(0.95, sum by (job, le) '
                '(rate(http_request_duration_seconds_bucket[5m])))', "{{job}} (node)", "B"),
    ],
    "s", 12, 16, 12, 8, 14))

# ── Fila 3: saturacion ────────────────────────────────────────────────────────
paneles.append(fila("Saturacion de recursos", 24, 20))

paneles.append(serie_temporal(
    "CPU del proceso",
    "Cuarta Golden Signal. Los dos runtimes publican la metrica con nombres "
    "distintos: process_cpu_usage en la JVM, process_cpu_seconds_total en Node.",
    [
        destino('process_cpu_usage{job!="prometheus"} * 100', "{{job}} (java)", "A"),
        # Prometheus tambien publica process_cpu_seconds_total, asi que sin
        # excluirlo aparecia en el panel como si fuera un microservicio.
        destino('rate(process_cpu_seconds_total{job!="prometheus"}[1m]) * 100',
                "{{job}} (node)", "B"),
    ],
    "percent", 0, 25, 12, 8, 21))

paneles.append(serie_temporal(
    "Memoria en uso",
    "Heap de la JVM y heap de Node. Un crecimiento sostenido sin bajadas "
    "apunta a una fuga; los dientes de sierra son la recoleccion de basura "
    "trabajando con normalidad.",
    [
        destino('sum by (job) (jvm_memory_used_bytes{area="heap"})', "{{job}} (java)", "A"),
        destino('nodejs_heap_size_used_bytes', "{{job}} (node)", "B"),
    ],
    "bytes", 12, 25, 12, 8, 22))

dashboard = {
    "uid": "system-hr-observabilidad",
    "title": "System_HR - Observabilidad del Ecosistema",
    "description": (
        "Estado de salud y Golden Signals de los siete microservicios. "
        "Reto 8: Observabilidad y Monitoreo."
    ),
    "tags": ["system-hr", "reto-8", "observabilidad"],
    "timezone": "browser",
    "editable": True,
    "schemaVersion": 39,
    "version": 1,
    "refresh": "10s",
    "time": {"from": "now-30m", "to": "now"},
    "timepicker": {},
    "templating": {"list": []},
    "annotations": {"list": []},
    "panels": paneles,
}

destino_archivo = 'observability/grafana/provisioning/dashboards/system-hr-observabilidad.json'
io.open(destino_archivo, 'w', encoding='utf-8', newline='\n').write(
    json.dumps(dashboard, indent=2, ensure_ascii=True) + '\n')

print('Escrito %s' % destino_archivo)
print('Paneles: %d (%d filas, %d graficos)' % (
    len(paneles),
    sum(1 for p in paneles if p['type'] == 'row'),
    sum(1 for p in paneles if p['type'] != 'row')))
