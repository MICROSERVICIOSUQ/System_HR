"""Comprueba las barreras que separan las pruebas de los datos del entorno local."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ConfiguracionCI(unittest.TestCase):
    def test_e2e_no_comparte_redes_volumenes_ni_carpetas_del_host(self):
        original = {
            'name': 'sistema-local',
            'services': {
                'employees-service': {
                    'build': {'context': '/workspace/services/employees-service'},
                    'ports': [{'target': 8080, 'published': '8080'}],
                    'networks': {'microservices-network': None},
                    'volumes': [{'type': 'volume', 'source': 'empleados_data', 'target': '/data'}],
                },
                'rabbitmq': {'image': 'rabbitmq:3-management', 'ports': [{'target': 5672}],
                            'volumes': [{'type': 'bind', 'source': '/host/definitions.json', 'target': '/etc/rabbitmq/definitions.json'}]},
                'bdd-tests': {'volumes': [{'type': 'bind', 'source': '/host/reports', 'target': '/app/reports'}]},
                'jenkins': {}, 'sonarqube': {}, 'sonar-db': {}, 'sonar-setup': {},
            },
            'networks': {'microservices-network': {'name': 'sistema_rrhh_network'}},
            'volumes': {'empleados_data': {'name': 'gestion-empleados_postgres_data'},
                        'jenkins_data': {'name': 'jenkins_data'}},
        }
        result = subprocess.run(
            [sys.executable, str(ROOT / 'ci/crear-compose-e2e.py'), 'hr-ci-demo-rabbitmq',
             'employees-service', 'system-hr/employees-service:42'],
            input=json.dumps(original), text=True, capture_output=True, check=True,
        )
        config = json.loads(result.stdout)
        self.assertNotIn('name', config)
        self.assertEqual(config['networks'], {'microservices-network': {}})
        self.assertEqual(config['volumes'], {'empleados_data': {}})
        self.assertEqual(set(config['services']), {'employees-service', 'rabbitmq', 'bdd-tests'})
        for service in config['services'].values():
            self.assertFalse(service.get('ports'))
            self.assertFalse(any(volume['type'] == 'bind' for volume in service['volumes']))
        employee = config['services']['employees-service']
        self.assertEqual(employee['image'], 'system-hr/employees-service:42')
        self.assertNotIn('build', employee, 'E2E debe usar la imagen evaluada en Package')
        self.assertEqual(config['services']['rabbitmq']['image'], 'hr-ci-demo-rabbitmq')

    def test_no_despliega_e2e_si_falta_la_suite_bdd(self):
        result = subprocess.run(
            [sys.executable, str(ROOT / 'ci/crear-compose-e2e.py'), 'rabbitmq',
             'employees-service', 'system-hr/employees-service:1'],
            input=json.dumps({'services': {}}), text=True, capture_output=True,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('perfil testing', result.stderr)

    def test_preparar_entorno_conserva_los_secretos_y_es_idempotente(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'ci').mkdir()
            shutil.copy(ROOT / 'ci/preparar-entorno.py', root / 'ci')
            (root / '.env').write_text('JWT_SECRET=clave-que-ya-existia\nJENKINS_ADMIN_PASSWORD=conservar\n')
            command = [sys.executable, str(root / 'ci/preparar-entorno.py')]
            subprocess.run(command, check=True, capture_output=True)
            first = (root / '.env').read_text()
            subprocess.run(command, check=True, capture_output=True)
            self.assertEqual(first, (root / '.env').read_text())
            self.assertIn('JWT_SECRET=clave-que-ya-existia\n', first)
            self.assertIn('JENKINS_ADMIN_PASSWORD=conservar\n', first)
            self.assertIn('SONAR_ADMIN_PASSWORD=', first)
            if os.name != 'nt':
                # os.chmod en Windows solo conmuta el bit de solo lectura: los
                # permisos POSIX no existen, y la comprobación daba 0o666 en
                # cualquier equipo Windows aunque el script fuera correcto.
                # Donde importa de verdad —el contenedor del pipeline, que es
                # Linux— sí se comprueba.
                self.assertEqual((root / '.env').stat().st_mode & 0o777, 0o600)

    def test_no_se_acepta_una_integracion_omitida_por_falta_de_docker(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            reports = root / 'services/employees-service/target/surefire-reports'
            reports.mkdir(parents=True)
            report = reports / 'TEST-com.microservicios.employees.EmployeeRepositoryIntegrationTest.xml'
            command = [sys.executable, str(ROOT / 'ci/comprobar-integracion.py')]
            for skipped, expected in [(7, 1), (0, 0)]:
                report.write_text(f'<testsuite tests="7" skipped="{skipped}"/>')
                result = subprocess.run(command, cwd=root, capture_output=True)
                self.assertEqual(result.returncode, expected)

    def test_la_limpieza_rechaza_un_proyecto_ajeno_a_ci(self):
        import os
        env = dict(os.environ, CI_PROJECT='sistema-local', CI_SERVICE='employees-service', BUILD_NUMBER='1')
        result = subprocess.run(['sh', str(ROOT / 'ci/e2e.sh'), 'clean'], env=env, capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b'solo admite proyectos hr-ci-', result.stderr)


class SecretosDelEntorno(unittest.TestCase):
    """La regla que protege los volúmenes ya creados.

    scripts/setup-secrets.sh reescribía con un valor nuevo todas las líneas
    *_DB_PASSWORD y RABBITMQ_PASSWORD. PostgreSQL y RabbitMQ fijan sus
    credenciales al inicializar su volumen, así que sobre un entorno que ya se
    había levantado eso dejaba a los siete servicios sin poder conectarse, y
    recuperarlo exigía borrar los volúmenes con sus datos. Esta prueba fija el
    comportamiento correcto para que no vuelva.
    """

    @unittest.skipUnless(shutil.which('bash'), 'requiere bash')
    @unittest.skipUnless(shutil.which('openssl'), 'requiere openssl')
    def test_setup_secrets_rellena_huecos_sin_tocar_lo_existente(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'scripts').mkdir()
            shutil.copy(ROOT / 'scripts/setup-secrets.sh', root / 'scripts')
            (root / '.env').write_text(
                '\n'.join([
                    'EMPLEADOS_DB_PASSWORD=postgres',
                    'RABBITMQ_PASSWORD=admin',
                    'JWT_SECRET=clave-que-ya-existia',
                    'JENKINS_ADMIN_PASSWORD=',
                    '',
                ]),
                encoding='utf-8',
            )

            # La ruta absoluta de bash y no el nombre: en Windows, invocar
            # 'bash' hace que se resuelva por el PATH del sistema y termina
            # ejecutando el relay de WSL, que no ve las rutas C:\... y falla con
            # "execvpe(/bin/bash) failed".
            command = [shutil.which('bash'), str(root / 'scripts/setup-secrets.sh')]
            subprocess.run(command, check=True, capture_output=True)
            primera = (root / '.env').read_text(encoding='utf-8')

            # Lo que ya tenía valor se conserva, literal.
            self.assertIn('EMPLEADOS_DB_PASSWORD=postgres\n', primera)
            self.assertIn('RABBITMQ_PASSWORD=admin\n', primera)
            self.assertIn('JWT_SECRET=clave-que-ya-existia\n', primera)

            # Lo que estaba vacío se rellena, y lo que faltaba se añade.
            self.assertNotIn('JENKINS_ADMIN_PASSWORD=\n', primera)
            self.assertIn('SONAR_ADMIN_PASSWORD=', primera)
            self.assertIn('SONAR_DB_PASSWORD=', primera)

            # Y repetirlo no cambia nada: es seguro ejecutarlo dos veces.
            subprocess.run(command, check=True, capture_output=True)
            self.assertEqual(primera, (root / '.env').read_text(encoding='utf-8'))


if __name__ == '__main__':
    unittest.main()
