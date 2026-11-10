"""Verifica que una demostración fallida restaure Departamentos sin usar Docker real."""
from contextlib import redirect_stdout
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('postman_runner', Path(__file__).with_name('probar-postman-pendientes.py'))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class RestauracionDeDepartamentos(unittest.TestCase):
    def execute(self, fail_phase=None, fail_stop=False):
        calls = []
        config = {'networks': {'microservices-network': {'name': 'test-network'}}, 'services': {
            'api-gateway': {'networks': {'microservices-network': None}},
            'auth-service': {'environment': {'AUTH_SEED_ADMIN_EMAIL': 'admin@example.test',
                                             'AUTH_SEED_ADMIN_PASSWORD': 'clave-que-no-debe-salir'}},
            'grafana': {'networks': {'microservices-network': None}, 'environment': {'GF_SECURITY_ADMIN_USER': 'admin', 'GF_SECURITY_ADMIN_PASSWORD': 'otra-clave'}}}}

        def fake_run(args, **kwargs):
            calls.append(args)
            code, output = 0, ''
            if args[1:4] == ['compose', 'config', '--format']:
                output = json.dumps(config)
            elif args[1:4] == ['compose', 'ps', '-q']:
                output = 'un-contenedor\n'
            elif args[1] == 'inspect':
                output = '{"test-network":{}}'
            elif args[1] == 'exec':
                data = json.loads(kwargs['input'])
                code = 1 if fail_phase is not None and data.get('folder') == fail_phase else 0
                output = 'RESULTADO_POSTMAN=' + json.dumps({'error': False, 'failures': code})
            elif args[1:3] == ['compose', 'stop'] and fail_stop:
                code = 1
            return subprocess.CompletedProcess(args, code, output, '')

        output = io.StringIO()
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(runner, 'ROOT', Path(directory)), patch.object(runner.shutil, 'which', return_value='docker'), \
                    patch.object(runner.subprocess, 'run', side_effect=fake_run), \
                    patch('sys.argv', ['probar-postman-pendientes.py', '--sin-arranque', '--caida-departamentos']), \
                    redirect_stdout(output):
                error = None
                try:
                    result = runner.main()
                except RuntimeError as caught:
                    result, error = None, caught
            report = (Path(directory) / 'reports/pendientes-postman/resumen.json').read_text(encoding='utf-8')
            self.assertNotIn('clave-que-no-debe-salir', report + output.getvalue())
            return result, error, calls, json.loads(report)

    def test_restaura_y_limpia_el_runner_si_la_prueba_de_caida_falla(self):
        _, error, calls, report = self.execute(fail_phase='02 Caida controlada')
        self.assertIsNotNone(error)
        self.assertIn(['docker', 'compose', 'stop', 'departments-service'], calls)
        self.assertTrue(any(call[1:4] == ['compose', 'up', '-d'] for call in calls))
        self.assertTrue(any(call[1:3] == ['rm', '-f'] for call in calls))
        self.assertTrue(report['departamentos_restaurado'])

    def test_tambien_restaura_si_el_comando_stop_informa_error(self):
        _, error, calls, report = self.execute(fail_stop=True)
        self.assertIsNotNone(error)
        self.assertTrue(any(call[1:4] == ['compose', 'up', '-d'] for call in calls))
        self.assertTrue(report['departamentos_restaurado'])

    def test_si_el_ensayo_normal_falla_no_apaga_departamentos(self):
        _, error, calls, report = self.execute(fail_phase='01 Funcionamiento')
        self.assertIsNotNone(error)
        self.assertFalse(any(call[1:3] == ['compose', 'stop'] for call in calls))
        self.assertIsNone(report['departamentos_restaurado'])

    def test_ensayo_completo_pasa_por_caida_y_recuperacion(self):
        result, error, _, report = self.execute()
        self.assertEqual(result, 0)
        self.assertIsNone(error)
        self.assertEqual([row['carpeta'] for row in report['ejecuciones']][-3:],
                         ['01 Funcionamiento', '02 Caida controlada', '03 Recuperacion'])
        self.assertTrue(report['departamentos_restaurado'])


if __name__ == '__main__':
    unittest.main()
