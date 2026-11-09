#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ROOT_DIR}/.env"
BACKUP_FILE="${ROOT_DIR}/.env.failfast-backup.$$"
OUTPUT_FILE="$(mktemp)"
ENV_MOVED=0

restore_env() {
    local restore_status=0

    if (( ENV_MOVED == 1 )); then
        mv -- "${BACKUP_FILE}" "${ENV_FILE}" || restore_status=$?
        ENV_MOVED=0
    fi

    rm -f -- "${OUTPUT_FILE}"

    if (( restore_status != 0 )); then
        printf '✗ No se pudo restaurar %s\n' "${ENV_FILE}" >&2
        return "${restore_status}"
    fi
}

trap restore_env EXIT

if [[ -f "${ENV_FILE}" ]]; then
    mv -- "${ENV_FILE}" "${BACKUP_FILE}"
    ENV_MOVED=1
    printf '✓ .env renombrado temporalmente\n'
else
    printf '✓ .env no existe; se prueba sin archivo de entorno\n'
fi

compose_status=0
if docker compose -f "${ROOT_DIR}/docker-compose.yml" config \
    >"${OUTPUT_FILE}" 2>&1; then
    compose_status=0
else
    compose_status=$?
fi

if (( compose_status == 0 )); then
    printf '✗ docker compose config no falló\n'
    exit 1
fi
printf '✓ docker compose config falló como se esperaba\n'

if grep -Eq 'JWT_SECRET|Defina JWT_SECRET' "${OUTPUT_FILE}"; then
    printf '✓ El fallo corresponde a JWT_SECRET requerido\n'
else
    printf '✗ El fallo no corresponde a JWT_SECRET requerido\n'
    printf '%s\n' "----- salida de docker compose config -----" >&2
    cat "${OUTPUT_FILE}" >&2
    exit 1
fi

printf '✓ Fail-fast de JWT_SECRET funciona correctamente\n'
exit 0
