#!/usr/bin/env python3
"""Prueba real del Reto 3. Requiere Docker Compose levantado; detiene y restaura departamentos.
Ejecutar: python3 tests/reto3_smoke.py [--base-url http://localhost:8080]
Genera evidencia JSON en stdout. Usa identificadores nuevos y no elimina datos ni volúmenes.
"""
import argparse
import json
import subprocess
import time
import uuid
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]


def compose(*args):
    return subprocess.check_output(["docker", "compose", *args], cwd=ROOT, text=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8080")
    args = parser.parse_args()
    base = args.base_url.rstrip("/")

    def request(path, expected, payload=None):
        start = time.monotonic()
        req = Request(base + path, data=None if payload is None else json.dumps(payload).encode(),
                      headers={"Content-Type": "application/json"})
        try:
            response = urlopen(req, timeout=50)
        except HTTPError as error:
            response = error
        with response:
            status = response.code
            body = json.loads(response.read())
        elapsed = time.monotonic() - start
        print(json.dumps({"path": path, "status": status, "seconds": round(elapsed, 3),
                          "body": body}, ensure_ascii=False), flush=True)
        assert status == expected, (path, status, expected, body)
        return body, elapsed

    running = compose("ps", "--services", "--status", "running").splitlines()
    assert all(s in running for s in ("api-gateway", "employees-service", "departments-service")), \
        "Primero ejecuta docker compose up --build -d"
    config = json.loads(compose("config", "--format", "json"))
    for name, service in config["services"].items():
        if name != "api-gateway":
            assert not service.get("ports"), f"{name} publica puertos"
    # Verify actual containers too, not only the Compose declaration.
    for service in ("employees-service", "departments-service"):
        container = compose("ps", "-q", service).strip()
        bindings = json.loads(subprocess.check_output(
            ["docker", "inspect", "--format", "{{json .NetworkSettings.Ports}}", container], text=True))
        assert all(not ports for ports in bindings.values()), (service, bindings)
        print(json.dumps({"service": service, "publishedPorts": bindings}), flush=True)
    request("/health", 200)
    state, _ = request("/empleados/circuit-breaker", 200)
    assert state["state"] == "CLOSED", "Espera la recuperación del circuito antes de probar"
    token = uuid.uuid4().hex[:12]
    department = "CB-" + token
    request("/departamentos", 201, {"id": department, "nombre": "Prueba circuito",
                                   "descripcion": "Prueba automatizada del Reto 3"})
    request("/departamentos/" + department, 200)

    def employee(i, dep=department):
        return {"id": f"CB-{token}-{i}", "nombre": "Prueba", "apellido": "Circuito",
                "email": f"cb-{token}-{i}@example.com", "numeroEmpleado": f"CB-{token}-{i}",
                "cargo": "Desarrollo", "area": "TI", "departamentoId": dep,
                "fechaIngreso": "2026-09-22"}

    request("/empleados", 201, employee("sano"))
    try:
        compose("stop", "departments-service")
        request("/departamentos", 503)
        request("/empleados", 200)  # The independent operation remains available.
        times = []
        for i in range(8):
            _, elapsed = request("/empleados", 503, employee(i))
            times.append(elapsed)
            state, _ = request("/empleados/circuit-breaker", 200)
            if i >= 2:
                assert state["state"] == "OPEN", state
        assert max(times[3:]) < min(times[:3]), times
    finally:
        # Always restore the dependency, including after failed assertions.
        compose("start", "departments-service")
    deadline = time.monotonic() + 90
    while True:
        container = compose("ps", "-q", "departments-service").strip()
        health = subprocess.check_output(["docker", "inspect", "--format",
                    "{{.State.Health.Status}}", container], text=True).strip()
        state, _ = request("/empleados/circuit-breaker", 200)
        if health == "healthy" and state["state"] == "HALF_OPEN":
            break
        assert time.monotonic() < deadline, "No llegó a HALF_OPEN con departamentos sano"
        time.sleep(2)
    body, _ = request("/empleados", 400, employee("recuperado", "NO-EXISTE-" + token))
    assert "departamento" in body["message"].lower(), body
    state, _ = request("/empleados/circuit-breaker", 200)
    assert state["state"] == "CLOSED", state
    print(json.dumps({"result": "PASS", "failureResponseSeconds": times}), flush=True)


if __name__ == "__main__":
    main()
