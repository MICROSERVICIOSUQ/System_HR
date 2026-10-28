"""Impide dar por válida una ejecución donde Testcontainers omitió PostgreSQL."""
from pathlib import Path
import xml.etree.ElementTree as ET

files = list(Path('services/employees-service/target/surefire-reports').glob('*EmployeeRepositoryIntegrationTest.xml'))
if len(files) != 1:
    raise SystemExit('No se encontró el reporte de integración de PostgreSQL.')
root = ET.parse(files[0]).getroot()
if int(root.get('tests', '0')) < 7 or int(root.get('skipped', '0')):
    raise SystemExit('Las siete pruebas de integración deben ejecutarse; revise Docker y Testcontainers.')
print('Las siete pruebas de integración se ejecutaron contra PostgreSQL.')
