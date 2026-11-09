#!/bin/sh
set -eu
: "${CI_PROJECT:?Falta el nombre del proyecto de pruebas}"
: "${CI_SERVICE:?Falta el microservicio evaluado}"
: "${BUILD_NUMBER:?Falta el número del build}"
case "$CI_PROJECT" in hr-ci-*) ;; *) echo 'La limpieza solo admite proyectos hr-ci-*' >&2; exit 1 ;; esac
compose_file="reports/e2e/compose.json"
compose() { docker compose -p "$CI_PROJECT" -f "$compose_file" "$@"; }
mkdir -p reports/e2e e2e-tests/reports
case "${1:-run}" in
    run)
        # Cada build tiene sus propias claves y sus propios volúmenes.
        export JWT_SECRET=$(python3 -c 'import secrets; print(secrets.token_hex(32))')
        export JENKINS_ADMIN_PASSWORD=unused SONAR_ADMIN_PASSWORD=unused SONAR_DB_PASSWORD=unused
        export VACACIONES_SCHEDULER_ENABLED=false
        export AUTH_SEED_ADMIN_PASSWORD=$(python3 -c 'import secrets; print(secrets.token_urlsafe(24))')
        docker build -f ci/Dockerfile.rabbitmq -t "$CI_PROJECT-rabbitmq" .
        docker compose --profile testing --env-file /dev/null -f docker-compose.yml config --format json |
            python3 ci/crear-compose-e2e.py "$CI_PROJECT-rabbitmq" "$CI_SERVICE" "system-hr/$CI_SERVICE:$BUILD_NUMBER" > "$compose_file"
        # El JSON contiene las claves efímeras: se borra y nunca se archiva.
        chmod 600 "$compose_file"
        compose build
        # La imagen evaluada en Package es exactamente la que ejecuta E2E.
        compose up -d --wait --wait-timeout 600 api-gateway employees-service \
            departments-service auth-service vacations-service notifications-service profiles-service
        compose create bdd-tests
        container=$(compose ps -aq bdd-tests)
        result=0
        docker start -a "$container" || result=$?
        exit_code=$(docker inspect -f '{{.State.ExitCode}}' "$container")
        docker cp "$container:/app/reports/." e2e-tests/reports/ || exit 1
        [ "$result" -eq 0 ] && [ "$exit_code" -eq 0 ]
        ;;
    clean)
        if [ -s "$compose_file" ]; then
            result=0
            compose logs --no-color > reports/e2e/system.log 2>&1 || result=$?
            if compose down -v --remove-orphans; then
                rm -f "$compose_file"
            else
                # Conservar el archivo permite reintentar la limpieza en post.
                exit 1
            fi
            docker image rm "$CI_PROJECT-rabbitmq" >/dev/null 2>&1 || true
            exit "$result"
        fi
        docker image rm "$CI_PROJECT-rabbitmq" >/dev/null 2>&1 || true
        ;;
    *) echo 'Uso: sh ci/e2e.sh [run|clean]' >&2; exit 1 ;;
esac
