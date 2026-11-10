"""Genera las cuatro colecciones corregidas; no incluye contraseñas ni tokens reales."""
import json
from pathlib import Path
from urllib.parse import quote

ROOT = Path(__file__).resolve().parent
BASE = '{{gatewayBaseUrl}}'
ADMIN = {'type': 'bearer', 'bearer': [{'key': 'token', 'value': '{{pendAdminToken}}', 'type': 'string'}]}
NOAUTH = {'type': 'noauth'}
GRAFANA = {'type': 'basic', 'basic': [
    {'key': 'username', 'value': '{{grafanaUser}}', 'type': 'string'},
    {'key': 'password', 'value': '{{grafanaPassword}}', 'type': 'string'}]}

INIT = """
const run = pm.variables.replaceIn('{{$guid}}').replace(/-/g, '').slice(0, 12);
const values = {
  pendRunId: run, pendDepartamentoId: 'DP-' + run,
  pendEmpleadoId: 'EP-' + run, pendEmpleadoEmail: 'ep-' + run + '@example.test',
  pendBordeId: 'EB-' + run, pendBordeEmail: 'eb-' + run + '@example.test',
  pendPassword: 'Demo9!-' + run, pendHoy: new Date().toISOString().slice(0, 10)
};
for (const [key, value] of Object.entries(values)) pm.collectionVariables.set(key, value);
for (const [key, offset] of [['pendInicio', 5], ['pendFin', 8]]) {
  const date = new Date(); date.setUTCDate(date.getUTCDate() + offset);
  pm.collectionVariables.set(key, date.toISOString().slice(0, 10));
}
for (const key of ['pendAdminToken', 'pendUserToken', 'pendResetToken', 'pendPeriodoId', 'pendBordePeriodoId', 'pendMessageId']) {
  pm.collectionVariables.unset(key);
}
"""


def event(listen, source):
    return {'listen': listen, 'script': {'type': 'text/javascript', 'exec': source.strip().splitlines()}}


def request(name, method, path, status=200, body=None, test='', pre='', auth=None, headers=None):
    raw = path if path.startswith('{{') else BASE + path
    req = {'method': method, 'url': raw, 'header': []}
    if auth is not None:
        req['auth'] = auth
    if body is not None:
        req['header'].append({'key': 'Content-Type', 'value': 'application/json'})
        req['body'] = {'mode': 'raw', 'raw': json.dumps(body, ensure_ascii=False, indent=2),
                       'options': {'raw': {'language': 'json'}}}
    req['header'] += [{'key': key, 'value': value} for key, value in (headers or {}).items()]
    item = {'name': name, 'request': req, 'event': []}
    if pre:
        item['event'].append(event('prerequest', pre))
    assertion = f"pm.test('HTTP {status}', () => pm.response.to.have.status({status}));\n" if status else ''
    if assertion or test:
        item['event'].append(event('test', assertion + test))
    return item


def collection(name, items, extra_vars=None, description=''):
    variables = {'gatewayBaseUrl': 'http://localhost:8080', 'adminUsuario': 'admin@empresa.com',
                 'adminPassword': '', **(extra_vars or {})}
    return {'info': {'name': name, 'schema': 'https://schema.getpostman.com/json/collection/v2.1.0/collection.json',
                     'description': description or 'Ejecutar completa con Collection Runner. Datos únicos en cada ejecución; accesos de negocio por Gateway. Configure adminPassword en el entorno. Las esperas verifican resultados asíncronos reales.'},
            'auth': ADMIN, 'variable': [{'key': key, 'value': value, 'type': 'string'} for key, value in variables.items()],
            'item': items}


def poll(req_js, condition, name, success='', attempts=60):
    """Espera por condición real sin marcar como correctos resultados intermedios."""
    return f"""
(() => {{
  let tries = 0;
  function check() {{
    pm.sendRequest({req_js}, (error, response) => {{
      let ready = false;
      try {{ ready = !error && ({condition}); }} catch (_) {{}}
      if (ready) {{
        {success}
        pm.test({json.dumps(name)}, () => pm.expect(ready).to.eql(true));
        return;
      }}
      if (++tries < {attempts}) {{ setTimeout(check, 500); return; }}
      pm.test({json.dumps(name)}, () => {{ throw new Error('No se alcanzó la condición esperada dentro del tiempo de espera'); }});
    }});
  }}
  check();
}})();
"""


def get_api(path):
    return "{url:pm.variables.get('gatewayBaseUrl')+" + json.dumps(path) + ",header:{Authorization:'Bearer '+pm.collectionVariables.get('pendAdminToken')}}"


def balance_wait(prefix='pendEmpleado'):
    return poll("{url:pm.variables.get('gatewayBaseUrl')+'/vacaciones/balances/'+pm.collectionVariables.get('" + prefix + "Id'),header:{Authorization:'Bearer '+pm.collectionVariables.get('pendAdminToken')}}",
                'response.code===200', 'La réplica de Vacaciones está disponible')


def login_wait(status, prefix='pendEmpleado'):
    req = "{url:pm.variables.get('gatewayBaseUrl')+'/auth/login',method:'POST',header:{'Content-Type':'application/json'},body:{mode:'raw',raw:JSON.stringify({usuario:pm.collectionVariables.get('" + prefix + "Email'),password:pm.collectionVariables.get('pendPassword')})}}"
    return poll(req, f'response.code==={status}', 'El cambio de cuenta llegó a Auth')


def login_admin():
    return request('01 Login ADMIN y preparar datos únicos', 'POST', '/auth/login',
                   body={'usuario': '{{adminUsuario}}', 'password': '{{adminPassword}}'}, auth=NOAUTH, pre=INIT,
                   test="if(pm.response.code===200) pm.collectionVariables.set('pendAdminToken',pm.response.json().token);")


def department():
    return request('02 Crear departamento único', 'POST', '/departamentos', 201,
                   {'id': '{{pendDepartamentoId}}', 'nombre': 'Demo {{pendRunId}}', 'descripcion': 'Prueba repetible'})


def employee_body(prefix='pendEmpleado', suffix=''):
    return {'id': '{{' + prefix + 'Id}}' + suffix, 'nombre': 'Demo', 'apellido': 'Prueba',
            'email': ('rec-{{pendRunId}}@example.test' if suffix else '{{' + prefix + 'Email}}'),
            'numeroEmpleado': 'NUM-{{pendRunId}}-' + prefix + suffix, 'cargo': 'Analista', 'area': 'Tecnología',
            'departamentoId': '{{pendDepartamentoId}}', 'fechaIngreso': '{{pendHoy}}', 'estado': 'ACTIVO'}


def employee(prefix='pendEmpleado'):
    return request('Crear empleado ' + prefix, 'POST', '/empleados', 201, employee_body(prefix),
                   test='if(pm.response.code===201) {' + balance_wait(prefix) + '}')


def activate(prefix='pendEmpleado'):
    req = "{url:pm.variables.get('gatewayBaseUrl')+'/auth/internal/tokens/restablecimiento',method:'POST',header:{Authorization:'Bearer '+pm.collectionVariables.get('pendAdminToken'),'Content-Type':'application/json'},body:{mode:'raw',raw:JSON.stringify({empleadoId:pm.collectionVariables.get('" + prefix + "Id')})}}"
    pre = "pm.collectionVariables.unset('pendResetToken');\n" + poll(req, 'response.code===200',
              'Auth ya permite emitir el token ADMIN', "pm.collectionVariables.set('pendResetToken',response.json().token);")
    return request('Activar cuenta ' + prefix + ' mediante Auth ADMIN', 'POST', '/auth/reset-password',
                   body={'token': '{{pendResetToken}}', 'nuevaPassword': '{{pendPassword}}'}, auth=NOAUTH, pre=pre)


def login_user(status=200, prefix='pendEmpleado', save=False):
    return request('Login ' + prefix + ': esperado ' + str(status), 'POST', '/auth/login', status,
                   {'usuario': '{{' + prefix + 'Email}}', 'password': '{{pendPassword}}'}, auth=NOAUTH,
                   test="if(pm.response.code===200) pm.collectionVariables.set('pendUserToken',pm.response.json().token);" if save else '')


def vacation(prefix='pendEmpleado', period='pendPeriodoId'):
    return request('Programar vacaciones ' + prefix, 'POST', '/vacaciones', 201,
                   {'empleadoId': '{{' + prefix + 'Id}}', 'fechaInicio': '{{pendInicio}}', 'fechaFin': '{{pendFin}}'},
                   test="if(pm.response.code===201) pm.collectionVariables.set('" + period + "',(pm.response.json().data||pm.response.json()).id);")


def force(start=True, prefix='pendEmpleado', period='pendPeriodoId', wait=True):
    return request('Forzar ' + ('inicio' if start else 'fin') + ' ' + prefix, 'POST',
                   '/vacaciones/{{' + period + '}}/' + ('forzar-inicio' if start else 'forzar-fin'),
                   test=('if(pm.response.code===200) {' + login_wait(401 if start else 200, prefix) + '}') if wait else '')


def retire(prefix='pendEmpleado'):
    return request('Retirar empleado ' + prefix, 'DELETE', '/empleados/{{' + prefix + 'Id}}',
                   test='if(pm.response.code===200) {' + login_wait(401, prefix) + '}')


def ciclo_vida():
    return collection('Ciclo de vida de cuenta - repetible', [login_admin(), department(), employee(), activate(),
        login_user(), vacation(), force(), login_user(401), force(False), login_user(),
        employee('pendBorde'), activate('pendBorde'), login_user(prefix='pendBorde'),
        vacation('pendBorde', 'pendBordePeriodoId'), force(True, 'pendBorde', 'pendBordePeriodoId'),
        retire('pendBorde'), force(False, 'pendBorde', 'pendBordePeriodoId', wait=False), login_user(401, 'pendBorde')])


def reto7():
    user = {'type': 'bearer', 'bearer': [{'key': 'token', 'value': '{{pendUserToken}}', 'type': 'string'}]}
    return collection('Reto 7 - demostración funcional del sistema', [
        request('Salud del Gateway', 'GET', '/health', auth=NOAUTH),
        request('Sin token no se leen empleados', 'GET', '/empleados', 401, auth=NOAUTH),
        login_admin(), department(), employee(),
        request('Consultar empleado', 'GET', '/empleados/{{pendEmpleadoId}}'),
        request('Consultar perfil replicado', 'GET', '/perfiles/{{pendEmpleadoId}}'),
        request('Consultar el evento de bienvenida sin exponer su token', 'GET', '/notificaciones/empleado/{{pendEmpleadoId}}',
                test=poll("{url:pm.variables.get('gatewayBaseUrl')+'/notificaciones/empleado/'+pm.collectionVariables.get('pendEmpleadoId'),header:{Authorization:'Bearer '+pm.collectionVariables.get('pendAdminToken')}}",
                          "response.code===200 && response.json().some(event=>event.eventType==='usuario.creado')",
                          'Notificaciones recibió usuario.creado')),
        activate(), login_user(save=True),
        request('USER no puede crear departamentos', 'POST', '/departamentos', 403,
                {'id': 'PROHIBIDO-{{pendRunId}}', 'nombre': 'No permitido', 'descripcion': 'RBAC'}, auth=user),
        vacation(), request('Consultar período', 'GET', '/vacaciones/{{pendPeriodoId}}'),
        force(), login_user(401), force(False), login_user(), retire(), login_user(401),
        request('Consultar retirados', 'GET', '/empleados?estado=RETIRADO&departamentoId={{pendDepartamentoId}}', test="pm.test('Incluye el empleado retirado',()=>{const b=pm.response.json();const rows=Array.isArray(b)?b:b.data;pm.expect(rows.some(x=>x.id===pm.collectionVariables.get('pendEmpleadoId'))).to.eql(true);});")])


def notifications():
    history = poll("{url:pm.variables.get('gatewayBaseUrl')+'/notificaciones/empleado/'+pm.collectionVariables.get('pendEmpleadoId'),header:{Authorization:'Bearer '+pm.collectionVariables.get('pendAdminToken')}}",
                   'response.code===200 && Array.isArray(response.json()) && response.json().length>0',
                   'El historial del empleado está disponible', "pm.collectionVariables.set('pendMessageId',response.json()[0].messageId);")
    safe = "pm.test('El historial no expone tokens',()=>{const body=pm.response.json();const rows=Array.isArray(body)?body:(body.data||[body]);for(const row of rows){pm.expect(Object.prototype.hasOwnProperty.call(row.payload?.data||{},'token')).to.eql(false);}});"
    return collection('Notificaciones - acceso por Gateway', [login_admin(), department(), employee(),
        request('Esperar una notificación propia', 'GET', '/notificaciones/empleado/{{pendEmpleadoId}}', test=history),
        request('Historial de notificaciones', 'GET', '/notificaciones', test=safe),
        request('Historial paginado', 'GET', '/notificaciones?page=1&limit=10', test=safe),
        request('Consultar por messageId real', 'GET', '/notificaciones/{{pendMessageId}}', test=safe),
        request('Rechazar escritura POST', 'POST', '/notificaciones', 405, {}),
        request('Rechazar eliminación', 'DELETE', '/notificaciones/{{pendMessageId}}', 405)])


def prometheus(query):
    return '{{prometheusBaseUrl}}/api/v1/query?query=' + quote(query, safe='')


def poll_url(url, condition, name, auth=False, attempts=120, success=''):
    headers = ",header:{Authorization:'Basic '+btoa(pm.variables.get('grafanaUser')+':'+pm.variables.get('grafanaPassword'))}" if auth else ''
    return poll("{url:pm.variables.replaceIn(" + json.dumps(url) + ")" + headers + '}', condition, name, success=success, attempts=attempts)


def observabilidad():
    up = prometheus('up{job!="prometheus"}')
    target = prometheus('up{job="departments-service"}')
    alerts = '{{grafanaBaseUrl}}/api/alertmanager/grafana/api/v2/alerts'
    is_dept_alert = "response.json().some(a=>a.labels?.job==='departments-service' || a.labels?.service==='departments-service')"
    trace_url = '{{zipkinBaseUrl}}/api/v2/trace/{{pendTraceId}}'
    services = ['api-gateway', 'employees-service', 'departments-service', 'auth-service', 'profiles-service', 'vacations-service', 'notifications-service']
    trace_ready = "response.code===200 && " + json.dumps(services) + ".every(s=>response.json().some(span=>span.localEndpoint?.serviceName===s))"
    traffic = prometheus('sum by (job) (rate(http_server_requests_seconds_count[5m])) or sum by (job) (rate(http_requests_total[5m]))')
    latency = prometheus('sum(rate(http_server_requests_seconds_sum{job="api-gateway"}[5m])) / sum(rate(http_server_requests_seconds_count{job="api-gateway"}[5m]))')
    loki_trace = '{{lokiBaseUrl}}/loki/api/v1/query_range?query=' + quote('{job="docker"} | json | traceId = "{{pendTraceId}}"', safe='{}') + '&limit=100'
    loki_services = '{{lokiBaseUrl}}/loki/api/v1/label/service/values'
    zipkin_services = '{{zipkinBaseUrl}}/api/v2/services'
    trace_pre = """
const hex = () => pm.variables.replaceIn('{{$guid}}').replace(/-/g,'');
const traceId = hex();
pm.collectionVariables.set('pendTraceId', traceId);
pm.collectionVariables.set('pendTraceparent','00-'+traceId+'-'+hex().slice(0,16)+'-01');
"""
    onboarding = employee()
    onboarding['event'].insert(0, event('prerequest', trace_pre))
    onboarding['request']['header'].append({'key': 'traceparent', 'value': '{{pendTraceparent}}'})
    normal = [login_admin(), department(), onboarding]
    normal += [request('Tráfico ' + path, 'GET', path) for path in ['/empleados', '/departamentos', '/notificaciones', '/perfiles']]
    normal += [request('Error 404 conocido', 'GET', '/empleados/NO-EXISTE-{{pendRunId}}', 404),
        request('Los siete servicios están UP', 'GET', up, auth=NOAUTH,
                test=poll_url(up, "response.code===200 && response.json().data.result.length===7 && response.json().data.result.every(x=>Number(x.value[1])===1)", 'Siete objetivos de negocio disponibles')),
        request('Tráfico registrado en Prometheus', 'GET', traffic, auth=NOAUTH,
                test=poll_url(traffic, "response.code===200 && response.json().data.result.length>0", 'Hay métricas de tráfico')),
        request('Latencia media del Gateway', 'GET', latency, auth=NOAUTH,
                test=poll_url(latency, "response.code===200 && response.json().data.result.some(x=>Number.isFinite(Number(x.value[1])))", 'Hay muestras de latencia',
                              success="pm.test('Latencia media menor al umbral de alerta de 2 segundos',()=>{const result=response.json().data.result;pm.expect(result.length).to.be.greaterThan(0);pm.expect(Number(result[0].value[1])).to.be.below(2);});")),
        request('La traza de onboarding contiene los siete servicios', 'GET', trace_url, auth=NOAUTH,
                test=poll_url(trace_url, trace_ready, 'Cadena distribuida completa en Zipkin')),
        request('Zipkin conoce los siete servicios', 'GET', zipkin_services, auth=NOAUTH,
                test=poll_url(zipkin_services, 'response.code===200 && ' + json.dumps(services) + '.every(s=>response.json().includes(s))', 'Siete servicios instrumentados')),
        request('Logs de la traza', 'GET', loki_trace, auth=NOAUTH,
                test=poll_url(loki_trace, "response.code===200 && response.json().data.result.flatMap(stream=>stream.values).length>0", 'Hay logs de la traza', success="""
const lines=response.json().data.result.flatMap(stream=>stream.values.map(value=>value[1]));
pm.test('Logs JSON con los campos del contrato',()=>{
  pm.expect(lines.length).to.be.greaterThan(0);
  const record=JSON.parse(lines[0]);
  for(const key of ['timestamp','level','service','traceId','message']) pm.expect(record).to.have.property(key);
});
pm.test('Varios servicios comparten la traza en sus logs',()=>{
  const services=new Set(lines.map(line=>{try{return JSON.parse(line).service;}catch(_){return undefined;}}).filter(Boolean));
  pm.expect(services.size).to.be.greaterThan(1);
});
""")),
        request('Loki recoge los siete servicios', 'GET', loki_services, auth=NOAUTH,
                test=poll_url(loki_services, 'response.code===200 && ' + json.dumps(services) + '.every(s=>response.json().data.includes(s))', 'Siete servicios con logs recolectados')),
        request('Dashboard aprovisionado', 'GET', '{{grafanaBaseUrl}}/api/dashboards/uid/system-hr-observabilidad', auth=GRAFANA,
                test="pm.test('Dashboard correcto',()=>pm.expect(pm.response.json().dashboard.uid).to.eql('system-hr-observabilidad'));"),
        request('Reglas de alerta aprovisionadas', 'GET', '{{grafanaBaseUrl}}/api/v1/provisioning/alert-rules', auth=GRAFANA,
                test="pm.test('Hay reglas',()=>pm.expect(pm.response.json().length).to.be.greaterThan(0));"),
        request('Canales de alerta aprovisionados', 'GET', '{{grafanaBaseUrl}}/api/v1/provisioning/contact-points', auth=GRAFANA,
                test="pm.test('El punto de contacto Discord está configurado',()=>pm.expect(pm.response.json().some(c=>c.type==='discord')).to.eql(true));")]
    outage_body = employee_body(suffix='-CAIDA')
    outage_body['departamentoId'] = 'SIN-CACHE-{{pendRunId}}'
    down = [request('Prometheus detecta Departamentos caído', 'GET', target, auth=NOAUTH,
                    test=poll_url(target, "response.code===200 && response.json().data.result.some(x=>Number(x.value[1])===0)", 'up=0 observado')),
            request('Grafana muestra su alerta activa', 'GET', alerts, auth=GRAFANA,
                    test=poll_url(alerts, 'response.code===200 && ' + is_dept_alert, 'Alerta de Departamentos activa', auth=True, attempts=360)),
            request('Una consulta sin caché recibe 503', 'POST', '/empleados', 503, outage_body)]
    recovery = [request('Prometheus detecta recuperación', 'GET', target, auth=NOAUTH,
                        test=poll_url(target, "response.code===200 && response.json().data.result.some(x=>Number(x.value[1])===1)", 'up=1 observado')),
                request('La operación de negocio vuelve a funcionar', 'POST', '/empleados', 201, employee_body(suffix='-REC'))]
    return collection('Observabilidad - operación y caída controlada', [
        {'name': '01 Funcionamiento', 'item': normal},
        {'name': '02 Caida controlada', 'item': down},
        {'name': '03 Recuperacion', 'item': recovery}],
        {'prometheusBaseUrl': 'http://localhost:9090', 'zipkinBaseUrl': 'http://localhost:9411',
         'lokiBaseUrl': 'http://localhost:3100', 'grafanaBaseUrl': 'http://localhost:3000',
         'grafanaUser': 'admin', 'grafanaPassword': ''},
        'Ejecute las tres carpetas mediante scripts/probar-postman-pendientes.py --caida-departamentos. El comando conserva las variables de esta colección entre fases, detiene Departamentos y lo restaura en finally. Para una revisión sin caída, ejecute solo 01 Funcionamiento. No ejecute las carpetas de caída con el servicio activo.')


def write(name, data):
    (ROOT / (name + '.postman_collection.json')).write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    for name, factory in [('ciclo-vida-cuenta', ciclo_vida), ('reto7-sustentacion', reto7),
                          ('notificaciones-service', notifications), ('observabilidad-reto8', observabilidad)]:
        write(name, factory())
        print('Generada: ' + name)
