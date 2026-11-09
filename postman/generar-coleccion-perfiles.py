"""Regenera la coleccion de Postman de perfiles-service (Dev 2)."""
import collections
import io
import json

OD = collections.OrderedDict


def url(raw):
    sin_query = raw.split("?")[0]
    base = sin_query.split("}}", 1)
    host = base[0] + "}}"
    ruta = [p for p in (base[1] if len(base) > 1 else "").split("/") if p]
    u = OD([("raw", raw), ("host", [host]), ("path", ruta)])
    if "?" in raw:
        u["query"] = [OD([("key", k), ("value", v)])
                      for k, v in (p.split("=", 1) for p in raw.split("?", 1)[1].split("&"))]
    return u


def peticion(nombre, metodo, raw, descripcion, test=None, prerequest=None,
             body=None, cabeceras=None):
    req = OD([("method", metodo), ("header", list(cabeceras or [])),
              ("url", url(raw)), ("description", descripcion)])
    if body is not None:
        req["header"] = [OD([("key", "Content-Type"), ("value", "application/json")])] + req["header"]
        req["body"] = OD([("mode", "raw"),
                          ("raw", json.dumps(body, indent=2, ensure_ascii=False)),
                          ("options", {"raw": {"language": "json"}})])
    eventos = []
    if prerequest:
        eventos.append(OD([("listen", "prerequest"),
                           ("script", OD([("type", "text/javascript"), ("exec", prerequest.split("\n"))]))]))
    if test:
        eventos.append(OD([("listen", "test"),
                           ("script", OD([("type", "text/javascript"), ("exec", test.split("\n"))]))]))
    item = OD([("name", nombre), ("request", req)])
    if eventos:
        item["event"] = eventos
    return item


def carpeta(nombre, descripcion, items):
    return OD([("name", nombre), ("description", descripcion), ("item", items)])


ADMIN = [OD([("key", "Authorization"), ("value", "Bearer {{adminToken}}")])]
USER = [OD([("key", "Authorization"), ("value", "Bearer {{userToken}}")])]

CAMPOS = ["telefono", "direccion", "ciudad", "biografia", "fechaCreacion"]

# ── 0. Preparacion ───────────────────────────────────────────────────────────
PRE_IDS = """const sufijo = String(Date.now()).slice(-9);
pm.collectionVariables.set('empleadoId', 'EPRF' + sufijo);
pm.collectionVariables.set('empleadoEmail', 'prf' + sufijo + '@empresa.com');
pre = 'EMP-PRF-' + sufijo;
pm.collectionVariables.set('numeroEmpleado', pre);
// Un segundo empleado para probar la regla de propiedad: hace falta un perfil
// AJENO al que intentar escribir.
pm.collectionVariables.set('ajenoId', 'EPRF' + sufijo + 'B');
pm.collectionVariables.set('ajenoEmail', 'prf' + sufijo + 'b@empresa.com');
pm.collectionVariables.set('ajenoNumero', pre + 'B');"""

prep = [
    peticion("0.1 Login ADMIN", "POST", "{{gatewayBaseUrl}}/auth/login",
             "Guarda adminToken. Todo lo demas depende de esta peticion.",
             body={"usuario": "{{adminUsuario}}", "password": "{{adminPassword}}"},
             prerequest=PRE_IDS,
             test="""pm.test('Status 200', () => pm.response.to.have.status(200));
pm.collectionVariables.set('adminToken', pm.response.json().token);"""),

    peticion("0.2 Precondicion: departamento IT", "POST", "{{gatewayBaseUrl}}/departamentos",
             "El alta de empleado valida el departamento. Tolera 400/409 si ya existe.",
             body={"id": "IT", "nombre": "Tecnologia", "descripcion": "Departamento de tecnologia"},
             cabeceras=ADMIN,
             test="pm.test('IT disponible', () => pm.expect(pm.response.code).to.be.oneOf([201, 400, 409]));"),

    peticion("0.3 Crear empleado (el perfil llega por evento)", "POST", "{{gatewayBaseUrl}}/empleados",
             "El perfil NO se crea con un POST /perfiles. Este servicio es un read model "
             "CQRS: su unica fuente son los eventos. Al crear el empleado, employees-service "
             "publica empleado.creado y profiles-service construye el perfil al consumirlo. "
             "Por eso la peticion siguiente espera unos segundos.",
             body={"id": "{{empleadoId}}", "nombre": "Perfil", "apellido": "Demo",
                   "email": "{{empleadoEmail}}", "numeroEmpleado": "{{numeroEmpleado}}",
                   "cargo": "Analista", "area": "Tecnologia", "departamentoId": "IT",
                   "fechaIngreso": "2026-11-08", "estado": "ACTIVO"},
             cabeceras=ADMIN,
             test="""pm.test('Status 201', () => pm.response.to.have.status(201));
console.log('Espere ~8s: el perfil se crea al consumir empleado.creado.');"""),

    peticion("0.4 Crear un segundo empleado (perfil ajeno)", "POST", "{{gatewayBaseUrl}}/empleados",
             "Hace falta un perfil que NO sea del usuario autenticado para poder comprobar "
             "la regla de propiedad (403) en la carpeta 3.",
             body={"id": "{{ajenoId}}", "nombre": "Ajeno", "apellido": "Demo",
                   "email": "{{ajenoEmail}}", "numeroEmpleado": "{{ajenoNumero}}",
                   "cargo": "Analista", "area": "Tecnologia", "departamentoId": "IT",
                   "fechaIngreso": "2026-11-08", "estado": "ACTIVO"},
             cabeceras=ADMIN,
             test="pm.test('Status 201', () => pm.response.to.have.status(201));"),
]

# ── 1. El modelo del perfil ──────────────────────────────────────────────────
T_CAMPOS = """pm.test('Status 200', () => pm.response.to.have.status(200));

const p = pm.response.json();

pm.test('empleadoId es el campo canonico', () => {
    pm.expect(p.empleadoId).to.eql(pm.collectionVariables.get('empleadoId'));
});

pm.test('employeeId se mantiene como alias', () => {
    // La columna se renombro a empleadoId para alinearla con el modelo canonico
    // del sistema, que usa nombres en espanol. Quitar employeeId habria roto a
    // cualquier cliente que lo leyera, asi que la respuesta lleva los dos. Es la
    // misma solucion que employees-service, que expone id y employeeId.
    pm.expect(p.employeeId).to.eql(p.empleadoId);
});

pm.test('Estan los cinco campos canonicos del perfil', () => {
    ['telefono', 'direccion', 'ciudad', 'biografia', 'fechaCreacion'].forEach(c => {
        pm.expect(p, 'falta ' + c).to.have.property(c);
    });
});

pm.test('fechaCreacion es distinta de fechaIngreso', () => {
    // fechaIngreso es cuando entro el empleado a la empresa; fechaCreacion es
    // cuando se creo su perfil en el read model. Pueden diferir en meses.
    pm.expect(p.fechaCreacion).to.not.be.null;
});

pm.test('Un perfil nuevo no esta archivado', () => {
    pm.expect(p.archivado).to.eql(false);
});

console.log('empleadoId=' + p.empleadoId + ' | archivado=' + p.archivado
            + ' | fechaCreacion=' + p.fechaCreacion);"""

T_EDITAR = """pm.test('Status 200', () => pm.response.to.have.status(200));

const p = pm.response.json();
pm.test('Los cuatro campos editables quedaron guardados', () => {
    pm.expect(p.telefono).to.eql('+57 310 555 0199');
    pm.expect(p.direccion).to.eql('Calle 10 #20-30');
    pm.expect(p.ciudad).to.eql('Armenia');
    pm.expect(p.biografia).to.eql('Analista de datos del area de Tecnologia');
});"""

D_EDITAR = ("Los campos del perfil los edita su dueno. A diferencia de nombre, cargo o "
            "departamento —que los gobierna employees-service y llegan por evento— estos "
            "cinco son datos del perfil: employees-service no los conoce ni los publica. "
            "fechaCreacion no se edita: la fija la base de datos al crear la fila.")

modelo = [
    peticion("1.1 El perfil creado por el evento", "GET", "{{gatewayBaseUrl}}/perfiles/{{empleadoId}}",
             "Comprueba el modelo completo: el campo canonico, su alias y los cinco campos "
             "del perfil. Si responde 404, el evento aun no se ha consumido: espere unos "
             "segundos y reintente.",
             T_CAMPOS, cabeceras=ADMIN),

    peticion("1.2 Editar los campos del perfil (ADMIN)", "PUT",
             "{{gatewayBaseUrl}}/perfiles/{{empleadoId}}", D_EDITAR, T_EDITAR,
             body={"telefono": "+57 310 555 0199", "direccion": "Calle 10 #20-30",
                   "ciudad": "Armenia",
                   "biografia": "Analista de datos del area de Tecnologia"},
             cabeceras=ADMIN),

    peticion("1.3 Ordenar por fechaCreacion", "GET",
             "{{gatewayBaseUrl}}/perfiles?page=1&limit=5&sortBy=fechaCreacion&order=DESC",
             "fechaCreacion y archivado son campos ordenables nuevos. De paso: el listado "
             "paginado devuelve {data, meta}, mientras que sin parametros devuelve un array.",
             """pm.test('Status 200', () => pm.response.to.have.status(200));
pm.test('Respuesta paginada con data y meta', () => {
    const c = pm.response.json();
    pm.expect(c).to.have.property('data');
    pm.expect(c.meta).to.have.all.keys('total', 'page', 'limit', 'totalPages');
});""", cabeceras=ADMIN),
]

# ── 2. Archivado ─────────────────────────────────────────────────────────────
T_NO_ARCH = """pm.test('Status 200', () => pm.response.to.have.status(200));

const perfiles = pm.response.json();
const lista = Array.isArray(perfiles) ? perfiles : perfiles.data;

pm.test('Ninguno esta archivado', () => {
    const archivados = lista.filter(p => p.archivado).map(p => p.empleadoId);
    pm.expect(archivados, 'archivados colados: ' + archivados.join(', ')).to.be.empty;
});

console.log('Perfiles activos (no archivados): ' + lista.length);"""

T_ARCH = """pm.test('Status 200', () => pm.response.to.have.status(200));

const lista = pm.response.json();
pm.test('Todos estan archivados', () => {
    const noArchivados = lista.filter(p => !p.archivado).map(p => p.empleadoId);
    pm.expect(noArchivados).to.be.empty;
});

pm.test('Y todos estan RETIRADOS', () => {
    // Hoy las dos condiciones coinciden, pero son filtros distintos: un perfil
    // podria archivarse por otro motivo sin estar retirado, y entonces estas dos
    // consultas dejarian de dar lo mismo.
    pm.expect(lista.every(p => p.estado === 'RETIRADO')).to.be.true;
});"""

archivado = [
    peticion("2.1 Solo los perfiles vigentes (?archivado=false)", "GET",
             "{{gatewayBaseUrl}}/perfiles?archivado=false",
             "La consulta corriente: los perfiles de quienes siguen en la empresa. "
             "Un perfil retirado no se borra —el historial se conserva para auditoria— "
             "pero se archiva para que no arrastre las consultas del dia a dia.",
             T_NO_ARCH, cabeceras=ADMIN),

    peticion("2.2 Solo los archivados (?archivado=true)", "GET",
             "{{gatewayBaseUrl}}/perfiles?archivado=true",
             "El historial: perfiles de empleados ya retirados.", T_ARCH, cabeceras=ADMIN),

    peticion("2.3 Un valor que no es booleano da 400", "GET",
             "{{gatewayBaseUrl}}/perfiles?archivado=quizas",
             "El filtro solo acepta true o false. Un valor invalido se rechaza en lugar de "
             "ignorarse: ignorarlo devolveria la lista completa y el cliente creeria que "
             "su filtro se aplico.",
             """pm.test('Status 400', () => pm.response.to.have.status(400));
pm.test('Explica el valor esperado', () => {
    pm.expect(pm.response.json().message).to.include('true o false');
});""", cabeceras=ADMIN),
]

# ── 3. Regla de propiedad (ABAC) ─────────────────────────────────────────────
PRE_ACTIVAR = """// Pide a auth-service el token de activacion de la cuenta del empleado.
//
// Antes se leia de GET /notifications, porque notifications-service guardaba el
// token en el payload de su historial. Eso era el hallazgo de seguridad que la
// auditoria pidio cerrar, y el servicio reconstruido en Go ya solo conserva
// campos publicos. Este es el canal legitimo: lo entrega quien firma el token.
const base  = pm.environment.get('gatewayBaseUrl') || pm.variables.get('gatewayBaseUrl');
const admin = pm.collectionVariables.get('adminToken');
const empleadoId = pm.collectionVariables.get('empleadoId');

pm.sendRequest({
    url: base + '/auth/internal/tokens/restablecimiento',
    method: 'POST',
    header: {
        'Authorization': 'Bearer ' + admin,
        'Content-Type': 'application/json',
        'Accept': 'application/json'
    },
    body: { mode: 'raw', raw: JSON.stringify({ empleadoId: empleadoId }) }
}, (err, res) => {
    if (err) { console.log('No se pudo pedir el token:', err); return; }
    if (res.code !== 200) {
        console.log('auth-service respondio ' + res.code + ': ' + res.text());
        return;
    }
    pm.collectionVariables.set('tokenActivacion', res.json().token);
});"""

T_ACTIVAR = """if (!pm.collectionVariables.get('tokenActivacion')) {
    pm.test('El script previo no obtuvo el token de activacion', () => {
        pm.expect.fail('Revise la consola: puede que el evento aun no haya llegado');
    });
} else {
    pm.test('Status 200', () => pm.response.to.have.status(200));
}"""

T_LOGIN_USER = """pm.test('Status 200', () => pm.response.to.have.status(200));
const b = pm.response.json();
pm.test('Rol USER', () => pm.expect(b.rol).to.eql('USER'));
pm.collectionVariables.set('userToken', b.token);"""

abac = [
    peticion("3.1 Activar la cuenta del empleado", "POST",
             "{{gatewayBaseUrl}}/auth/reset-password",
             "Hace falta un token de rol USER para probar la regla de propiedad. El token de "
             "activacion lo trae el script previo del evento usuario.creado.",
             T_ACTIVAR, prerequest=PRE_ACTIVAR,
             body={"token": "{{tokenActivacion}}", "nuevaPassword": "Secreta123"}),

    peticion("3.2 Login del empleado (rol USER)", "POST", "{{gatewayBaseUrl}}/auth/login",
             "Guarda userToken.", T_LOGIN_USER,
             body={"usuario": "{{empleadoEmail}}", "password": "Secreta123"}),

    peticion("3.3 Un USER edita SU PROPIO perfil (200)", "PUT",
             "{{gatewayBaseUrl}}/perfiles/{{empleadoId}}",
             "Autorizacion por propiedad del recurso (ABAC), no RBAC: el permiso no depende "
             "solo del rol sino de quien es dueno del dato. El gateway compara el claim sub "
             "del token con el id de la ruta. Es lo que permite que cada empleado gestione su "
             "perfil sin darle permisos de administrador.",
             """pm.test('Status 200', () => pm.response.to.have.status(200));
pm.test('El cambio se aplico', () => {
    pm.expect(pm.response.json().ciudad).to.eql('Pereira');
});""",
             body={"ciudad": "Pereira", "telefono": "+57 311 222 3344"},
             cabeceras=USER),

    peticion("3.4 Un USER intenta editar el perfil de OTRO (403)", "PUT",
             "{{gatewayBaseUrl}}/perfiles/{{ajenoId}}",
             "La otra mitad de la regla. El token es valido y el verbo esta permitido para el "
             "dueno, pero el id de la ruta es de otro empleado. Debe ser 403 ('se quien eres y "
             "no puedes') y no 401 ('no se quien eres'): responder 401 haria que el cliente "
             "intentara autenticarse de nuevo sin que eso resolviera nada.",
             """pm.test('Status 403', () => pm.response.to.have.status(403));
pm.test('No es 401: la identidad era valida', () => {
    pm.expect(pm.response.code).to.not.eql(401);
});
pm.test('El mensaje explica que falta el permiso', () => {
    pm.expect(pm.response.json().message).to.include('no tiene permiso');
});""",
             body={"ciudad": "Intruso"}, cabeceras=USER),

    peticion("3.5 Un USER SI puede LEER el perfil de otro (200)", "GET",
             "{{gatewayBaseUrl}}/perfiles/{{ajenoId}}",
             "La restriccion de propiedad es de escritura. El rol USER tiene lectura sobre "
             "todo el ecosistema.",
             "pm.test('Status 200', () => pm.response.to.have.status(200));",
             cabeceras=USER),
]

# ── 4. Retiro y archivado ────────────────────────────────────────────────────
T_RETIRO = """pm.test('Status 200', () => pm.response.to.have.status(200));

pm.test('El empleado queda RETIRADO con la hora exacta', () => {
    const e = pm.response.json();
    pm.expect(e.estado).to.eql('RETIRADO');
    // fechaRetiro es un instante desde el cambio del Dev 1: antes era solo la
    // fecha y dos retiros del mismo dia eran indistinguibles.
    pm.expect(e.fechaRetiro).to.match(/^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}/);
});

console.log('Espere ~8s: el perfil se archiva al consumir empleado.retirado.');"""

T_ARCHIVADO = """pm.test('Status 200', () => pm.response.to.have.status(200));

const p = pm.response.json();

pm.test('El perfil quedo RETIRADO y ARCHIVADO', () => {
    pm.expect(p.estado).to.eql('RETIRADO');
    pm.expect(p.archivado).to.eql(true);
});

pm.test('El perfil NO se borro: la baja es logica', () => {
    // El historial se conserva para auditoria. Lo que cambia es que queda fuera
    // de las consultas corrientes, no que desaparezca.
    pm.expect(p.empleadoId).to.eql(pm.collectionVariables.get('empleadoId'));
});

pm.test('La fechaRetiro llego con la hora, no a medianoche', () => {
    // El evento empleado.retirado transporta el instante del retiro. Antes
    // enviaba LocalDate.now(), asi que el perfil la recibia truncada a las 00:00
    // y, si el evento se reentregaba, con la fecha de ese momento.
    pm.expect(p.fechaRetiro).to.match(/^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}/);
    pm.expect(p.fechaRetiro).to.not.include('T00:00:00.000');
});"""

retiro = [
    peticion("4.1 Retirar al empleado (ADMIN)", "DELETE",
             "{{gatewayBaseUrl}}/empleados/{{empleadoId}}",
             "El retiro ocurre en employees-service y se propaga por el evento "
             "empleado.retirado. profiles-service lo consume y archiva el perfil.",
             T_RETIRO, cabeceras=ADMIN),

    peticion("4.2 El perfil quedo archivado", "GET",
             "{{gatewayBaseUrl}}/perfiles/{{empleadoId}}",
             "El archivado se escribe en la misma operacion que el cambio de estado: si "
             "fueran dos escrituras, un fallo entre ellas dejaria un perfil retirado sin "
             "archivar y el filtro ?archivado=false seguiria devolviendolo.",
             T_ARCHIVADO, cabeceras=ADMIN),

    peticion("4.3 Ya no aparece entre los vigentes", "GET",
             "{{gatewayBaseUrl}}/perfiles?archivado=false",
             "Cierra el ciclo: el perfil existe, pero fuera de la consulta corriente.",
             """pm.test('Status 200', () => pm.response.to.have.status(200));

const lista = pm.response.json();
const yo = pm.collectionVariables.get('empleadoId');

pm.test('El perfil retirado quedo fuera del listado vigente', () => {
    pm.expect(lista.map(p => p.empleadoId)).to.not.include(yo);
});""", cabeceras=ADMIN),
]

coleccion = OD([
    ("info", OD([
        ("name", "perfiles-service (Dev 2)"),
        ("description",
         "Read model CQRS de empleados. Verifica los cambios del Desarrollador 2:\n\n"
         "  - Los cinco campos canonicos del perfil (telefono, direccion, ciudad, "
         "biografia, fechaCreacion).\n"
         "  - El renombrado de employeeId a empleadoId, con alias de compatibilidad.\n"
         "  - La marca de archivado y su filtro.\n"
         "  - La regla de propiedad del recurso (ABAC): 200 en el perfil propio, 403 en "
         "el ajeno.\n\n"
         "ORDEN: las cuatro carpetas van de corrido, pero con RETARDO de ~2500 ms entre "
         "peticiones. El perfil no se crea con un POST: este servicio es un read model y "
         "su unica fuente son los eventos de RabbitMQ, que tardan unos segundos en "
         "propagarse.\n\n"
         "NOTA: todas las peticiones van por el API Gateway. La version anterior de esta "
         "coleccion apuntaba a {{perfilesBaseUrl}}, el puerto directo del servicio, que no "
         "esta publicado al host (ADR-008: impide esquivar la validacion del JWT) y que "
         "tampoco estaba definido en el entorno, asi que no se podia ejecutar."),
        ("schema", "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"),
    ])),
    ("item", [
        carpeta("0. Preparacion", "Token de ADMIN y dos empleados cuyos perfiles llegan por evento.", prep),
        carpeta("1. El modelo del perfil", "empleadoId, su alias y los cinco campos canonicos.", modelo),
        carpeta("2. Archivado", "La marca y su filtro.", archivado),
        carpeta("3. Regla de propiedad (ABAC)", "200 en el perfil propio, 403 en el ajeno.", abac),
        carpeta("4. Retiro y archivado", "El ciclo completo por eventos.", retiro),
    ]),
    ("variable", [
        OD([("key", "adminToken"), ("value", "")]),
        OD([("key", "userToken"), ("value", "")]),
        OD([("key", "tokenActivacion"), ("value", "")]),
        OD([("key", "empleadoId"), ("value", "")]),
        OD([("key", "empleadoEmail"), ("value", "")]),
        OD([("key", "numeroEmpleado"), ("value", "")]),
        OD([("key", "ajenoId"), ("value", "")]),
        OD([("key", "ajenoEmail"), ("value", "")]),
        OD([("key", "ajenoNumero"), ("value", "")]),
    ]),
])

destino = "perfiles-service.postman_collection.json"
io.open(destino, "w", encoding="utf-8", newline="\n").write(
    json.dumps(coleccion, indent=2, ensure_ascii=False) + "\n")

total = sum(len(c["item"]) for c in coleccion["item"])
print("Escrito %s | %d carpetas, %d peticiones" % (destino, len(coleccion["item"]), total))
