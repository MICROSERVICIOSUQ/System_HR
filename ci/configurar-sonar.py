"""Prepara proyectos, cobertura y webhook antes de que arranque Jenkins."""
import base64
import json
import os
from pathlib import Path
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

URL = os.environ.get('SONAR_URL', 'http://sonarqube:9000')
PASSWORD = os.environ['SONAR_ADMIN_PASSWORD']

def api(endpoint, data=None, password=PASSWORD, token=None):
    auth = f'{token}:' if token else f'admin:{password}'
    headers = {'Authorization': 'Basic ' + base64.b64encode(auth.encode()).decode()}
    body = urlencode(data).encode() if data is not None else None
    with urlopen(Request(URL + '/api/' + endpoint, data=body, headers=headers), timeout=20) as response:
        content = response.read()
        return json.loads(content) if content else {}

for attempt in range(120):
    try:
        if api('system/status').get('status') == 'UP':
            break
    except (HTTPError, URLError, TimeoutError):
        pass
    time.sleep(5)
else:
    raise SystemExit('SonarQube no estuvo disponible en diez minutos.')

if not api('authentication/validate').get('valid'):
    if not api('authentication/validate', password='admin').get('valid'):
        raise SystemExit('La contraseña de SonarQube no coincide con SONAR_ADMIN_PASSWORD.')
    api('users/change_password', {'login': 'admin', 'previousPassword': 'admin', 'password': PASSWORD}, password='admin')

projects = ['employees-service', 'vacations-service']
for key in projects:
    found = api('projects/search?' + urlencode({'projects': key}))['components']
    if not found:
        api('projects/create', {'project': key, 'name': key})

gates = api('qualitygates/list')['qualitygates']
gate = next((item for item in gates if item['name'] == 'System HR - 70%'), None)
if gate is None:
    gate = api('qualitygates/create', {'name': 'System HR - 70%'})
conditions = api('qualitygates/show?' + urlencode({'name': gate['name']}))['conditions']
for condition in conditions:
    api('qualitygates/delete_condition', {'id': condition['id']})
api('qualitygates/create_condition', {'gateName': gate['name'], 'metric': 'coverage', 'op': 'LT', 'error': '70'})
for key in projects:
    api('qualitygates/select', {'projectKey': key, 'gateName': gate['name']})

hooks = api('webhooks/list')['webhooks']
if not any(hook['url'] == 'http://jenkins:8080/sonarqube-webhook/' for hook in hooks):
    api('webhooks/create', {'name': 'Jenkins', 'url': 'http://jenkins:8080/sonarqube-webhook/'})

secret = Path('/run/sonar/SONAR_TOKEN')
valid = secret.exists() and api('authentication/validate', token=secret.read_text().strip()).get('valid')
if not valid:
    api('user_tokens/revoke', {'name': 'system-hr-jenkins'})
    token = api('user_tokens/generate', {'name': 'system-hr-jenkins', 'type': 'GLOBAL_ANALYSIS_TOKEN'})['token']
    secret.write_text(token)
    secret.chmod(0o400)
    os.chown(secret, 1000, 1000)
print('SonarQube preparado: dos proyectos, cobertura mínima del 70% y webhook de Jenkins.')
