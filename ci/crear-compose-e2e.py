"""Aísla el Compose del proyecto y elimina los recursos persistentes del entorno local."""
import json
import sys

config = json.load(sys.stdin)
services = config['services']
if 'bdd-tests' not in services:
    raise SystemExit('Falta bdd-tests en Compose. Active el perfil testing al generar la configuración.')
for name in ['jenkins', 'sonarqube', 'sonar-db', 'sonar-setup']:
    services.pop(name, None)
for service in services.values():
    service.pop('ports', None)
    service.pop('container_name', None)
    service['restart'] = 'no'
    # Docker recibe el contexto por el cliente; los bind mounts apuntarían al host.
    service['volumes'] = [v for v in service.get('volumes', []) if v['type'] != 'bind']
    service.pop('profiles', None)
services['rabbitmq']['image'] = sys.argv[1]
# Este servicio ya se empaquetó; no se reconstruye durante E2E.
services[sys.argv[2]]['image'] = sys.argv[3]
services[sys.argv[2]].pop('build', None)
config['networks'] = {'microservices-network': {}}
config['volumes'] = {
    name: {} for name in config.get('volumes', {})
    if any(v.get('type') == 'volume' and v.get('source') == name
           for service in services.values() for v in service.get('volumes', []))
}
config.pop('name', None)
json.dump(config, sys.stdout, indent=2)
