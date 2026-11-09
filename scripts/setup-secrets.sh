#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ROOT_DIR}/.env"
ENV_EXAMPLE="${ROOT_DIR}/.env.example"

if [[ ! -f "${ENV_FILE}" ]]; then
    if [[ ! -f "${ENV_EXAMPLE}" ]]; then
        printf 'Error: no se encontró %s\n' "${ENV_EXAMPLE}" >&2
        exit 1
    fi

    cp -- "${ENV_EXAMPLE}" "${ENV_FILE}"
    printf 'Creado %s desde .env.example\n' "${ENV_FILE}"
else
    printf 'Usando el .env existente: %s\n' "${ENV_FILE}"
fi

if ! command -v openssl >/dev/null 2>&1; then
    printf 'Error: openssl es necesario para generar los secretos.\n' >&2
    exit 1
fi

read_env_value() {
    local key="$1"
    sed -n "s/^${key}=//p" "${ENV_FILE}" | head -n 1
}

JWT_SECRET="$(read_env_value JWT_SECRET)"
DB_PASSWORD="$(read_env_value DB_PASSWORD)"
RABBITMQ_PASSWORD="$(read_env_value RABBITMQ_PASSWORD)"
JENKINS_ADMIN_PASSWORD="$(read_env_value JENKINS_ADMIN_PASSWORD)"
SONAR_DB_PASSWORD="$(read_env_value SONAR_DB_PASSWORD)"
SONAR_ADMIN_PASSWORD="$(read_env_value SONAR_ADMIN_PASSWORD)"

[[ -n "${JWT_SECRET}" ]] || JWT_SECRET="$(openssl rand -base64 48)"
[[ -n "${DB_PASSWORD}" ]] || DB_PASSWORD="$(openssl rand -base64 48)"
[[ -n "${RABBITMQ_PASSWORD}" ]] || RABBITMQ_PASSWORD="$(openssl rand -base64 48)"
[[ -n "${JENKINS_ADMIN_PASSWORD}" ]] || JENKINS_ADMIN_PASSWORD="$(openssl rand -base64 32)"
[[ -n "${SONAR_DB_PASSWORD}" ]] || SONAR_DB_PASSWORD="$(openssl rand -base64 32)"
[[ -n "${SONAR_ADMIN_PASSWORD}" ]] || SONAR_ADMIN_PASSWORD="$(openssl rand -base64 32)"
TEMP_FILE="$(mktemp "${ENV_FILE}.tmp.XXXXXX")"

cleanup() {
    rm -f -- "${TEMP_FILE}"
}
trap cleanup EXIT

awk \
    -v jwt_secret="${JWT_SECRET}" \
    -v db_password="${DB_PASSWORD}" \
    -v rabbitmq_password="${RABBITMQ_PASSWORD}" \
    -v jenkins_admin_password="${JENKINS_ADMIN_PASSWORD}" \
    -v sonar_db_password="${SONAR_DB_PASSWORD}" \
    -v sonar_admin_password="${SONAR_ADMIN_PASSWORD}" '
BEGIN {
    found_jenkins = found_sonar_db = found_sonar_admin = 0
}

function replace_value(line, key, value,    prefix) {
    prefix = substr(line, 1, index(line, "="))
    return prefix value
}

{
    if ($0 ~ /^[[:space:]]*JWT_SECRET=/) {
        print replace_value($0, "JWT_SECRET", jwt_secret)
    } else if ($0 ~ /^[[:space:]]*JENKINS_ADMIN_PASSWORD=/) {
        found_jenkins = 1
        print replace_value($0, "JENKINS_ADMIN_PASSWORD", jenkins_admin_password)
    } else if ($0 ~ /^[[:space:]]*SONAR_DB_PASSWORD=/) {
        found_sonar_db = 1
        print replace_value($0, "SONAR_DB_PASSWORD", sonar_db_password)
    } else if ($0 ~ /^[[:space:]]*SONAR_ADMIN_PASSWORD=/) {
        found_sonar_admin = 1
        print replace_value($0, "SONAR_ADMIN_PASSWORD", sonar_admin_password)
    } else if ($0 ~ /^[[:space:]]*[A-Za-z_][A-Za-z0-9_]*DB_PASSWORD=/) {
        print replace_value($0, "DB_PASSWORD", db_password)
    } else if ($0 ~ /^[[:space:]]*RABBITMQ_PASSWORD=/) {
        print replace_value($0, "RABBITMQ_PASSWORD", rabbitmq_password)
    } else {
        print
    }
}

END {
    if (!found_jenkins) print "JENKINS_ADMIN_PASSWORD=" jenkins_admin_password
    if (!found_sonar_db) print "SONAR_DB_PASSWORD=" sonar_db_password
    if (!found_sonar_admin) print "SONAR_ADMIN_PASSWORD=" sonar_admin_password
}
' "${ENV_FILE}" > "${TEMP_FILE}"

mv -- "${TEMP_FILE}" "${ENV_FILE}"
chmod 600 "${ENV_FILE}"

printf 'Secretos JWT, de bases de datos y de RabbitMQ configurados.\n'
printf 'Permisos aplicados: chmod 600 %s\n' "${ENV_FILE}"
