"""Prepara una copia Git para Jenkins sin hacer commits en el repositorio de trabajo."""
from pathlib import Path
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
branch = subprocess.check_output(['git', '-C', str(root), 'branch', '--show-current'], text=True).strip()
if branch != 'valentina':
    raise SystemExit('Ejecute este comando desde la rama valentina.')

container = subprocess.check_output(['docker', 'compose', 'ps', '-q', 'jenkins'], cwd=root, text=True).strip()
if not container:
    raise SystemExit('Primero ejecute: docker compose up -d --build jenkins')

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
lines = path.read_text().splitlines()
key = 'CI_REPOSITORY_URL='
value = key + 'git://jenkins:9419/source.git'
if any(line.startswith(key) for line in lines):
    lines = [value if line.startswith(key) else line for line in lines]
else:
    lines.append(value)
path.write_text('\n'.join(lines) + '\n')
path.chmod(0o600)
subprocess.run(['docker', 'compose', 'up', '-d', '--build', 'jenkins'], cwd=root, check=True)
print('Fuente local preparada en el volumen de Jenkins, rama valentina.')
print('No se hicieron commits ni push en el repositorio de trabajo. .env queda fuera de Git.')
