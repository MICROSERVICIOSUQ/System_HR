"""Genera la colección del flujo completo de System_HR y su entorno.

Un recorrido numerado por todo el sistema, en el orden en el que ocurren las
cosas: catálogo, alta del empleado, activación de su cuenta, perfil, vacaciones,
notificaciones, vista agregada, seguridad, observabilidad y baja.

Cada petición lleva por nombre lo que hace el endpoint, no la ruta.

Dos decisiones que explican casi todo lo demás:

1. **Cada ejecución inventa sus propios identificadores.** Se derivan del reloj
   en la primera petición. Una colección con identificadores fijos solo pasa la
   primera vez: la segunda choca con los duplicados que dejó la anterior.

2. **Los efectos asíncronos se esperan comprobando una condición, nunca con una
   pausa fija.** El alta de un empleado desencadena una cadena por RabbitMQ
   —cuenta, perfil, saldo de vacaciones, correo— y desde que los servicios
   publican con outbox el evento sale hasta un segundo después de la respuesta
   HTTP. Un `sleep` convierte eso en una prueba frágil; un sondeo con tope lo
   convierte en una aserción real.
"""
import collections
import io
import json

OD = collections.OrderedDict

ENTORNO = 'system-hr-flujo-completo.postman_environment.json'
COLECCION = 'flujo-completo.postman_collection.json'

# ─── Guiones reutilizables ───────────────────────────────────────────────────

def sondeo(descripcion, peticion_js, condicion_js, al_lograrlo='', intentos=60):
    """Prerequest que repite una consulta hasta que se cumple la condición.

    Mismo patrón en toda la colección: hasta `intentos` consultas cada 500 ms,
    es decir 30 segundos de margen. Si no se cumple, el `test` de la petición
    falla por sí mismo; aquí solo se deja constancia en la consola.
    """
    return f"""// Espera a que {descripcion}.
//
// Es un efecto asíncrono: viaja por RabbitMQ y el publicador lo envía en su
// siguiente ciclo. Se comprueba una condición real, con un tope de 30 s.
const base  = pm.environment.get('gatewayBaseUrl') || pm.variables.get('gatewayBaseUrl');
const admin = pm.collectionVariables.get('tokenAdmin');

(() => {{
  let intentos = 0;
  function comprobar() {{
    {peticion_js}
    , (error, res) => {{
      let listo = false;
      try {{ listo = !error && ({condicion_js}); }} catch (_) {{}}
      if (listo) {{ {al_lograrlo} return; }}
      if (++intentos < {intentos}) {{ setTimeout(comprobar, 500); return; }}
      console.log('No se cumplió la condición esperada en 30 s: {descripcion}');
    }});
  }}
  comprobar();
}})();"""


GET_JS = """pm.sendRequest({
      url: base + {ruta},
      method: 'GET',
      header: {{ Authorization: 'Bearer ' + admin, Accept: 'application/json' }}
    }}"""


def sondeo_get(descripcion, ruta_js, condicion_js, al_lograrlo='', intentos=60):
    peticion = ("pm.sendRequest({\n"
                "      url: base + " + ruta_js + ",\n"
                "      method: 'GET',\n"
                "      header: { Authorization: 'Bearer ' + admin, Accept: 'application/json' }\n"
                "    }")
    return sondeo(descripcion, peticion, condicion_js, al_lograrlo, intentos)


# ─── Definición de las peticiones ────────────────────────────────────────────

def peticion(nombre, metodo, ruta, descripcion, test, cuerpo=None,
             prerequest=None, token='admin', cabeceras=None,
             acepta='application/json'):
    """Construye una petición de Postman.

    `token` elige qué identidad usa: 'admin', 'user', 'ninguno' o un literal.
    `acepta` es el valor de la cabecera Accept; hay que cambiarlo para las
    respuestas que no son JSON, como el formato de texto de Prometheus, porque
    pedir solo JSON ahí devuelve 406.
    """
    cabeceras_finales = []
    if token == 'admin':
        cabeceras_finales.append(('Authorization', 'Bearer {{tokenAdmin}}'))
    elif token == 'user':
        cabeceras_finales.append(('Authorization', 'Bearer {{tokenUsuario}}'))
    elif token not in (None, 'ninguno'):
        cabeceras_finales.append(('Authorization', token))
    if cuerpo is not None:
        cabeceras_finales.append(('Content-Type', 'application/json'))
    cabeceras_finales.append(('Accept', acepta))
    for clave, valor in (cabeceras or []):
        cabeceras_finales.append((clave, valor))

    eventos = []
    if prerequest:
        eventos.append(OD([("listen", "prerequest"),
                           ("script", OD([("type", "text/javascript"),
                                          ("exec", prerequest.split('\n'))]))]))
    eventos.append(OD([("listen", "test"),
                       ("script", OD([("type", "text/javascript"),
                                      ("exec", test.split('\n'))]))]))

    item = OD()
    item["name"] = nombre
    item["event"] = eventos
    solicitud = OD()
    solicitud["method"] = metodo
    solicitud["header"] = [OD([("key", k), ("value", v)]) for k, v in cabeceras_finales]
    if cuerpo is not None:
        solicitud["body"] = OD([("mode", "raw"),
                                ("raw", json.dumps(cuerpo, indent=2, ensure_ascii=False)),
                                ("options", OD([("raw", OD([("language", "json")]))]))])
    solicitud["url"] = OD([("raw", "{{gatewayBaseUrl}}" + ruta),
                           ("host", ["{{gatewayBaseUrl}}"]),
                           ("path", [p for p in ruta.split('/') if p])])
    solicitud["description"] = descripcion
    item["request"] = solicitud
    return item


def carpeta(nombre, descripcion, items):
    return OD([("name", nombre), ("description", descripcion), ("item", items)])


CARPETAS = []

# ── 00 Preparación ──────────────────────────────────────────────────────────
CARPETAS.append(carpeta(
    '00 Preparación',
    'Autenticación del administrador y datos propios de esta ejecución. Todo lo '
    'demás depende de estos dos pasos.',
    [
        peticion(
            '00.1 Iniciar sesión como administrador y guardar su token',
            'POST', '/auth/login',
            'Único endpoint público que entrega un Access JWT. El administrador '
            'semilla lo crea auth-service al arrancar. Aquí se generan además los '
            'identificadores de esta ejecución, para que la colección pueda '
            'repetirse sobre la misma base de datos.',
            token='ninguno',
            cuerpo={"usuario": "{{adminUsuario}}", "password": "{{adminPassword}}"},
            test="""pm.test('Responde 200 con un Access JWT', () => {
    pm.response.to.have.status(200);
    const b = pm.response.json();
    pm.expect(b.tipo).to.eql('Bearer');
    pm.expect(b.rol).to.eql('ADMIN');
    pm.expect(b.token.split('.')).to.have.lengthOf(3);
});

const cuerpo = pm.response.json();
pm.collectionVariables.set('tokenAdmin', cuerpo.token);

// El payload de un JWT es Base64url, no va cifrado: se puede leer.
const claims = JSON.parse(atob(cuerpo.token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
pm.test('El token declara el rol y el sujeto, y caduca', () => {
    pm.expect(claims.role).to.eql('ADMIN');
    pm.expect(claims.sub).to.eql(cuerpo.empleadoId);
    pm.expect(claims.exp).to.be.a('number');
});

// ── Identificadores únicos de esta ejecución ───────────────────────────────
const sufijo = String(Date.now()).slice(-9);
pm.collectionVariables.set('sufijo', sufijo);
pm.collectionVariables.set('departamentoId', 'DF' + sufijo.slice(-4));
pm.collectionVariables.set('empleadoId', 'EF' + sufijo.slice(-5));
pm.collectionVariables.set('empleadoEmail', 'flujo.' + sufijo + '@empresa.com');
pm.collectionVariables.set('empleadoNumero', 'EMP-F-' + sufijo);
pm.collectionVariables.set('companeroId', 'EC' + sufijo.slice(-5));
pm.collectionVariables.set('companeroEmail', 'companero.' + sufijo + '@empresa.com');
pm.collectionVariables.set('companeroNumero', 'EMP-C-' + sufijo);
console.log('Datos de esta ejecución: departamento ' + pm.collectionVariables.get('departamentoId')
            + ', empleado ' + pm.collectionVariables.get('empleadoId'));"""),

        peticion(
            '00.2 Rechazar unas credenciales incorrectas',
            'POST', '/auth/login',
            'La contraparte del paso anterior: una contraseña equivocada no '
            'distingue entre "no existe" y "no coincide", para no revelar qué '
            'correos están registrados.',
            token='ninguno',
            cuerpo={"usuario": "{{adminUsuario}}", "password": "esta-no-es"},
            test="""pm.test('Responde 401 sin revelar el motivo exacto', () => {
    pm.response.to.have.status(401);
    pm.expect(pm.response.json().message).to.include('Credenciales');
});"""),
    ]))

# ── 01 Departamentos ────────────────────────────────────────────────────────
CARPETAS.append(carpeta(
    '01 Catálogo de departamentos',
    'CRUD completo del catálogo organizacional, en Python con FastAPI. Es la '
    'fuente de verdad que employees-service consulta antes de dar de alta a '
    'cualquier empleado.',
    [
        peticion(
            '01.1 Crear un departamento',
            'POST', '/departamentos',
            'El identificador se puede enviar o dejar que lo genere la secuencia '
            'de PostgreSQL. Aquí se envía para poder referenciarlo después.',
            cuerpo={"id": "{{departamentoId}}", "nombre": "Ingeniería {{sufijo}}",
                    "descripcion": "Departamento creado por la colección de flujo completo"},
            test="""pm.test('Responde 201 con el departamento creado', () => {
    pm.response.to.have.status(201);
    const d = pm.response.json();
    pm.expect(d.id).to.eql(pm.collectionVariables.get('departamentoId'));
    pm.expect(d.nombre).to.include('Ingeniería');
});

pm.test('Las fechas salen en ISO-8601 con sufijo Z', () => {
    const d = pm.response.json();
    pm.expect(d.createdAt).to.match(/Z$/);
    pm.expect(d.updatedAt).to.match(/Z$/);
});"""),

        peticion(
            '01.2 Rechazar un departamento con el mismo identificador',
            'POST', '/departamentos',
            'La unicidad se comprueba en dos capas: una consulta previa y la '
            'restricción UNIQUE de la base de datos. El cuerpo va completo a '
            'propósito: con un campo obligatorio ausente respondería 400 por la '
            'validación y el conflicto nunca se llegaría a comprobar.',
            cuerpo={"id": "{{departamentoId}}", "nombre": "Duplicado",
                    "descripcion": "Intento de duplicar el identificador"},
            test="""pm.test('Responde 409 por conflicto', () => pm.response.to.have.status(409));
pm.test('El mensaje explica que el departamento ya existe', () => {
    pm.expect(pm.response.json().message).to.include('Ya existe');
});"""),

        peticion(
            '01.3 Rechazar un departamento sin nombre',
            'POST', '/departamentos',
            'La validación la genera el propio modelo Pydantic, el mismo del que '
            'sale el contrato OpenAPI. Responde 400, no el 422 de FastAPI, para '
            'no cambiar el contrato que ya usaban los clientes.',
            cuerpo={"descripcion": "Sin nombre"},
            test="""pm.test('Responde 400 con el detalle del campo', () => {
    pm.response.to.have.status(400);
    const b = pm.response.json();
    pm.expect(b.status).to.eql('error');
    pm.expect(b.errors[0].field).to.eql('nombre');
});"""),

        peticion(
            '01.4 Consultar un departamento por su identificador',
            'GET', '/departamentos/{{departamentoId}}',
            'Lectura directa del catálogo.',
            test="""pm.test('Responde 200 con el departamento', () => {
    pm.response.to.have.status(200);
    pm.expect(pm.response.json().id).to.eql(pm.collectionVariables.get('departamentoId'));
});"""),

        peticion(
            '01.5 Consultar un departamento inexistente',
            'GET', '/departamentos/NO-EXISTE',
            'Un 404 aquí es información, no un fallo: employees-service lo usa '
            'para distinguir "el departamento no existe" de "no pude preguntar".',
            test="""pm.test('Responde 404 con un mensaje explícito', () => {
    pm.response.to.have.status(404);
    pm.expect(pm.response.json().message).to.include('no existe');
});"""),

        peticion(
            '01.6 Listar todos los departamentos',
            'GET', '/departamentos',
            'Sin parámetros devuelve un array. La forma importa: '
            'employees-service comprueba la longitud de esta lista para saber si '
            'un departamento tiene empleados.',
            test="""pm.test('Responde 200 con un array', () => {
    pm.response.to.have.status(200);
    pm.expect(pm.response.json()).to.be.an('array');
});"""),

        peticion(
            '01.7 Listar departamentos paginados y ordenados por nombre',
            'GET', '/departamentos?page=1&limit=5&sortBy=nombre&order=ASC',
            'Con cualquier parámetro de paginación la respuesta cambia de forma: '
            'pasa a ser un objeto con data y meta.',
            test="""pm.test('Responde 200 con data y meta', () => {
    pm.response.to.have.status(200);
    const b = pm.response.json();
    pm.expect(b.data).to.be.an('array');
    pm.expect(b.meta).to.have.all.keys('total', 'page', 'limit', 'totalPages');
    pm.expect(b.data.length).to.be.at.most(5);
});"""),

        peticion(
            '01.8 Actualizar el nombre y la descripción del departamento',
            'PUT', '/departamentos/{{departamentoId}}',
            'PUT y PATCH se comportan igual: aplican solo los campos enviados.',
            cuerpo={"nombre": "Ingeniería y Plataforma {{sufijo}}",
                    "descripcion": "Nombre actualizado por la colección"},
            test="""pm.test('Responde 200 con el cambio aplicado', () => {
    pm.response.to.have.status(200);
    pm.expect(pm.response.json().nombre).to.include('Plataforma');
});"""),
    ]))

# ── 02 Empleados ────────────────────────────────────────────────────────────
CARPETAS.append(carpeta(
    '02 Alta de empleados',
    'El registro de un empleado valida su departamento por HTTP contra el '
    'catálogo y publica "empleado.creado". Ese evento desencadena toda la cadena '
    'que se comprueba en las carpetas siguientes.',
    [
        peticion(
            '02.1 Registrar un empleado',
            'POST', '/empleados',
            'Antes de guardar, employees-service comprueba el email, el número de '
            'empleado y el identificador, y después valida el departamento contra '
            'departments-service a través de un circuit breaker. El evento se '
            'escribe en la misma transacción que el empleado (patrón outbox), así '
            'que no puede guardarse uno sin el otro.',
            cuerpo={"id": "{{empleadoId}}", "nombre": "Laura", "apellido": "Méndez",
                    "email": "{{empleadoEmail}}", "numeroEmpleado": "{{empleadoNumero}}",
                    "cargo": "Desarrolladora", "area": "Tecnología",
                    "departamentoId": "{{departamentoId}}", "fechaIngreso": "2026-04-15"},
            test="""pm.test('Responde 201 con el empleado activo', () => {
    pm.response.to.have.status(201);
    const e = pm.response.json();
    pm.expect(e.id).to.eql(pm.collectionVariables.get('empleadoId'));
    pm.expect(e.estado).to.eql('ACTIVO');
    pm.expect(e.fechaRetiro).to.be.null;
});

pm.test('Devuelve los campos del modelo canónico', () => {
    const e = pm.response.json();
    ['id', 'nombre', 'apellido', 'email', 'numeroEmpleado', 'cargo', 'area',
     'departamentoId', 'fechaIngreso', 'estado'].forEach(c =>
        pm.expect(e, 'falta ' + c).to.have.property(c));
});

pm.test('employeeId es un alias de id, conservado por compatibilidad', () => {
    const e = pm.response.json();
    pm.expect(e.employeeId).to.eql(e.id);
});"""),

        peticion(
            '02.2 Registrar un segundo empleado para las pruebas de permisos',
            'POST', '/empleados',
            'Hace falta un empleado ajeno para demostrar que un usuario no puede '
            'tocar el perfil de otro.',
            cuerpo={"id": "{{companeroId}}", "nombre": "Andrés", "apellido": "Gómez",
                    "email": "{{companeroEmail}}", "numeroEmpleado": "{{companeroNumero}}",
                    "cargo": "Analista", "area": "Tecnología",
                    "departamentoId": "{{departamentoId}}", "fechaIngreso": "2026-05-02"},
            test="""pm.test('Responde 201', () => pm.response.to.have.status(201));"""),

        peticion(
            '02.3 Rechazar un empleado con un email ya registrado',
            'POST', '/empleados',
            'El email es único en todo el sistema.',
            cuerpo={"id": "OTRO-{{sufijo}}", "nombre": "Copia", "apellido": "Email",
                    "email": "{{empleadoEmail}}", "numeroEmpleado": "EMP-X-{{sufijo}}",
                    "cargo": "Analista", "area": "Tecnología",
                    "departamentoId": "{{departamentoId}}", "fechaIngreso": "2026-04-15"},
            test="""pm.test('Responde 400 nombrando el email', () => {
    pm.response.to.have.status(400);
    pm.expect(pm.response.json().message).to.include(pm.collectionVariables.get('empleadoEmail'));
});"""),

        peticion(
            '02.4 Rechazar un empleado con un número ya registrado',
            'POST', '/empleados',
            'El número de empleado también es único.',
            cuerpo={"id": "OTRO2-{{sufijo}}", "nombre": "Copia", "apellido": "Numero",
                    "email": "otro.{{sufijo}}@empresa.com", "numeroEmpleado": "{{empleadoNumero}}",
                    "cargo": "Analista", "area": "Tecnología",
                    "departamentoId": "{{departamentoId}}", "fechaIngreso": "2026-04-15"},
            test="""pm.test('Responde 400 nombrando el número', () => {
    pm.response.to.have.status(400);
    pm.expect(pm.response.json().message).to.include(pm.collectionVariables.get('empleadoNumero'));
});"""),

        peticion(
            '02.5 Rechazar un empleado con un departamento inexistente',
            'POST', '/empleados',
            'El 404 del catálogo se traduce aquí en 400: es una regla de negocio '
            'incumplida, no una caída del servicio. La diferencia importa, porque '
            'una caída sí abriría el circuito.',
            cuerpo={"id": "OTRO3-{{sufijo}}", "nombre": "Sin", "apellido": "Departamento",
                    "email": "sindepto.{{sufijo}}@empresa.com", "numeroEmpleado": "EMP-SD-{{sufijo}}",
                    "cargo": "Analista", "area": "Tecnología",
                    "departamentoId": "NO-EXISTE", "fechaIngreso": "2026-04-15"},
            test="""pm.test('Responde 400, no 503', () => pm.response.to.have.status(400));"""),

        peticion(
            '02.6 Consultar el empleado por su identificador',
            'GET', '/empleados/{{empleadoId}}',
            'Lectura directa.',
            test="""pm.test('Responde 200 con los datos guardados', () => {
    pm.response.to.have.status(200);
    const e = pm.response.json();
    pm.expect(e.nombre).to.eql('Laura');
    pm.expect(e.estado).to.eql('ACTIVO');
});"""),

        peticion(
            '02.7 Listar empleados filtrando por departamento y paginando',
            'GET', '/empleados?departamentoId={{departamentoId}}&page=1&size=10&sortBy=fechaIngreso&order=ASC',
            'Los filtros se combinan: departamento, estado, cargo y paginación.',
            test="""pm.test('Responde 200 y solo trae empleados del departamento', () => {
    pm.response.to.have.status(200);
    const b = pm.response.json();
    const filas = Array.isArray(b) ? b : b.data;
    pm.expect(filas.length).to.be.at.least(2);
    filas.forEach(e => pm.expect(e.departamentoId).to.eql(pm.collectionVariables.get('departamentoId')));
});"""),

        peticion(
            '02.8 Actualizar el cargo del empleado',
            'PATCH', '/empleados/{{empleadoId}}',
            'Publica "empleado.actualizado", que el read model de perfiles aplica.',
            cuerpo={"cargo": "Líder técnica"},
            test="""pm.test('Responde 200 con el cargo nuevo', () => {
    pm.response.to.have.status(200);
    pm.expect(pm.response.json().cargo).to.eql('Líder técnica');
});"""),

        peticion(
            '02.9 Consultar el estado del circuit breaker hacia departamentos',
            'GET', '/empleados/circuit-breaker',
            'Expone el estado y los contadores del circuito que protege la llamada '
            'al catálogo. En operación normal está CLOSED.',
            test="""pm.test('Responde 200 con el estado del circuito', () => {
    pm.response.to.have.status(200);
    const b = pm.response.json();
    pm.expect(JSON.stringify(b)).to.include('CLOSED');
});"""),
    ]))

# ── 03 Cuenta ───────────────────────────────────────────────────────────────
TOKEN_RESTABLECIMIENTO_JS = ("pm.sendRequest({\n"
                             "      url: base + '/auth/internal/tokens/restablecimiento',\n"
                             "      method: 'POST',\n"
                             "      header: { Authorization: 'Bearer ' + admin, 'Content-Type': 'application/json' },\n"
                             "      body: { mode: 'raw', raw: JSON.stringify({ empleadoId: pm.collectionVariables.get('empleadoId') }) }\n"
                             "    }")

CARPETAS.append(carpeta(
    '03 Activación de la cuenta',
    'auth-service crea la cuenta al consumir "empleado.creado", en estado '
    'PENDIENTE_ACTIVACION y con un token de activación que viaja en el evento '
    '"usuario.creado". El token no se guarda en el historial de notificaciones: '
    'se pide a quien lo firma.',
    [
        peticion(
            '03.1 Rechazar la recuperación de una cuenta sin activar',
            'POST', '/auth/recover-password',
            'La recuperación solo se concede a cuentas ACTIVA. Permitirla sobre '
            'una cuenta recién creada convertiría un endpoint público en un '
            'generador de tokens de activación a demanda: bastaría conocer el '
            'correo. El mensaje orienta al dueño hacia el camino correcto.',
            token='ninguno',
            cuerpo={"email": "{{empleadoEmail}}"},
            prerequest=sondeo(
                'auth-service haya creado la cuenta del empleado',
                TOKEN_RESTABLECIMIENTO_JS,
                'res.code === 200'),
            test="""pm.test('Responde 401 por el estado de la cuenta', () => pm.response.to.have.status(401));
pm.test('El mensaje indica que la cuenta aún no se activó', () => {
    pm.expect(pm.response.json().message).to.include('no ha sido activada');
});"""),

        peticion(
            '03.2 Emitir el token de activación (reservado al administrador)',
            'POST', '/auth/internal/tokens/restablecimiento',
            'Canal legítimo para obtener un token de restablecimiento: lo entrega '
            'quien lo firma. Es una operación privilegiada —equivale a poder tomar '
            'el control de una cuenta— así que el gateway la reserva al rol ADMIN '
            'y auth-service registra quién la pidió.',
            cuerpo={"empleadoId": "{{empleadoId}}"},
            test="""pm.test('Responde 200 con un token de restablecimiento', () => {
    pm.response.to.have.status(200);
    const b = pm.response.json();
    pm.expect(b.empleadoId).to.eql(pm.collectionVariables.get('empleadoId'));
    pm.expect(b.tipo).to.eql('RESET_PASSWORD');
    pm.expect(b.expiraEnSegundos).to.be.a('number');
});

const token = pm.response.json().token;
pm.collectionVariables.set('tokenActivacion', token);

const cabecera = JSON.parse(atob(token.split('.')[0].replace(/-/g, '+').replace(/_/g, '/')));
const claims = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
pm.test('La cabecera declara typ=JWT y alg=HS256', () => {
    pm.expect(cabecera.typ).to.eql('JWT');
    pm.expect(cabecera.alg).to.eql('HS256');
});
pm.test('El claim type lo marca como RESET_PASSWORD y no trae rol', () => {
    pm.expect(claims.type).to.eql('RESET_PASSWORD');
    pm.expect(claims.role).to.be.undefined;
});"""),

        peticion(
            '03.3 Rechazar una contraseña que no cumple la política',
            'POST', '/auth/reset-password',
            'La política exige al menos ocho caracteres, con letras y dígitos.',
            token='ninguno',
            cuerpo={"token": "{{tokenActivacion}}", "nuevaPassword": "corta"},
            test="""pm.test('Responde 400', () => pm.response.to.have.status(400));"""),

        peticion(
            '03.4 Establecer la contraseña con el token de activación',
            'POST', '/auth/reset-password',
            'Es el "establecimiento inicial de contraseña": la cuenta pasa a '
            'ACTIVA y auth-service publica "cuenta.activada". El endpoint es '
            'público a propósito, porque la autorización la aporta el token de un '
            'solo propósito que viaja en el cuerpo.',
            token='ninguno',
            cuerpo={"token": "{{tokenActivacion}}", "nuevaPassword": "{{empleadoPassword}}"},
            test="""pm.test('Responde 200 y confirma el establecimiento', () => {
    pm.response.to.have.status(200);
    pm.expect(pm.response.json().mensaje).to.include('Contraseña');
});"""),

        peticion(
            '03.5 Iniciar sesión como el empleado y guardar su token',
            'POST', '/auth/login',
            'Primer acceso del empleado. El token que recibe lleva rol USER, que '
            'es de solo lectura salvo sobre sus propios recursos.',
            token='ninguno',
            cuerpo={"usuario": "{{empleadoEmail}}", "password": "{{empleadoPassword}}"},
            test="""pm.test('Responde 200 con un token de rol USER', () => {
    pm.response.to.have.status(200);
    const b = pm.response.json();
    pm.expect(b.rol).to.eql('USER');
    pm.expect(b.empleadoId).to.eql(pm.collectionVariables.get('empleadoId'));
});

pm.collectionVariables.set('tokenUsuario', pm.response.json().token);"""),

        peticion(
            '03.6 Cambiar su propia contraseña',
            'POST', '/auth/change-password',
            'Una de las dos escrituras que el rol USER tiene permitidas. La '
            'identidad se toma del claim sub del token, nunca del cuerpo: nadie '
            'puede cambiar la contraseña de otro por esta vía.',
            token='user',
            cuerpo={"passwordActual": "{{empleadoPassword}}", "nuevaPassword": "{{empleadoPasswordNueva}}"},
            test="""pm.test('Responde 200', () => pm.response.to.have.status(200));"""),

        peticion(
            '03.7 Rechazar el acceso con la contraseña anterior',
            'POST', '/auth/login',
            'Comprueba que el cambio tuvo efecto real sobre el hash almacenado.',
            token='ninguno',
            cuerpo={"usuario": "{{empleadoEmail}}", "password": "{{empleadoPassword}}"},
            test="""pm.test('Responde 401', () => pm.response.to.have.status(401));"""),

        peticion(
            '03.8 Iniciar sesión con la contraseña nueva',
            'POST', '/auth/login',
            'Y que la nueva sí funciona. Se guarda el token otra vez porque el '
            'anterior sigue siendo válido pero conviene usar el más reciente.',
            token='ninguno',
            cuerpo={"usuario": "{{empleadoEmail}}", "password": "{{empleadoPasswordNueva}}"},
            test="""pm.test('Responde 200', () => pm.response.to.have.status(200));
pm.collectionVariables.set('tokenUsuario', pm.response.json().token);"""),

        peticion(
            '03.9 Solicitar la recuperación de una cuenta ya activa',
            'POST', '/auth/recover-password',
            'Ahora sí procede: la cuenta está ACTIVA. Responde 202 porque la '
            'entrega del correo es asíncrona.',
            token='ninguno',
            cuerpo={"email": "{{empleadoEmail}}"},
            test="""pm.test('Responde 202, la entrega es asíncrona', () => pm.response.to.have.status(202));"""),

        peticion(
            '03.10 Rechazar la recuperación de un correo desconocido',
            'POST', '/auth/recover-password',
            'Un correo que no existe devuelve 404.',
            token='ninguno',
            cuerpo={"email": "nadie.{{sufijo}}@empresa.com"},
            test="""pm.test('Responde 404', () => pm.response.to.have.status(404));"""),
    ]))

# ── 04 Perfiles ─────────────────────────────────────────────────────────────
CARPETAS.append(carpeta(
    '04 Perfil del empleado',
    'profiles-service es un read model: no crea perfiles por iniciativa propia, '
    'los proyecta desde los eventos de empleados. Sobre esa proyección el propio '
    'empleado completa sus datos personales.',
    [
        peticion(
            '04.1 Consultar el perfil creado por el evento',
            'GET', '/perfiles/{{empleadoId}}',
            'El perfil nació de "empleado.creado", sin que nadie lo pidiera. '
            'empleadoId es el nombre canónico del campo; employeeId se conserva '
            'como alias para no romper a los clientes que ya lo leían.',
            prerequest=sondeo_get(
                'el perfil exista, proyectado desde el evento',
                "'/perfiles/' + pm.collectionVariables.get('empleadoId')",
                'res.code === 200'),
            test="""pm.test('Responde 200 con el perfil proyectado', () => {
    pm.response.to.have.status(200);
    const p = pm.response.json();
    pm.expect(p.empleadoId).to.eql(pm.collectionVariables.get('empleadoId'));
    pm.expect(p.employeeId).to.eql(p.empleadoId);
    pm.expect(p.estado).to.eql('ACTIVO');
});

pm.test('Trae los cinco campos propios del perfil', () => {
    const p = pm.response.json();
    ['telefono', 'direccion', 'ciudad', 'biografia', 'fechaCreacion'].forEach(c =>
        pm.expect(p, 'falta ' + c).to.have.property(c));
});

pm.test('Un perfil nuevo no está archivado', () => {
    pm.expect(pm.response.json().archivado).to.eql(false);
});"""),

        peticion(
            '04.2 Completar los datos personales del perfil',
            'PUT', '/perfiles/{{empleadoId}}',
            'Los cuatro campos de contacto son datos del perfil, no del empleado: '
            'employees-service no los conoce ni los publica. Por eso son '
            'opcionales y los rellena su dueño.',
            token='user',
            cuerpo={"telefono": "+57 300 000 0000", "direccion": "Calle 10 # 5-20",
                    "ciudad": "Armenia", "biografia": "Desarrolladora backend."},
            test="""pm.test('Responde 200 con los cuatro campos guardados', () => {
    pm.response.to.have.status(200);
    const p = pm.response.json();
    pm.expect(p.telefono).to.eql('+57 300 000 0000');
    pm.expect(p.direccion).to.eql('Calle 10 # 5-20');
    pm.expect(p.ciudad).to.eql('Armenia');
    pm.expect(p.biografia).to.include('backend');
});"""),

        peticion(
            '04.3 Listar únicamente los perfiles vigentes',
            'GET', '/perfiles?archivado=false&page=1&limit=10&sortBy=fechaCreacion&order=DESC',
            'El filtro de archivado es una dimensión aparte del estado: hoy '
            'coinciden, pero un perfil podría archivarse por otro motivo sin estar '
            'retirado.',
            test="""pm.test('Responde 200 y ninguno viene archivado', () => {
    pm.response.to.have.status(200);
    const b = pm.response.json();
    const filas = Array.isArray(b) ? b : b.data;
    filas.forEach(p => pm.expect(p.archivado).to.eql(false));
});"""),

        peticion(
            '04.4 Rechazar un valor no booleano en el filtro de archivado',
            'GET', '/perfiles?archivado=quizas',
            'Un filtro mal escrito que se ignorara en silencio devolvería una '
            'lista que parece correcta y no lo es.',
            test="""pm.test('Responde 400', () => pm.response.to.have.status(400));"""),

        peticion(
            '04.5 Un empleado no puede editar el perfil de otro',
            'PUT', '/perfiles/{{companeroId}}',
            'Autorización por propiedad del recurso: el permiso no depende solo '
            'del rol, sino de quién es dueño del dato. El gateway lo resuelve sin '
            'llegar al servicio.',
            token='user',
            cuerpo={"ciudad": "Intento sobre un perfil ajeno"},
            test="""pm.test('Responde 403, no 401', () => {
    pm.response.to.have.status(403);
});
pm.test('El mensaje explica que falta el permiso', () => {
    pm.expect(pm.response.json().message).to.include('no tiene permiso');
});"""),

        peticion(
            '04.6 Un empleado sí puede leer el perfil de otro',
            'GET', '/perfiles/{{companeroId}}',
            'El rol USER es de solo lectura sobre el resto del sistema: leer sí, '
            'escribir no.',
            token='user',
            prerequest=sondeo_get(
                'el perfil del compañero exista',
                "'/perfiles/' + pm.collectionVariables.get('companeroId')",
                'res.code === 200'),
            test="""pm.test('Responde 200', () => pm.response.to.have.status(200));"""),
    ]))

# ── 05 Vacaciones ───────────────────────────────────────────────────────────
CARPETAS.append(carpeta(
    '05 Vacaciones y suspensión de la cuenta',
    'El saldo de vacaciones también se proyecta desde los eventos de empleados. '
    'Programar un período no afecta a la cuenta; lo que la suspende es el inicio '
    'del período, que publica "vacaciones.iniciadas".',
    [
        peticion(
            '05.1 Consultar el saldo de vacaciones creado por el evento',
            'GET', '/vacaciones/balances/{{empleadoId}}',
            'Réplica local del empleado con su saldo. Sin ella no se puede '
            'programar nada: es la comprobación de que el evento llegó.',
            prerequest=sondeo_get(
                'el saldo de vacaciones exista',
                "'/vacaciones/balances/' + pm.collectionVariables.get('empleadoId')",
                'res.code === 200'),
            test="""pm.test('Responde 200 con el saldo inicial', () => {
    pm.response.to.have.status(200);
    const b = pm.response.json();
    pm.expect(b.diasDisponibles).to.be.a('number');
    pm.expect(b.estadoEmpleado).to.eql('ACTIVO');
});"""),

        peticion(
            '05.2 Rechazar un período cuya fecha de fin no es posterior al inicio',
            'POST', '/vacaciones',
            'fechaFin es el día de REGRESO, no el último día de descanso: un día '
            'de vacaciones son dos fechas consecutivas. Es una decisión de negocio '
            'explícita del servicio.',
            cuerpo={"empleadoId": "{{empleadoId}}", "fechaInicio": "2027-06-10", "fechaFin": "2027-06-10"},
            test="""pm.test('Responde 400', () => pm.response.to.have.status(400));
pm.test('El mensaje explica la regla', () => {
    pm.expect(pm.response.json().message).to.include('posterior a fechaInicio');
});"""),

        peticion(
            '05.3 Rechazar un período que empieza en el pasado',
            'POST', '/vacaciones',
            'No se programan vacaciones hacia atrás.',
            cuerpo={"empleadoId": "{{empleadoId}}", "fechaInicio": "2020-01-10", "fechaFin": "2020-01-15"},
            test="""pm.test('Responde 400', () => pm.response.to.have.status(400));
pm.test('El mensaje habla de una fecha pasada', () => {
    pm.expect(pm.response.json().message).to.include('pasada');
});"""),

        peticion(
            '05.4 Programar un período de vacaciones',
            'POST', '/vacaciones',
            'Cinco validaciones antes de guardar: rango, fecha no pasada, empleado '
            'existente, empleado ACTIVO y sin solapamiento. El período y su evento '
            'se guardan en la misma transacción.',
            cuerpo={"empleadoId": "{{empleadoId}}", "fechaInicio": "2027-07-05", "fechaFin": "2027-07-13"},
            test="""pm.test('Responde 201 con el período PROGRAMADA', () => {
    pm.response.to.have.status(201);
    const p = pm.response.json();
    pm.expect(p.estado).to.eql('PROGRAMADA');
    pm.expect(p.empleadoId).to.eql(pm.collectionVariables.get('empleadoId'));
});

pm.collectionVariables.set('periodoId', pm.response.json().id);"""),

        peticion(
            '05.5 Rechazar un período que se solapa con el anterior',
            'POST', '/vacaciones',
            'Dos períodos activos del mismo empleado no pueden cruzarse.',
            cuerpo={"empleadoId": "{{empleadoId}}", "fechaInicio": "2027-07-08", "fechaFin": "2027-07-20"},
            test="""pm.test('Responde 400 nombrando el solapamiento', () => {
    pm.response.to.have.status(400);
    pm.expect(pm.response.json().message).to.include('solapa');
});"""),

        peticion(
            '05.6 Consultar el período programado',
            'GET', '/vacaciones/{{periodoId}}',
            'Lectura del período por su identificador.',
            test="""pm.test('Responde 200 con el período', () => {
    pm.response.to.have.status(200);
    pm.expect(pm.response.json().id).to.eql(pm.collectionVariables.get('periodoId'));
});"""),

        peticion(
            '05.7 Reprogramar las fechas del período',
            'PATCH', '/vacaciones/{{periodoId}}',
            'Mientras está PROGRAMADA se puede mover, con las mismas validaciones.',
            cuerpo={"fechaInicio": "2027-07-12", "fechaFin": "2027-07-20"},
            test="""pm.test('Responde 200 con las fechas nuevas', () => {
    pm.response.to.have.status(200);
    pm.expect(pm.response.json().fechaInicio).to.include('2027-07-12');
});"""),

        peticion(
            '05.8 Programar un período que empieza hoy',
            'POST', '/vacaciones',
            'Para demostrar la suspensión de la cuenta hace falta un período que '
            'el scheduler pueda iniciar ya. Se programa para el empleado que sí '
            'tiene contraseña establecida: la suspensión se comprueba con un '
            'inicio de sesión, y sin contraseña el servicio rechazaría por '
            'credenciales antes de mirar el estado de la cuenta. La fecha se '
            'calcula en el script, no se escribe a mano.',
            prerequest="""// Fechas calculadas: hoy y el regreso mañana. Es el período más corto
// posible con la semántica de fecha de regreso.
const hoy = new Date();
const manana = new Date(hoy.getTime() + 24 * 60 * 60 * 1000);
const iso = (d) => d.toISOString().slice(0, 10);
pm.collectionVariables.set('hoyISO', iso(hoy));
pm.collectionVariables.set('mananaISO', iso(manana));""",
            cuerpo={"empleadoId": "{{empleadoId}}", "fechaInicio": "{{hoyISO}}", "fechaFin": "{{mananaISO}}"},
            test="""pm.test('Responde 201', () => pm.response.to.have.status(201));
pm.collectionVariables.set('periodoInmediatoId', pm.response.json().id);"""),

        peticion(
            '05.9 Forzar el inicio del período sin esperar al scheduler',
            'POST', '/vacaciones/{{periodoInmediatoId}}/forzar-inicio',
            'El scheduler corre cada hora; este endpoint dispara su misma lógica a '
            'demanda para poder demostrar el flujo en una sustentación. Publica '
            '"vacaciones.iniciadas", que auth-service convierte en una suspensión.',
            test="""pm.test('Responde 200 y el período queda EN_CURSO', () => {
    pm.response.to.have.status(200);
    const b = pm.response.json();
    pm.expect(JSON.stringify(b)).to.include('EN_CURSO');
});"""),

        peticion(
            '05.10 Comprobar que la cuenta quedó suspendida durante las vacaciones',
            'POST', '/auth/login',
            'La suspensión es temporal y la provoca el evento, no una llamada '
            'directa: auth-service pone la cuenta en SUSPENDIDA_TEMPORAL. Las '
            'credenciales son las correctas, así que el 401 solo puede venir del '
            'estado de la cuenta, y el mensaje lo confirma.',
            token='ninguno',
            cuerpo={"usuario": "{{empleadoEmail}}", "password": "{{empleadoPasswordNueva}}"},
            prerequest=sondeo(
                'auth-service suspenda la cuenta por el inicio de las vacaciones',
                ("pm.sendRequest({\n"
                 "      url: base + '/auth/login',\n"
                 "      method: 'POST',\n"
                 "      header: { 'Content-Type': 'application/json' },\n"
                 "      body: { mode: 'raw', raw: JSON.stringify({ usuario: pm.collectionVariables.get('empleadoEmail'), password: pm.collectionVariables.get('empleadoPasswordNueva') }) }\n"
                 "    }"),
                "res.code === 401 && String(res.text()).indexOf('vacaciones') >= 0"),
            test="""pm.test('Responde 401 por el estado de la cuenta', () => pm.response.to.have.status(401));
pm.test('El mensaje menciona las vacaciones, no las credenciales', () => {
    pm.expect(pm.response.json().message).to.include('vacaciones');
});"""),

        peticion(
            '05.11 Forzar el fin del período',
            'POST', '/vacaciones/{{periodoInmediatoId}}/forzar-fin',
            'Publica "vacaciones.finalizadas" y la cuenta vuelve a ACTIVA. El '
            'período queda FINALIZADA.',
            test="""pm.test('Responde 200 y el período queda FINALIZADA', () => {
    pm.response.to.have.status(200);
    pm.expect(JSON.stringify(pm.response.json())).to.include('FINALIZADA');
});"""),

        peticion(
            '05.12 Comprobar que la cuenta volvió a estar activa',
            'POST', '/auth/login',
            'La reactivación también llega por evento. Solo se reactiva lo que se '
            'suspendió por vacaciones: una baja permanente no se revierte por '
            'este camino, y eso se comprueba al final del recorrido.',
            token='ninguno',
            cuerpo={"usuario": "{{empleadoEmail}}", "password": "{{empleadoPasswordNueva}}"},
            prerequest=sondeo(
                'auth-service devuelva la cuenta a ACTIVA al terminar el período',
                ("pm.sendRequest({\n"
                 "      url: base + '/auth/login',\n"
                 "      method: 'POST',\n"
                 "      header: { 'Content-Type': 'application/json' },\n"
                 "      body: { mode: 'raw', raw: JSON.stringify({ usuario: pm.collectionVariables.get('empleadoEmail'), password: pm.collectionVariables.get('empleadoPasswordNueva') }) }\n"
                 "    }"),
                'res.code === 200'),
            test="""pm.test('Responde 200: la cuenta volvió a estar activa', () => {
    pm.response.to.have.status(200);
    pm.expect(pm.response.json().rol).to.eql('USER');
});

// El token se renueva porque el resto del recorrido sigue usando esta identidad.
pm.collectionVariables.set('tokenUsuario', pm.response.json().token);"""),

        peticion(
            '05.13 Cancelar el período programado',
            'DELETE', '/vacaciones/{{periodoId}}',
            'Cancela el período que seguía PROGRAMADA y devuelve los días al saldo.',
            test="""pm.test('Responde 200 o 204', () => {
    pm.expect(pm.response.code).to.be.oneOf([200, 204]);
});"""),
    ]))

# ── 06 Notificaciones ───────────────────────────────────────────────────────
CARPETAS.append(carpeta(
    '06 Historial de notificaciones',
    'notifications-service consume los seis eventos del catálogo y simula el '
    'correo. Guarda un historial consultable, pero sin credenciales dentro.',
    [
        peticion(
            '06.1 Listar el historial de notificaciones',
            'GET', '/notificaciones?size=20',
            'Historial completo, paginado y ordenado por fecha de proceso.',
            prerequest=sondeo_get(
                'exista alguna notificación del empleado',
                "'/notificaciones/empleado/' + pm.collectionVariables.get('empleadoId')",
                'res.code === 200'),
            test="""pm.test('Responde 200 con el historial', () => {
    pm.response.to.have.status(200);
    const b = pm.response.json();
    const filas = Array.isArray(b) ? b : b.data;
    pm.expect(filas).to.be.an('array');
});"""),

        peticion(
            '06.2 Filtrar el historial por empleado',
            'GET', '/notificaciones/empleado/{{empleadoId}}',
            'Las notificaciones del empleado que esta ejecución dio de alta: su '
            'correo de bienvenida y el de recuperación.',
            test="""pm.test('Responde 200 con notificaciones del empleado', () => {
    pm.response.to.have.status(200);
    const b = pm.response.json();
    const filas = Array.isArray(b) ? b : b.data;
    pm.expect(filas.length).to.be.at.least(1);
});

pm.test('Están los correos de la cadena de alta', () => {
    const b = pm.response.json();
    const filas = Array.isArray(b) ? b : b.data;
    const tipos = filas.map(f => f.eventType);
    pm.expect(tipos).to.include('usuario.creado');
});"""),

        peticion(
            '06.3 Comprobar que el historial no conserva los tokens',
            'GET', '/notificaciones/empleado/{{empleadoId}}',
            'El token de activación viaja en el evento, pero el historial guarda '
            'solo campos públicos: un token de un solo uso en texto plano dentro '
            'de una base de datos consultable por API era un hallazgo de '
            'seguridad. Esta es la aserción que lo mantiene cerrado.',
            test="""const b = pm.response.json();
const filas = Array.isArray(b) ? b : b.data;
const creado = filas.find(f => f.eventType === 'usuario.creado');

pm.test('Existe el correo de bienvenida', () => pm.expect(creado).to.not.be.undefined);

pm.test('Su payload no guarda el token', () => {
    const datos = (creado && creado.payload && creado.payload.data) || {};
    pm.expect(datos).to.not.have.property('token');
});

pm.test('Pero sí conserva los datos públicos del empleado', () => {
    const datos = (creado && creado.payload && creado.payload.data) || {};
    pm.expect(datos).to.have.property('id');
    pm.expect(datos).to.have.property('email');
});"""),

        peticion(
            '06.4 Rechazar una escritura sobre el historial',
            'POST', '/notificaciones',
            'El historial es de solo consulta: lo escribe el consumidor de '
            'eventos, no un cliente HTTP.',
            cuerpo={"intento": "escribir en el historial"},
            test="""pm.test('Responde 405', () => pm.response.to.have.status(405));"""),
    ]))

# ── 07 Vista agregada ───────────────────────────────────────────────────────
CARPETAS.append(carpeta(
    '07 Vista agregada del empleado',
    'Un endpoint del propio gateway que compone en una respuesta lo que viven '
    'tres servicios distintos, con resiliencia por dependencia.',
    [
        peticion(
            '07.1 Consultar el expediente completo del empleado',
            'GET', '/empleados/{{empleadoId}}/completo',
            'Agrega empleado, perfil y vacaciones en paralelo. El empleado es '
            'obligatorio; el perfil y el saldo son opcionales y degradan a null si '
            'su servicio no responde, con un circuit breaker por dependencia.',
            test="""pm.test('Responde 200 con los tres bloques', () => {
    pm.response.to.have.status(200);
    const b = pm.response.json();
    pm.expect(b.empleado, 'empleado').to.be.an('object');
    pm.expect(b.perfil, 'perfil').to.be.an('object');
    pm.expect(b.vacaciones, 'vacaciones').to.be.an('object');
});

pm.test('Los tres hablan del mismo empleado', () => {
    const b = pm.response.json();
    const id = pm.collectionVariables.get('empleadoId');
    pm.expect(b.empleado.id).to.eql(id);
    pm.expect(b.perfil.empleadoId).to.eql(id);
});"""),

        peticion(
            '07.2 Consultar el expediente de un empleado inexistente',
            'GET', '/empleados/NO-EXISTE-{{sufijo}}/completo',
            'Sin empleado no hay nada que agregar: responde 404, no 503. Un 404 '
            'del servicio de empleados es una respuesta correcta, no una caída.',
            test="""pm.test('Responde 404', () => pm.response.to.have.status(404));"""),

        peticion(
            '07.3 Rechazar el expediente completo sin token',
            'GET', '/empleados/{{empleadoId}}/completo',
            'Aunque lo atiende un controlador del propio gateway, pasa por los '
            'mismos filtros de seguridad que las rutas que se reenvían.',
            token='ninguno',
            test="""pm.test('Responde 401', () => pm.response.to.have.status(401));"""),
    ]))

# ── 08 Seguridad ────────────────────────────────────────────────────────────
CARPETAS.append(carpeta(
    '08 Seguridad y control de acceso',
    'La validación del JWT y las reglas de rol viven solo en el gateway: es el '
    'único componente publicado al host, y los servicios de negocio confían en '
    'él porque no son alcanzables desde fuera.',
    [
        peticion(
            '08.1 Rechazar una petición sin token',
            'GET', '/empleados',
            'Toda ruta que no sea pública exige un Access JWT.',
            token='ninguno',
            test="""pm.test('Responde 401', () => pm.response.to.have.status(401));
pm.test('Explica qué cabecera falta', () => {
    pm.expect(pm.response.json().message).to.include('Authorization');
});"""),

        peticion(
            '08.2 Rechazar un token malformado',
            'GET', '/empleados',
            'Cualquier cosa que no sea un JWT verificable se rechaza antes de '
            'llegar al servicio.',
            token='Bearer esto-no-es-un-jwt',
            test="""pm.test('Responde 401', () => pm.response.to.have.status(401));"""),

        peticion(
            '08.3 Rechazar las cabeceras de identidad enviadas por el cliente',
            'GET', '/empleados',
            'El gateway propaga la identidad a los servicios en X-Auth-User y '
            'X-Auth-Role. Si aceptara esas cabeceras del cliente, cualquiera '
            'podría declararse ADMIN: se eliminan antes de validar.',
            token='ninguno',
            cabeceras=[('X-Auth-User', 'admin'), ('X-Auth-Role', 'ADMIN')],
            test="""pm.test('Responde 401: las cabeceras no otorgan identidad', () => {
    pm.response.to.have.status(401);
});"""),

        peticion(
            '08.4 Un empleado no puede crear departamentos',
            'POST', '/departamentos',
            'El rol USER es de solo lectura fuera de sus propios recursos.',
            token='user',
            cuerpo={"nombre": "Intento de un USER"},
            test="""pm.test('Responde 403', () => pm.response.to.have.status(403));"""),

        peticion(
            '08.5 Un empleado no puede retirar a nadie',
            'DELETE', '/empleados/{{companeroId}}',
            'Tampoco las bajas: son operaciones de administración.',
            token='user',
            test="""pm.test('Responde 403', () => pm.response.to.have.status(403));"""),

        peticion(
            '08.6 Un token de restablecimiento no sirve como token de acceso',
            'GET', '/empleados',
            'Los dos tipos de token se firman con el mismo secreto, así que la '
            'firma no los distingue: lo hace el claim type. Sin esa comprobación, '
            'un token de activación abriría la API.',
            token='Bearer {{tokenActivacion}}',
            test="""pm.test('Responde 401 por el tipo de token', () => pm.response.to.have.status(401));"""),
    ]))

# ── 09 Observabilidad ───────────────────────────────────────────────────────
CARPETAS.append(carpeta(
    '09 Observabilidad y contratos',
    'Salud, métricas y documentación. Las tres rutas son públicas a propósito: '
    'las usan Docker, Prometheus y quien lea la API.',
    [
        peticion(
            '09.1 Comprobar la salud del gateway',
            'GET', '/health',
            'Sonda propia del borde, sin token. Es la que Docker consulta para '
            'decidir si el contenedor está sano.',
            token='ninguno',
            test="""pm.test('Responde 200 y se identifica', () => {
    pm.response.to.have.status(200);
    pm.expect(pm.response.json().service).to.eql('api-gateway');
});"""),

        peticion(
            '09.2 Comprobar la salud de empleados a través del gateway',
            'GET', '/empleados/health',
            'Sonda ligera, sin token y sin consultar dependencias: responde si el '
            'servicio atiende peticiones. La sonda detallada —con un check por '
            'dependencia y 503 si alguna falla— vive en la raíz del servicio, '
            'que no está enrutada al exterior: la consulta Docker desde dentro '
            'del contenedor, que es quien decide si reiniciarlo.',
            test="""pm.test('Responde 200 y el servicio se identifica', () => {
    pm.response.to.have.status(200);
    const b = pm.response.json();
    pm.expect(b.service).to.eql('empleados');
    pm.expect(b.status).to.eql('ok');
});"""),

        peticion(
            '09.3 Consultar las métricas del gateway en formato Prometheus',
            'GET', '/metrics',
            'Alias común a los siete servicios. Los tres servicios Java conservan '
            'además su ruta anterior, /actuator/prometheus, para no romper a quien '
            'ya la usaba. La respuesta es texto, no JSON: pedir solo JSON en la '
            'cabecera Accept devuelve 406, y por eso esta petición declara '
            'text/plain.',
            token='ninguno',
            acepta='text/plain',
            test="""pm.test('Responde 200 en texto de Prometheus', () => {
    pm.response.to.have.status(200);
    pm.expect(pm.response.text()).to.include('# HELP');
    pm.expect(pm.response.text()).to.include('# TYPE');
});"""),

        peticion(
            '09.4 Consultar el contrato OpenAPI de empleados',
            'GET', '/v3/api-docs/empleados',
            'El gateway agrega los contratos de los seis servicios con API '
            'pública bajo /v3/api-docs/<servicio>, y los presenta juntos en su '
            'Swagger UI.',
            token='ninguno',
            test="""pm.test('Responde 200 con un contrato OpenAPI', () => {
    pm.response.to.have.status(200);
    const d = pm.response.json();
    pm.expect(d.openapi || d.swagger).to.be.a('string');
    pm.expect(d.paths).to.be.an('object');
});"""),

        peticion(
            '09.5 Consultar el contrato OpenAPI de perfiles',
            'GET', '/v3/api-docs/perfiles',
            'Mismo mecanismo para los servicios que no son Java.',
            token='ninguno',
            test="""pm.test('Responde 200 y describe el recurso perfil', () => {
    pm.response.to.have.status(200);
    const d = pm.response.json();
    pm.expect(Object.keys(d.paths)).to.include('/perfiles');
    pm.expect(d.components.schemas).to.have.property('Perfil');
});"""),
    ]))

# ── 10 Baja del empleado ────────────────────────────────────────────────────
CARPETAS.append(carpeta(
    '10 Baja del empleado',
    'El retiro es una baja lógica que se propaga por eventos: archiva el perfil, '
    'desactiva la cuenta de forma permanente y cancela las vacaciones pendientes. '
    'El historial nunca se borra.',
    [
        peticion(
            '10.1 Retirar al empleado',
            'DELETE', '/empleados/{{empleadoId}}',
            'No elimina la fila: cambia el estado a RETIRADO y guarda la fecha y '
            'hora exactas del hecho. Publica "empleado.retirado".',
            test="""pm.test('Responde 200 con el empleado retirado', () => {
    pm.response.to.have.status(200);
    const e = pm.response.json();
    pm.expect(e.estado).to.eql('RETIRADO');
    pm.expect(e.fechaRetiro).to.not.be.null;
});

pm.test('La fecha de retiro lleva la hora, no solo el día', () => {
    pm.expect(pm.response.json().fechaRetiro).to.match(/^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}/);
});"""),

        peticion(
            '10.2 Rechazar el retiro de un empleado ya retirado',
            'DELETE', '/empleados/{{empleadoId}}',
            'La operación no es idempotente a propósito: repetirla es un error del '
            'cliente, no una acción sin efecto.',
            test="""pm.test('Responde 400 o 409', () => {
    pm.expect(pm.response.code).to.be.oneOf([400, 409]);
});"""),

        peticion(
            '10.3 Comprobar que el perfil quedó archivado',
            'GET', '/perfiles/{{empleadoId}}',
            'El perfil no se borra —el historial se conserva para auditoría— pero '
            'se archiva, y el archivado se escribe en la misma operación que el '
            'cambio de estado.',
            prerequest=sondeo_get(
                'el perfil quede RETIRADO por el evento de retiro',
                "'/perfiles/' + pm.collectionVariables.get('empleadoId')",
                "res.code === 200 && res.json().estado === 'RETIRADO'"),
            test="""pm.test('Responde 200 con el perfil retirado y archivado', () => {
    pm.response.to.have.status(200);
    const p = pm.response.json();
    pm.expect(p.estado).to.eql('RETIRADO');
    pm.expect(p.archivado).to.eql(true);
    pm.expect(p.fechaRetiro).to.not.be.null;
});"""),

        peticion(
            '10.4 Comprobar que el perfil salió del listado vigente',
            'GET', '/perfiles?archivado=false&limit=100',
            'Es el efecto práctico del archivado: las consultas corrientes dejan '
            'de arrastrarlo.',
            test="""pm.test('Responde 200 y el perfil retirado ya no aparece', () => {
    pm.response.to.have.status(200);
    const b = pm.response.json();
    const filas = Array.isArray(b) ? b : b.data;
    const ids = filas.map(p => p.empleadoId);
    pm.expect(ids).to.not.include(pm.collectionVariables.get('empleadoId'));
});"""),

        peticion(
            '10.5 Comprobar que la cuenta quedó desactivada de forma permanente',
            'POST', '/auth/login',
            'A diferencia de la suspensión por vacaciones, esta baja no se '
            'revierte: ni el login ni la recuperación la reactivan.',
            token='ninguno',
            cuerpo={"usuario": "{{empleadoEmail}}", "password": "{{empleadoPasswordNueva}}"},
            prerequest=sondeo(
                'auth-service desactive la cuenta por el retiro',
                ("pm.sendRequest({\n"
                 "      url: base + '/auth/login',\n"
                 "      method: 'POST',\n"
                 "      header: { 'Content-Type': 'application/json' },\n"
                 "      body: { mode: 'raw', raw: JSON.stringify({ usuario: pm.collectionVariables.get('empleadoEmail'), password: pm.collectionVariables.get('empleadoPasswordNueva') }) }\n"
                 "    }"),
                "res.code === 401 && String(res.text()).indexOf('permanente') >= 0"),
            test="""pm.test('Responde 401 por baja permanente', () => {
    pm.response.to.have.status(401);
    pm.expect(pm.response.json().message).to.include('permanente');
});"""),

        peticion(
            '10.6 Comprobar que el empleado aparece en la auditoría de retiros',
            'GET', '/empleados?desde=2020-01-01&hasta=2030-12-31',
            'Con desde y hasta, el listado se convierte en la auditoría de '
            'retiros: fuerza el estado RETIRADO y acota por fecha de retiro, con '
            'los dos límites inclusivos.',
            test="""pm.test('Responde 200 y solo trae retirados', () => {
    pm.response.to.have.status(200);
    const b = pm.response.json();
    const filas = Array.isArray(b) ? b : b.data;
    filas.forEach(e => pm.expect(e.estado).to.eql('RETIRADO'));
});

pm.test('El empleado de esta ejecución está en la auditoría', () => {
    const b = pm.response.json();
    const filas = Array.isArray(b) ? b : b.data;
    pm.expect(filas.map(e => e.id)).to.include(pm.collectionVariables.get('empleadoId'));
});"""),

        peticion(
            '10.7 Rechazar una auditoría sin el rango completo',
            'GET', '/empleados/auditoria',
            'Un rango abierto es una consulta sin techo, y acotar es justo lo que '
            'una auditoría necesita. Esta ruta se conserva como alias de la '
            'anterior por compatibilidad.',
            test="""pm.test('Responde 400 pidiendo el rango', () => {
    pm.response.to.have.status(400);
    pm.expect(pm.response.json().message).to.include('obligatorio');
});"""),

        peticion(
            '10.8 Rechazar el borrado de un departamento con empleados',
            'DELETE', '/departamentos/{{departamentoId}}',
            'Las bases de datos están separadas por servicio, así que no hay una '
            'llave foránea que PostgreSQL pueda defender: departments-service '
            'pregunta a employees-service antes de borrar. Si no pudiera '
            'preguntar, responde 503 y no borra.',
            test="""pm.test('Responde 409 por tener empleados asociados', () => {
    pm.response.to.have.status(409);
});"""),

        peticion(
            '10.9 Comprobar que el historial de notificaciones conserva el retiro',
            'GET', '/notificaciones/empleado/{{empleadoId}}',
            'Cierre del recorrido: el historial guarda toda la vida de la cuenta, '
            'desde la bienvenida hasta la desactivación.',
            prerequest=sondeo_get(
                'llegue la notificación del retiro',
                "'/notificaciones/empleado/' + pm.collectionVariables.get('empleadoId')",
                ("res.code === 200 && JSON.stringify(res.json()).indexOf('empleado.retirado') >= 0")),
            test="""pm.test('Responde 200 y están los eventos del ciclo completo', () => {
    pm.response.to.have.status(200);
    const b = pm.response.json();
    const filas = Array.isArray(b) ? b : b.data;
    const tipos = filas.map(f => f.eventType);
    pm.expect(tipos, 'bienvenida').to.include('usuario.creado');
    pm.expect(tipos, 'retiro').to.include('empleado.retirado');
});"""),
    ]))

# ─── Documento de la colección ───────────────────────────────────────────────

DESCRIPCION = """Recorrido completo del sistema de recursos humanos, en el orden en el que
ocurren las cosas: del catálogo de departamentos a la baja del empleado.

**Cómo ejecutarla.** Selecciona el entorno `system-hr-flujo-completo` y usa el
Collection Runner con las carpetas en orden. Las peticiones comparten estado
—tokens, identificadores, el período de vacaciones— así que no están pensadas
para ejecutarse aisladas.

**Se puede repetir.** Cada ejecución genera sus propios identificadores y
correos a partir del reloj, de modo que no choca con los datos que dejó la
anterior.

**Espera los efectos asíncronos.** El alta de un empleado desencadena una cadena
por RabbitMQ: la cuenta, el perfil, el saldo de vacaciones y el correo llegan
después de la respuesta HTTP. Donde eso importa, la petición lleva un script
previo que consulta hasta que la condición se cumple, con un tope de 30 segundos.
No hay pausas fijas: una pausa fija o sobra o no alcanza.

**Todo entra por el gateway.** Los servicios de negocio no publican puertos al
host, así que `{{gatewayBaseUrl}}` es la única dirección que hace falta."""

coleccion = OD()
coleccion["info"] = OD([
    ("name", "System_HR — Flujo completo"),
    ("description", DESCRIPCION),
    ("schema", "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"),
])
coleccion["item"] = CARPETAS
coleccion["variable"] = [
    OD([("key", k), ("value", v)]) for k, v in [
        ("tokenAdmin", ""),
        ("tokenUsuario", ""),
        ("tokenActivacion", ""),
        ("sufijo", ""),
        ("departamentoId", ""),
        ("empleadoId", ""),
        ("empleadoEmail", ""),
        ("empleadoNumero", ""),
        ("companeroId", ""),
        ("companeroEmail", ""),
        ("companeroNumero", ""),
        ("periodoId", ""),
        ("periodoInmediatoId", ""),
        ("hoyISO", ""),
        ("mananaISO", ""),
        ("empleadoPassword", "Secreta123"),
        ("empleadoPasswordNueva", "Nueva4567"),
    ]
]

io.open(COLECCION, 'w', encoding='utf-8', newline='\n').write(
    json.dumps(coleccion, indent=2, ensure_ascii=False) + '\n')

# ─── Entorno ─────────────────────────────────────────────────────────────────

entorno = OD([
    ("name", "system-hr-flujo-completo"),
    ("values", [
        OD([("key", "gatewayBaseUrl"), ("value", "http://localhost:8080"), ("enabled", True),
            ("type", "default")]),
        OD([("key", "adminUsuario"), ("value", "admin@empresa.com"), ("enabled", True),
            ("type", "default")]),
        OD([("key", "adminPassword"), ("value", "Admin12345"), ("enabled", True),
            ("type", "default")]),
    ]),
    ("_postman_variable_scope", "environment"),
])

io.open(ENTORNO, 'w', encoding='utf-8', newline='\n').write(
    json.dumps(entorno, indent=2, ensure_ascii=False) + '\n')

total = sum(len(c["item"]) for c in CARPETAS)
print(f'{COLECCION}: {len(CARPETAS)} carpetas, {total} peticiones')
print(f'{ENTORNO}: entorno con la URL del gateway y las credenciales del administrador')
