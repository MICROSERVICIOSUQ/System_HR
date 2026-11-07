$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskPreviousSecret = $env:TEST_SECRET
$env:TEST_SECRET = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
Push-Location $taskRoot
try {
    docker version --format '{{.Server.Version}}'
    if ($LASTEXITCODE -ne 0) { throw 'Abra Docker Desktop y espere a que arranque el motor.' }
    docker compose -f compose.dev3-tests.yml up -d --wait postgres-notifications postgres-vacations rabbitmq
    if ($LASTEXITCODE -ne 0) { throw 'No arrancaron las dependencias de prueba.' }
    docker compose -f compose.dev3-tests.yml run --build --rm notifications-tests
    if ($LASTEXITCODE -ne 0) { throw 'Fallaron las pruebas de Notificaciones.' }
    docker compose -f compose.dev3-tests.yml run --build --rm vacations-tests
    if ($LASTEXITCODE -ne 0) { throw 'Fallaron las pruebas de Vacaciones.' }
    Write-Host 'Pruebas de los dos servicios aprobadas. Consulte sus carpetas reports.'
}
finally {
    # Solo retira los contenedores efímeros de este archivo, sin tocar el sistema principal.
    docker compose -f compose.dev3-tests.yml down
    $env:TEST_SECRET = $taskPreviousSecret
    Pop-Location
}
