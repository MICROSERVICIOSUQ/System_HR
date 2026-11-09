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


def auth_header():
    if SONAR_TOKEN:
        credentials = f"{SONAR_TOKEN}:"
    elif SONAR_PASSWORD:
        credentials = f"admin:{SONAR_PASSWORD}"
    else:
        fail("Define SONAR_TOKEN o SONAR_ADMIN_PASSWORD.")

    encoded = base64.b64encode(credentials.encode()).decode()
    return {"Authorization": f"Basic {encoded}"}


def request(endpoint, params=None, data=None):
    query = f"?{urlencode(params)}" if params else ""
    body = urlencode(data).encode() if data is not None else None
    method = "POST" if data is not None else "GET"
    headers = {
        **auth_header(),
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
        fail(f"{method} /api/{endpoint} falló: {detail}")


def wait_until_ready():
    for attempt in range(60):
        try:
            with urlopen(
                Request(
                    f"{SONAR_URL}/api/system/status",
                    headers=auth_header(),
                ),
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
            request("projects/create", {"project": key, "name": key})
            print(f"Proyecto creado: {key}")
        else:
            print(f"Proyecto existente: {key}")


def ensure_quality_gate():
    gates = request("qualitygates/list").get("qualitygates", [])
    gate = next((item for item in gates if item.get("name") == GATE_NAME), None)

    if gate is None:
        request("qualitygates/create", {"name": GATE_NAME})
        print(f"Quality Gate creado: {GATE_NAME}")
    else:
        print(f"Quality Gate existente: {GATE_NAME}")

    current = request("qualitygates/show", {"name": GATE_NAME})
    for condition in current.get("conditions", []):
        request("qualitygates/delete_condition", {"id": condition["id"]})

    for metric in ("coverage", "new_coverage"):
        request(
            "qualitygates/create_condition",
            {
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
            {"projectKey": project, "gateName": GATE_NAME},
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
        selected = association.get("name") or association.get("gate", {}).get("name")
        if selected != GATE_NAME:
            fail(f"{project} está asociado a {selected!r}, no a {GATE_NAME!r}.")
        print(f"Verificada asociación: {project} -> {GATE_NAME}")


def main():
    projects = project_keys()
    wait_until_ready()
    ensure_projects(projects)
    ensure_quality_gate()
    associate_projects(projects)
    verify(projects)
    print("Configuración de SonarQube completada correctamente.")


if __name__ == "__main__":
    main()
