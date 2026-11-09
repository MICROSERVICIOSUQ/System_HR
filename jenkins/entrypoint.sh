#!/bin/sh
set -eu

# La contraseña ya no es obligatoria para interpolar el Compose —lo era con
# ${JENKINS_ADMIN_PASSWORD:?...} y eso impedia levantar la aplicacion entera con
# un .env antiguo—, asi que la comprobacion vive aqui: este contenedor si la
# necesita. casc.yaml crea el usuario administrador con ella y, vacia, Jenkins
# quedaria con una cuenta sin contrasena.
if [ -z "${JENKINS_ADMIN_PASSWORD:-}" ]; then
    echo "ERROR: JENKINS_ADMIN_PASSWORD esta vacia." >&2
    echo "Ejecute scripts/setup-secrets.sh antes de 'docker compose --profile ci up'." >&2
    exit 1
fi

# El grupo del socket depende del equipo; no siempre se llama docker.
socket_gid=$(stat -c '%g' /var/run/docker.sock)
if ! getent group "$socket_gid" >/dev/null; then
    groupadd -g "$socket_gid" docker-host
fi
usermod -aG "$socket_gid" jenkins
# La fuente de validación local vive en el volumen, por lo que sobrevive a recrear Jenkins.
if [ "${CI_REPOSITORY_URL:-}" = 'git://jenkins:9419/source.git' ] &&
   [ -d "$JENKINS_HOME/ci-source/source.git" ]; then
    gosu jenkins git daemon --detach --reuseaddr --export-all \
        --base-path="$JENKINS_HOME/ci-source" --listen=0.0.0.0 --port=9419 \
        "$JENKINS_HOME/ci-source"
fi
exec gosu jenkins /usr/local/bin/jenkins.sh "$@"
