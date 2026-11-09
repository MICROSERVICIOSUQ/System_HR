#!/usr/bin/env bash

set -euo pipefail

FRONTEND_NETWORK="${FRONTEND_NETWORK:-system_hr_frontend_gateway}"
MICROSERVICES_NETWORK="${MICROSERVICES_NETWORK:-system_hr_microservices}"

failed=0

check() {
    local description="$1"
    shift

    if "$@"; then
        printf '✓ %s\n' "${description}"
    else
        printf '✗ %s\n' "${description}"
        failed=1
    fi
}

network_exists() {
    docker network inspect "$1" >/dev/null 2>&1
}

network_containers() {
    docker network inspect --format '{{range .Containers}}{{println .Name}}{{end}}' "$1"
}

has_container() {
    local network="$1"
    local service="$2"
    local containers

    containers="$(network_containers "${network}")"
    printf '%s\n' "${containers}" |
        grep -Eq "(^|[-_])${service}(-[0-9]+)?$"
}

frontend_has_only_gateway() {
    local containers
    containers="$(network_containers "${FRONTEND_NETWORK}")"

    [[ -n "${containers}" ]] &&
        ! printf '%s\n' "${containers}" |
            grep -Eqv '(^|[-_])api-gateway(-[0-9]+)?$'
}

frontend_has_no_data_services() {
    local containers
    containers="$(network_containers "${FRONTEND_NETWORK}")"

    ! printf '%s\n' "${containers}" |
        grep -Eiq '(^|[-_])(postgres|postgresql|rabbitmq|database-[^[:space:]]*|db-[^[:space:]]*)($|[-_])'
}

check "Existe la red ${FRONTEND_NETWORK}" network_exists "${FRONTEND_NETWORK}"
if network_exists "${FRONTEND_NETWORK}"; then
    check "Solo api-gateway está conectado a ${FRONTEND_NETWORK}" \
        frontend_has_only_gateway
    check "Postgres y RabbitMQ no están en ${FRONTEND_NETWORK}" \
        frontend_has_no_data_services
else
    failed=1
fi

check "Existe la red ${MICROSERVICES_NETWORK}" network_exists "${MICROSERVICES_NETWORK}"
if network_exists "${MICROSERVICES_NETWORK}"; then
    for service in \
        api-gateway \
        auth-service \
        departments-service \
        employees-service \
        notifications-service \
        profiles-service \
        vacations-service; do
        check "${service} está en ${MICROSERVICES_NETWORK}" \
            has_container "${MICROSERVICES_NETWORK}" "${service}"
    done
else
    failed=1
fi

if (( failed != 0 )); then
    exit 1
fi

exit 0
