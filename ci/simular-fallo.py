"""Introduce los fallos del punto 5 únicamente en el checkout descartable de Jenkins."""
from pathlib import Path
import os
import sys

service, failure = sys.argv[1:]
if failure == 'ninguno':
    raise SystemExit(0)
if not os.environ.get('JENKINS_HOME') or not os.environ.get('WORKSPACE'):
    raise SystemExit('Las simulaciones solo se ejecutan en un workspace de Jenkins.')
if service not in {'employees-service', 'vacations-service'}:
    raise SystemExit('Microservicio desconocido.')
root = Path('services') / service
if failure == 'unitaria':
    if service == 'employees-service':
        path = root / 'src/test/java/com/microservicios/employees/FalloControladoTest.java'
        content = '''package com.microservicios.employees;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.fail;
class FalloControladoTest {
    @Test void falloDeliberadoDelReto7() {
        fail("Fallo controlado: Jenkins debe identificar esta prueba en Test Result.");
    }
}
'''
    else:
        path = root / 'src/tests/fallo-controlado.test.ts'
        content = '''import { test } from 'node:test';
import assert from 'node:assert/strict';
test('Fallo deliberado del reto 7', () => {
  assert.fail('Jenkins debe identificar esta prueba en Test Result.');
});
'''
    path.write_text(content)
elif failure == 'cobertura':
    # Código sin pruebas: produce cobertura baja real, sin falsificar reportes.
    if service == 'employees-service':
        path = root / 'src/main/java/com/microservicios/employees/CodigoSinPruebas.java'
        methods = ''.join(f'    public int valor{i}() {{ return {i}; }}\n' for i in range(2000))
        content = 'package com.microservicios.employees;\npublic class CodigoSinPruebas {\n' + methods + '}\n'
    else:
        path = root / 'src/services/codigo-sin-pruebas.ts'
        content = ''.join(f'export function valor{i}(): number {{ return {i}; }}\n' for i in range(2000))
    path.write_text(content)
elif failure == 'docker':
    with (root / 'Dockerfile').open('a') as file:
        file.write('\nINSTRUCCION_INEXISTENTE fallo-controlado-del-reto7\n')
elif failure == 'bdd':
    path = Path('e2e-tests/src/test/resources/features/99-fallo-controlado.feature')
    path.write_text('''# language: es
Característica: Demostración de un escenario fallido en Jenkins
  Escenario: El pipeline identifica un fallo BDD
    Cuando consulto el estado del sistema
    Entonces la respuesta debe tener código 599
''')
else:
    raise SystemExit('Tipo de fallo desconocido.')
print(f'Simulación preparada: {failure}. El siguiente build parte de un checkout limpio.')
