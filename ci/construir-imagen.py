"""Construye una imagen y deja el diagnóstico visible como reporte en Jenkins."""
from html import escape
from pathlib import Path
import subprocess
import sys

service, number = sys.argv[1:]
folder = Path('reports/docker')
folder.mkdir(parents=True, exist_ok=True)
command = ['docker', 'build', '-t', f'system-hr/{service}:{number}', '-t', f'system-hr/{service}:latest', f'services/{service}']
result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
print(result.stdout, end='')
(folder / 'build.log').write_text(result.stdout)
status = 'CORRECTO' if result.returncode == 0 else 'ERROR'
(folder / 'index.html').write_text(f'''<!doctype html>
<html lang="es"><meta charset="utf-8"><title>Empaquetado Docker</title>
<style>body{{font-family:system-ui;margin:2rem}}pre{{white-space:pre-wrap;background:#f4f4f4;padding:1rem}}</style>
<h1>Empaquetado de {escape(service)}</h1>
<p>Build {escape(number)} · Estado: <strong>{status}</strong></p>
<p>Comando: <code>{escape(' '.join(command))}</code></p>
<pre>{escape(result.stdout)}</pre></html>''')
sys.exit(result.returncode)
