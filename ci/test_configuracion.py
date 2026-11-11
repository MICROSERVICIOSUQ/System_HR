"""Comprueba las barreras que separan las pruebas de los datos del entorno local."""
import json
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
            (root / '.env').write_text(
                'JWT_SECRET=clave-que-ya-existia\n'
                'JENKINS_ADMIN_PASSWORD=conservar\n'
                '# comentario con caracteres UTF-8: integración — Windows\n',
                encoding='utf-8',
            )
            command = [sys.executable, str(root / 'ci/preparar-entorno.py')]
            subprocess.run(command, check=True, capture_output=True)
            first = (root / '.env').read_text(encoding='utf-8')
            subprocess.run(command, check=True, capture_output=True)
            self.assertEqual(first, (root / '.env').read_text(encoding='utf-8'))
            self.assertIn('JWT_SECRET=clave-que-ya-existia\n', first)
            self.assertIn('JENKINS_ADMIN_PASSWORD=conservar\n', first)
            self.assertIn('# comentario con caracteres UTF-8: integración — Windows\n', first)
            self.assertIn('SONAR_ADMIN_PASSWORD=', first)
            if sys.platform != 'win32':
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


if __name__ == '__main__':
    unittest.main()
