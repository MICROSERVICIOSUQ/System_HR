"""Prepara una copia Git para Jenkins sin hacer commits en el repositorio de trabajo."""
from pathlib import Path
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
# La rama que se publique es la que esté activa, sea cual sea. Antes el script
# exigía 'valentina' y abortaba en cualquier otra, así que tras integrar el
# trabajo en main dejó de poder ejecutarse: justamente cuando hacía falta.
branch = subprocess.check_output(['git', '-C', str(root), 'branch', '--show-current'], text=True).strip()
if not branch:
    raise SystemExit('No se pudo determinar la rama actual (¿HEAD desacoplado?).')

# --profile ci: los servicios de CI llevan ese perfil y sin él 'compose ps' no
# los encuentra.
container = subprocess.check_output(
    ['docker', 'compose', '--profile', 'ci', 'ps', '-q', 'jenkins'], cwd=root, text=True
).strip()
if not container:
    raise SystemExit('Primero ejecute: docker compose --profile ci up -d --build jenkins')

files = subprocess.check_output(
    ['git', '-C', str(root), 'ls-files', '--cached', '--others', '--exclude-standard', '-z']
).decode().strip('\0').split('\0')
with tempfile.TemporaryDirectory(prefix='system-hr-ci-') as directory:
    snapshot = Path(directory) / 'source'
    snapshot.mkdir()
    for name in files:
        if name == '.env' or name.startswith('.env.') and name != '.env.example':
            continue
        source = root / name
        if source.is_file():
            target = snapshot / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
    subprocess.run(['git', 'init', '-q', '-b', branch, str(snapshot)], check=True)
    subprocess.run(['git', '-C', str(snapshot), 'add', '.'], check=True)
    subprocess.run(['git', '-C', str(snapshot), '-c', 'user.name=CI local',
                    '-c', 'user.email=ci-local@example.invalid', 'commit', '-qm',
                    'Copia local para validar el reto 7'], check=True)
    bare = Path(directory) / 'source.git'
    subprocess.run(['git', 'clone', '-q', '--bare', str(snapshot), str(bare)], check=True)
    destination = '/var/jenkins_home/ci-source/source.git'
    subprocess.run(['docker', 'exec', '-u', 'jenkins', container, 'mkdir', '-p', destination], check=True)
    subprocess.run(['docker', 'cp', str(bare) + '/.', container + ':' + destination], check=True)

path = root / '.env'
# encoding='utf-8' explícito: en Windows read_text usa cp1252 por omisión y el
# .env tiene comentarios acentuados, así que el script moría con
# UnicodeDecodeError, y al escribir habría cambiado esos caracteres.
lines = path.read_text(encoding='utf-8').splitlines()
# Se apuntan la URL del demonio git local y la rama publicada. La rama importa:
# los trabajos hacen checkout de ${CI_REPOSITORY_BRANCH} y, si no coincide con
# la que se acaba de crear en la copia, el build no encuentra nada.
for key, valor in (
    ('CI_REPOSITORY_URL=', 'git://jenkins:9419/source.git'),
    ('CI_REPOSITORY_BRANCH=', branch),
):
    linea = key + valor
    if any(line.startswith(key) for line in lines):
        lines = [linea if line.startswith(key) else line for line in lines]
    else:
        lines.append(linea)
path.write_text('\n'.join(lines) + '\n', encoding='utf-8')
path.chmod(0o600)
# Recrear el contenedor para que tome las variables nuevas y para que el
# entrypoint arranque el demonio git sobre la copia que se acaba de dejar.
subprocess.run(
    ['docker', 'compose', '--profile', 'ci', 'up', '-d', '--build', '--force-recreate', 'jenkins'],
    cwd=root, check=True,
)
print(f'Fuente local preparada en el volumen de Jenkins, rama {branch}.')
print('No se hicieron commits ni push en el repositorio de trabajo. .env queda fuera de Git.')
