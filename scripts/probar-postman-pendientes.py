"""Ejecuta las cuatro colecciones; opcionalmente demuestra caída y recuperación."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import uuid

# En Windows la consola usa cp1252 y la salida de Newman trae caracteres que no
# puede codificar (las flechas y los visto bueno de su informe). Sin esto, el
# script moria con UnicodeEncodeError al imprimir la primera coleccion, antes de
# llegar a ejecutar ninguna comprobacion.
for _flujo in (sys.stdout, sys.stderr):
    try:
        _flujo.reconfigure(encoding='utf-8', errors='replace')
    except AttributeError:
        pass

ROOT = Path(__file__).resolve().parents[1]
IMAGE = 'postman/newman:6-alpine'
# El estado con tokens queda dentro de este contenedor temporal, nunca en reportes del host.
RUNNER = r"""
const fs = require('fs');
const path = require('path');
const newman = require(require.resolve('newman', {paths:['/usr/local/lib/node_modules']}));
const input = JSON.parse(fs.readFileSync(0, 'utf8'));
const state = '/tmp/' + path.basename(input.collection) + '.state.json';
const collection = JSON.parse(fs.readFileSync(fs.existsSync(state) ? state : input.collection, 'utf8'));
let runtime;
const execution = newman.run({collection, environment:input.environment, folder:input.folder,
  timeoutRequest:60000, timeoutScript:240000, reporters:['cli'], reporter:{cli:{noConsole:true}}},
  (error, summary) => {
    if (summary && runtime) {
      const updated = summary.collection.toJSON();
      // Newman conserva la definición original en summary; los valores modificados
      // por pm.collectionVariables están en el estado de ejecución.
      updated.variable = Object.entries(runtime.state.collectionVariables.toObject())
        .map(([key,value]) => ({key,value,type:'string'}));
      fs.writeFileSync(state, JSON.stringify(updated), {mode:0o600});
    }
    const result = {error:!!error, requests:summary?.run.stats.requests,
      assertions:summary?.run.stats.assertions, failures:summary?.run.failures.length ?? 1};
    console.log('RESULTADO_POSTMAN=' + JSON.stringify(result));
    process.exitCode = error || result.failures ? 1 : 0;
  });
execution.on('start', (error, args) => { runtime = args.run; });
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--caida-departamentos', action='store_true',
                        help='Parar Departamentos, verificar alerta/503 y restaurarlo en finally.')
    parser.add_argument('--sin-arranque', action='store_true',
                        help='Usar un entorno que ya está construido y encendido.')
    args = parser.parse_args()
    docker = shutil.which('docker.exe' if os.name == 'nt' else 'docker')
    if not docker:
        raise RuntimeError('Docker no está en PATH. Abre una terminal nueva tras instalar Docker Desktop.')

    def command(*arguments, capture=False, check=True):
        result = subprocess.run([docker, *arguments], cwd=ROOT, text=True, encoding='utf-8', errors='replace',
                                stdout=subprocess.PIPE if capture else None,
                                stderr=subprocess.PIPE if capture else None)
        if check and result.returncode:
            raise RuntimeError('Falló Docker. Revisa el motor, los servicios y los mensajes anteriores.')
        return result

    command('info', '--format', '{{.ServerVersion}}', capture=True)
    config = json.loads(command('compose', 'config', '--format', 'json', capture=True).stdout)
    services = ['api-gateway', 'employees-service', 'departments-service', 'auth-service',
                'vacations-service', 'notifications-service', 'profiles-service',
                'prometheus', 'loki', 'promtail', 'zipkin', 'grafana']
    if not args.sin_arranque:
        print('Preparando aplicación y observabilidad (sin Jenkins ni SonarQube)...', flush=True)
        for service in services:
            if config['services'][service].get('build'):
                command('compose', 'build', service)
        command('compose', 'up', '-d', '--no-build', '--wait', '--wait-timeout', '300', *services)
    gateway = command('compose', 'ps', '-q', 'api-gateway', capture=True).stdout.strip()
    if not gateway:
        raise RuntimeError('El Gateway no está encendido. Arranca el entorno o retira --sin-arranque.')
    networks = json.loads(command('inspect', '--format', '{{json .NetworkSettings.Networks}}', gateway, capture=True).stdout)
    shared = set(config['services']['api-gateway']['networks']) & set(config['services']['grafana']['networks'])
    network = next((config['networks'][key]['name'] for key in shared
                    if config['networks'][key].get('name') in networks), None)
    if not network:
        raise RuntimeError('Gateway y Grafana no tienen una red compartida disponible para las pruebas.')
    auth = config['services']['auth-service']['environment']
    grafana = config['services']['grafana']['environment']
    values = {
        'gatewayBaseUrl': 'http://api-gateway:8080',
        'adminUsuario': auth['AUTH_SEED_ADMIN_EMAIL'], 'adminPassword': auth['AUTH_SEED_ADMIN_PASSWORD'],
        'prometheusBaseUrl': 'http://prometheus:9090', 'zipkinBaseUrl': 'http://zipkin:9411',
        'lokiBaseUrl': 'http://loki:3100', 'grafanaBaseUrl': 'http://grafana:3000',
        'grafanaUser': grafana['GF_SECURITY_ADMIN_USER'], 'grafanaPassword': grafana['GF_SECURITY_ADMIN_PASSWORD'],
    }
    environment = {'name': 'Prueba temporal', 'values': [
        {'key': key, 'value': value, 'enabled': True} for key, value in values.items()]}
    container = 'hr-postman-pendientes-' + uuid.uuid4().hex[:10]
    results = []
    stopped = False
    created = False
    restored = None
    folder = ROOT / 'reports/pendientes-postman'
    folder.mkdir(parents=True, exist_ok=True)

    def run_collection(name, selected=None):
        print('\nColección: ' + name + (' / ' + selected if selected else ''), flush=True)
        payload = {'collection': '/workspace/postman/' + name + '.postman_collection.json',
                   'environment': environment}
        if selected:
            payload['folder'] = selected
        result = subprocess.run([docker, 'exec', '-i', container, 'node', '-e', RUNNER],
                                cwd=ROOT, input=json.dumps(payload), text=True, encoding='utf-8',
                                errors='replace', stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        # Los scripts de las colecciones no imprimen tokens, cuerpos de login ni contraseñas.
        print(result.stdout, flush=True)
        summary = next((json.loads(line.split('=', 1)[1]) for line in result.stdout.splitlines()
                        if line.startswith('RESULTADO_POSTMAN=')), {'error': True})
        results.append({'coleccion': name, 'carpeta': selected, 'salida': result.returncode, **summary})
        if result.returncode:
            raise RuntimeError('Falló ' + name + '. Consulta las aserciones mostradas antes de repetir.')

    try:
        command('run', '-d', '--name', container, '--network', network,
                '-v', str(ROOT) + ':/workspace:ro', '--entrypoint', 'tail', IMAGE, '-f', '/dev/null', capture=True)
        created = True
        for name in ['ciclo-vida-cuenta', 'reto7-sustentacion', 'notificaciones-service']:
            run_collection(name)
        run_collection('observabilidad-reto8', '01 Funcionamiento')
        if args.caida_departamentos:
            if not command('compose', 'ps', '-q', 'departments-service', capture=True).stdout.strip():
                raise RuntimeError('Departamentos debe estar encendido antes de simular su caída.')
            try:
                # Desde este momento se reintentará la restauración incluso si stop se interrumpe.
                stopped = True
                command('compose', 'stop', 'departments-service')
                run_collection('observabilidad-reto8', '02 Caida controlada')
            finally:
                print('Restaurando Departamentos...', flush=True)
                command('compose', 'up', '-d', '--wait', '--wait-timeout', '180', 'departments-service')
                stopped = False
                restored = True
            run_collection('observabilidad-reto8', '03 Recuperacion')
        else:
            print('Caída controlada no ejecutada. Para demostrarla: --caida-departamentos')
        return 0
    finally:
        try:
            if stopped:
                print('Reintentando restauración de Departamentos...', flush=True)
                result = command('compose', 'up', '-d', '--wait', '--wait-timeout', '180',
                                 'departments-service', check=False)
                restored = result.returncode == 0
                if not restored:
                    print('No se pudo restaurar Departamentos. Revisa Docker y ejecuta docker compose up -d departments-service.', file=sys.stderr)
        finally:
            if created:
                command('rm', '-f', container, capture=True, check=False)
            report = {'fecha': datetime.now(timezone.utc).isoformat(), 'caida_solicitada': args.caida_departamentos,
                      'departamentos_restaurado': restored, 'ejecuciones': results}
            (folder / 'resumen.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
            print('Resumen sin credenciales: ' + str(folder / 'resumen.json'))


if __name__ == '__main__':
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print('Prueba interrumpida. Revisa los mensajes de restauración de Departamentos.', file=sys.stderr)
        sys.exit(130)
    except (RuntimeError, OSError, ValueError) as error:
        print('ERROR: ' + str(error), file=sys.stderr)
        sys.exit(1)
