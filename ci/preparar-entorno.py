"""Completa .env sin sobrescribir la configuración que ya tenga el proyecto."""
from pathlib import Path
import secrets

root = Path(__file__).resolve().parents[1]
path = root / '.env'
text = path.read_text(encoding='utf-8') if path.exists() else (root / '.env.example').read_text(encoding='utf-8')
keys = {line.split('=', 1)[0].strip() for line in text.splitlines() if '=' in line}
values = {
    'JENKINS_ADMIN_USER': 'admin',
    'JENKINS_ADMIN_PASSWORD': secrets.token_urlsafe(24),
    'SONAR_ADMIN_PASSWORD': secrets.token_urlsafe(24),
    'SONAR_DB_PASSWORD': secrets.token_urlsafe(24),
    'JENKINS_URL': 'http://localhost:9091/',
    'JENKINS_RESOURCE_URL': 'http://127.0.0.1:9091/',
    'CI_NETWORK': 'system_hr_ci',
    'CI_REPOSITORY_URL': 'https://github.com/MICROSERVICIOSUQ/System_HR.git',
    'CI_REPOSITORY_BRANCH': 'valentina',
    'CI_GIT_USERNAME': '',
    'CI_GIT_TOKEN': '',
}
missing = {key: value for key, value in values.items() if key not in keys}
if missing:
    with path.open('a' if path.exists() else 'w', encoding='utf-8') as file:
        if not path.exists() or path.stat().st_size == 0:
            file.write(text)
        file.write('\n# Integración continua — configuración local\n')
        for key, value in missing.items():
            file.write(f'{key}={value}\n')
path.chmod(0o600)
print('Entorno preparado. Las contraseñas están en .env; no se incluyen en Git.')
