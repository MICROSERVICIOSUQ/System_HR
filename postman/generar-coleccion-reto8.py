"""Regenera únicamente la colección de observabilidad corregida."""
from pathlib import Path
import runpy

if __name__ == '__main__':
    helpers = runpy.run_path(str(Path(__file__).with_name('generar-colecciones-pendientes.py')))
    helpers['write']('observabilidad-reto8', helpers['observabilidad']())
    print('Generada: observabilidad-reto8')
