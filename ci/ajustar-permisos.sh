#!/bin/sh
set -eu
# Los agentes usan root para escribir sus cachés. Jenkins debe poder borrar
# después esos archivos al preparar el siguiente checkout.
: "${WORKSPACE:?Falta el workspace de Jenkins}"
: "${JENKINS_HOME:?Este script se ejecuta desde Jenkins}"
case "$WORKSPACE" in "$JENKINS_HOME"/workspace/*) ;; *) echo 'Workspace fuera de Jenkins' >&2; exit 1 ;; esac
docker run --rm --user 0:0 --volumes-from "$(hostname)" alpine:3.21 \
    chown -R "$(id -u):$(id -g)" "$WORKSPACE"
