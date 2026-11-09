#!/usr/bin/env bash
#
# Rellena los secretos del .env que estén vacíos.
#
# REGLA PRINCIPAL: no se sobrescribe ningún valor que ya exista.
#
# La versión anterior reescribía con un valor nuevo TODAS las líneas que
# terminaran en _DB_PASSWORD, además de RABBITMQ_PASSWORD y JWT_SECRET. Sobre un
# entorno recién clonado eso funciona, pero sobre uno que ya se ha levantado
# rompe el sistema entero: PostgreSQL y RabbitMQ fijan sus credenciales cuando se
# inicializa su volumen y no vuelven a leerlas, así que cambiar la contraseña en
# el .env deja a los siete servicios sin poder conectarse, y recuperarlo exige
# borrar los volúmenes —y con ellos los datos—.
#
# Un script de arranque no puede tener como efecto secundario la pérdida de la
# base de datos. Por eso ahora solo rellena huecos, es idempotente y se puede
# ejecutar tantas veces como se quiera.
#
# Para rotar un secreto a propósito: vacíe su línea en el .env y vuelva a
# ejecutar este script. Si es una contraseña de base de datos o de RabbitMQ,
# tendrá que recrear también su volumen.

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

# Claves que este script sabe rellenar, con la longitud en bytes del valor
# aleatorio. El JWT necesita 32 bytes como mínimo por el algoritmo HS256; para
# las demás 32 son de sobra.
CLAVES=(
    'JWT_SECRET:48'
    'JENKINS_ADMIN_PASSWORD:32'
    'SONAR_DB_PASSWORD:32'
    'SONAR_ADMIN_PASSWORD:32'
)

valor_de() {
    # Primera aparición de la clave, sin comillas ni espacios alrededor.
    sed -n "s/^[[:space:]]*$1=//p" "${ENV_FILE}" | head -n 1 \
        | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'\$/\1/"
}

clave_existe() {
    grep -Eq "^[[:space:]]*$1=" "${ENV_FILE}"
}

escribir_valor() {
    # Sustituye solo el valor, conservando el nombre y el comentario de la línea.
    # El valor va por argumento de awk y no por el patrón de sed: un secreto en
    # base64 contiene '/' y '+', que sed interpretaría.
    local clave="$1" valor="$2" temporal
    temporal="$(mktemp "${ENV_FILE}.tmp.XXXXXX")"

    awk -v clave="${clave}" -v valor="${valor}" '
        $0 ~ "^[[:space:]]*" clave "=" && !hecho {
            print clave "=" valor
            hecho = 1
            next
        }
        { print }
    ' "${ENV_FILE}" > "${temporal}"

    mv -- "${temporal}" "${ENV_FILE}"
}

generados=0
conservados=0

for entrada in "${CLAVES[@]}"; do
    clave="${entrada%%:*}"
    longitud="${entrada##*:}"

    if ! clave_existe "${clave}"; then
        printf '%s=%s\n' "${clave}" "$(openssl rand -base64 "${longitud}" | tr -d '\n')" >> "${ENV_FILE}"
        printf '  %-24s generado (la clave no estaba en el archivo)\n' "${clave}"
        generados=$((generados + 1))
        continue
    fi

    if [[ -n "$(valor_de "${clave}")" ]]; then
        printf '  %-24s ya tenía valor, se conserva\n' "${clave}"
        conservados=$((conservados + 1))
        continue
    fi

    escribir_valor "${clave}" "$(openssl rand -base64 "${longitud}" | tr -d '\n')"
    printf '  %-24s generado\n' "${clave}"
    generados=$((generados + 1))
done

# Las contraseñas de las bases de datos y de RabbitMQ NO se tocan, ni siquiera
# cuando están vacías: sus valores tienen que coincidir con los que quedaron
# grabados al inicializar cada volumen. Si alguna está vacía se avisa, porque
# entonces el servicio no podrá conectarse, pero la decisión de cambiarla —y de
# recrear el volumen— es de quien ejecuta el script, no del script.
faltantes=()
while IFS= read -r clave; do
    [[ -n "$(valor_de "${clave}")" ]] || faltantes+=("${clave}")
done < <(grep -Eo '^[[:space:]]*[A-Za-z_][A-Za-z0-9_]*(DB_PASSWORD|RABBITMQ_PASSWORD)=' "${ENV_FILE}" \
         | tr -d ' ' | sed 's/=$//' | sort -u)

if ((${#faltantes[@]} > 0)); then
    printf '\nAviso: estas credenciales están vacías y NO se generan automáticamente,\n'
    printf 'porque deben coincidir con las que ya tiene el volumen de cada servicio:\n'
    printf '  - %s\n' "${faltantes[@]}"
    printf 'Si las cambia, recree el volumen correspondiente.\n'
fi

chmod 600 "${ENV_FILE}"

printf '\n%d secreto(s) generado(s), %d conservado(s).\n' "${generados}" "${conservados}"
printf 'Permisos aplicados: chmod 600 %s\n' "${ENV_FILE}"
