#!/usr/bin/env python3
"""Configura y verifica el Quality Gate de SonarQube para System HR."""

import base64
import json
import os
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


SONAR_URL = os.environ.get("SONAR_URL", "http://localhost:9000").rstrip("/")
SONAR_PASSWORD = os.environ.get("SONAR_ADMIN_PASSWORD")
SONAR_TOKEN = os.environ.get("SONAR_TOKEN")
GATE_NAME = "RRHH-70"

# Contraseña de fábrica de SonarQube. En el primer arranque es la única que
# sirve, así que hay que usarla para cambiarla por la del .env; en los
# siguientes ya no vale y se usa la nueva.
SONAR_PASSWORD_INICIAL = "admin"

# Jenkins lee el token desde su directorio SECRETS: casc.yaml resuelve
# ${SONAR_TOKEN} buscando un archivo con ese nombre. Es el único puente entre
# los dos contenedores, porque el token solo existe después de crearlo aquí.
DIRECTORIO_DE_SECRETOS = os.environ.get("SONAR_SECRETS_DIR")
NOMBRE_DEL_TOKEN = "jenkins-system-hr"

# Webhook con el que SonarQube avisa a Jenkins de que un analisis termino.
# Sin el, el paso waitForQualityGate de los pipelines se queda esperando hasta
# agotar su timeout de 10 minutos y la ejecucion acaba en ABORTED.
NOMBRE_DEL_WEBHOOK = "Jenkins"
URL_DEL_WEBHOOK = os.environ.get(
    "SONAR_WEBHOOK_URL", "http://jenkins:8080/sonarqube-webhook/"
)
DEFAULT_PROJECTS = (
    "employees-service",
    "auth-service",
    "api-gateway",
    "departments-service",
    "notifications-service",
    "profiles-service",
    "vacations-service",
)


def fail(message):
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def auth_header(password=None, token=None):
    if token is not None:
        credentials = f"{token}:"
    elif password is not None:
        credentials = f"admin:{password}"
    elif SONAR_TOKEN:
        credentials = f"{SONAR_TOKEN}:"
    elif SONAR_PASSWORD:
        credentials = f"admin:{SONAR_PASSWORD}"
    else:
        fail("Define SONAR_TOKEN o SONAR_ADMIN_PASSWORD.")

    encoded = base64.b64encode(credentials.encode()).decode()
    return {"Authorization": f"Basic {encoded}"}


def request(endpoint, params=None, data=None, password=None, token=None, tolerar_error=False):
    query = f"?{urlencode(params)}" if params else ""
    body = urlencode(data).encode() if data is not None else None
    method = "POST" if data is not None else "GET"
    headers = {
        **auth_header(password, token),
        "Accept": "application/json",
    }
    if data is not None:
        headers["Content-Type"] = "application/x-www-form-urlencoded"

    try:
        with urlopen(
            Request(
                f"{SONAR_URL}/api/{endpoint}{query}",
                data=body,
                headers=headers,
                method=method,
            ),
            timeout=30,
        ) as response:
            content = response.read()
            return json.loads(content) if content else {}
    except (HTTPError, URLError, TimeoutError) as error:
        detail = error.read().decode(errors="replace") if isinstance(error, HTTPError) else str(error)
        if tolerar_error:
            return None
        fail(f"{method} /api/{endpoint} falló: {detail}")


def wait_until_ready():
    for attempt in range(60):
        try:
            # /api/system/status es público: consultarlo con credenciales
            # obligaba a tenerlas correctas antes de poder esperar al servidor,
            # y en el primer arranque todavía no lo están.
            with urlopen(
                Request(f"{SONAR_URL}/api/system/status"),
                timeout=10,
            ) as response:
                status = json.loads(response.read()).get("status")
            if status == "UP":
                return
        except (HTTPError, URLError, TimeoutError, json.JSONDecodeError):
            pass
        time.sleep(5)

    fail("SonarQube no estuvo disponible en cinco minutos.")


def project_keys():
    configured = os.environ.get("SONAR_PROJECTS")
    projects = tuple(
        item.strip()
        for item in (configured.split(",") if configured else DEFAULT_PROJECTS)
        if item.strip()
    )
    if len(projects) != 7:
        fail("SONAR_PROJECTS debe contener exactamente siete proyectos.")
    return projects


def ensure_projects(projects):
    for key in projects:
        found = request("projects/search", {"projects": key}).get("components", [])
        if not found:
            # Con data= y no params=: request() deduce el método del cuerpo, y
            # estos endpoints de SonarQube solo aceptan POST ("HTTP method POST
            # is required"). Se enviaban como GET y la creación fallaba.
            request("projects/create", data={"project": key, "name": key})
            print(f"Proyecto creado: {key}")
        else:
            print(f"Proyecto existente: {key}")


def ensure_quality_gate():
    gates = request("qualitygates/list").get("qualitygates", [])
    gate = next((item for item in gates if item.get("name") == GATE_NAME), None)

    if gate is None:
        request("qualitygates/create", data={"name": GATE_NAME})
        print(f"Quality Gate creado: {GATE_NAME}")
    else:
        print(f"Quality Gate existente: {GATE_NAME}")

    current = request("qualitygates/show", {"name": GATE_NAME})
    for condition in current.get("conditions", []):
        request("qualitygates/delete_condition", data={"id": condition["id"]})

    for metric in ("coverage", "new_coverage"):
        request(
            "qualitygates/create_condition",
            data={
                "gateName": GATE_NAME,
                "metric": metric,
                "op": "LT",
                "error": "70",
            },
        )
        print(f"Condición configurada: {metric} < 70")


def associate_projects(projects):
    for project in projects:
        request(
            "qualitygates/select",
            data={"projectKey": project, "gateName": GATE_NAME},
        )
        print(f"Quality Gate asociado: {project}")


def verify(projects):
    gate = request("qualitygates/show", {"name": GATE_NAME})
    expected = {("coverage", "LT", "70"), ("new_coverage", "LT", "70")}
    actual = {
        (item.get("metric"), item.get("op"), str(item.get("error")))
        for item in gate.get("conditions", [])
    }
    if actual != expected:
        fail(f"Condiciones inesperadas en {GATE_NAME}: {sorted(actual)}")

    print(f"Verificado {GATE_NAME}: coverage < 70 y new_coverage < 70")

    for project in projects:
        association = request("qualitygates/get_by_project", {"project": project})
        # La respuesta real de SonarQube 10 es {"qualityGate": {"name": ...}}.
        # Se leía con .get("name") y .get("gate", {}).get("name"), que no
        # existen, así que la verificación final siempre daba None y el script
        # terminaba en error justo después de haberlo configurado todo bien.
        selected = (
            association.get("name")
            or association.get("qualityGate", {}).get("name")
            or association.get("gate", {}).get("name")
        )
        if selected != GATE_NAME:
            fail(f"{project} está asociado a {selected!r}, no a {GATE_NAME!r}.")
        print(f"Verificada asociación: {project} -> {GATE_NAME}")


def ensure_admin_password():
    """Deja la cuenta admin con la contraseña del .env.

    SonarQube arranca con admin/admin y este script se autenticaba directamente
    con SONAR_ADMIN_PASSWORD, que nadie había establecido: en una instancia
    recién creada todas las llamadas respondían 401 y la configuración del
    Quality Gate no llegaba a ejecutarse.

    Se prueba primero con la contraseña definitiva —el caso normal a partir de
    la segunda ejecución— y solo si no sirve se intenta el cambio desde la de
    fábrica. Así la función es idempotente y no falla al repetirse.
    """
    if not SONAR_PASSWORD:
        fail(
            "SONAR_ADMIN_PASSWORD está vacía. "
            "Ejecute scripts/setup-secrets.sh antes de levantar el perfil ci."
        )

    # Ojo con /api/authentication/validate: responde 200 con {"valid": false}
    # cuando las credenciales no sirven, así que comprobar solo que la llamada
    # no falle da siempre verdadero. Hay que leer el campo.
    comprobacion = request(
        "authentication/validate", password=SONAR_PASSWORD, tolerar_error=True
    )
    if (comprobacion or {}).get("valid") is True:
        print("La contraseña de admin ya estaba establecida.")
        return

    cambiado = request(
        "users/change_password",
        data={
            "login": "admin",
            "previousPassword": SONAR_PASSWORD_INICIAL,
            "password": SONAR_PASSWORD,
        },
        password=SONAR_PASSWORD_INICIAL,
        tolerar_error=True,
    )
    if cambiado is None:
        fail(
            "No se pudo autenticar con SONAR_ADMIN_PASSWORD ni cambiar la "
            "contraseña de fábrica. Si ya cambió la de admin a mano, ponga ese "
            "mismo valor en SONAR_ADMIN_PASSWORD."
        )
    print("Contraseña de admin establecida desde el .env.")


def ensure_token():
    """Crea el token de Jenkins y lo escribe donde casc.yaml lo espera.

    casc.yaml declara la credencial 'sonar-token' con el valor ${SONAR_TOKEN} y
    Jenkins resuelve esa variable desde su directorio SECRETS, que es el volumen
    sonar_credentials. Nadie escribía ese archivo, así que la credencial del
    análisis quedaba sin resolver y las etapas 'Sonar Scan' de los siete
    pipelines no tenían con qué autenticarse.

    Si ya hay un token escrito y SonarQube lo sigue aceptando, se conserva.
    Regenerarlo en cada ejecución parece inofensivo y no lo es: Jenkins lee el
    archivo UNA vez, al arrancar, cuando casc resuelve la credencial. Volver a
    ejecutar este script revocaba ese token y los análisis empezaban a fallar con
    "Not authorized" hasta reiniciar Jenkins. Solo se crea uno nuevo cuando no
    hay ninguno o el que hay ya no vale, porque la API de SonarQube devuelve el
    valor únicamente en el momento de crearlo.
    """
    if not DIRECTORIO_DE_SECRETOS:
        print("SONAR_SECRETS_DIR no está definida: no se escribe el token.")
        return

    destino_previo = os.path.join(DIRECTORIO_DE_SECRETOS, "SONAR_TOKEN")
    if os.path.exists(destino_previo):
        with open(destino_previo, encoding="utf-8") as archivo:
            token_previo = archivo.read().strip()
        if token_previo:
            vigente = request(
                "authentication/validate", token=token_previo, tolerar_error=True
            )
            if (vigente or {}).get("valid") is True:
                print("El token del análisis sigue siendo válido; se conserva.")
                return
            print("El token escrito ya no es válido; se emite uno nuevo.")

    request(
        "user_tokens/revoke",
        data={"name": NOMBRE_DEL_TOKEN},
        password=SONAR_PASSWORD,
        tolerar_error=True,
    )
    creado = request(
        "user_tokens/generate",
        data={"name": NOMBRE_DEL_TOKEN},
        password=SONAR_PASSWORD,
    )
    token = (creado or {}).get("token")
    if not token:
        fail("SonarQube no devolvió el token del análisis.")

    os.makedirs(DIRECTORIO_DE_SECRETOS, exist_ok=True)
    destino = os.path.join(DIRECTORIO_DE_SECRETOS, "SONAR_TOKEN")
    # Sin salto de línea final: Jenkins usa el contenido literal del archivo
    # como valor de la variable, y un salto se convertiria en parte del token.
    with open(destino, "w", encoding="utf-8") as archivo:
        archivo.write(token)
    # 0o644 y no 0o600: este contenedor corre como root y Jenkins como el
    # usuario 'jenkins', así que con 0o600 el plugin configuration-as-code no
    # podía leerlo y abortaba con AccessDeniedException sobre /run/sonar. El
    # archivo vive en un volumen que solo comparten estos dos contenedores.
    os.chmod(destino, 0o644)
    print(f"Token '{NOMBRE_DEL_TOKEN}' escrito en {destino}")


def ensure_webhook():
    """Registra el webhook que avisa a Jenkins cuando termina un analisis.

    El paso `waitForQualityGate` de los Jenkinsfile no consulta a SonarQube: se
    queda esperando a que SonarQube le avise. Si el webhook no existe, la espera
    agota el timeout de la etapa —10 minutos— y el pipeline termina en ABORTED
    despues de haber hecho todo bien. Les pasaba a los tres pipelines Java.

    Es idempotente: si ya hay un webhook con el mismo nombre y la misma URL, no
    se toca; si la URL cambio, se borra y se vuelve a crear, porque la API no
    permite actualizarla.
    """
    existentes = (request("webhooks/list") or {}).get("webhooks", [])
    for webhook in existentes:
        if webhook.get("name") != NOMBRE_DEL_WEBHOOK:
            continue
        if webhook.get("url") == URL_DEL_WEBHOOK:
            print(f"Webhook '{NOMBRE_DEL_WEBHOOK}' ya configurado.")
            return
        request("webhooks/delete", data={"webhook": webhook["key"]})
        print(f"Webhook '{NOMBRE_DEL_WEBHOOK}' tenia otra URL: se recrea.")
        break

    request(
        "webhooks/create",
        data={"name": NOMBRE_DEL_WEBHOOK, "url": URL_DEL_WEBHOOK},
    )
    print(f"Webhook '{NOMBRE_DEL_WEBHOOK}' creado -> {URL_DEL_WEBHOOK}")


def main():
    projects = project_keys()
    wait_until_ready()
    ensure_admin_password()
    ensure_webhook()
    ensure_projects(projects)
    ensure_quality_gate()
    associate_projects(projects)
    verify(projects)
    ensure_token()
    print("Configuración de SonarQube completada correctamente.")


if __name__ == "__main__":
    main()
