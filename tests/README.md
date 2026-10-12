# Pruebas del sistema

## Prueba integral del Reto 3

Requisitos: Docker Compose con el sistema levantado, Python 3 y el circuito en CLOSED. Ejecutar desde la raíz del repositorio:

```bash
docker compose up --build -d
docker compose ps
python3 tests/reto3_smoke.py
```

Para utilizar otra URL pública:

```bash
python3 tests/reto3_smoke.py --base-url http://localhost:8080
```

El script usa el proyecto de Compose de la raíz. Si se utilizan overrides o un nombre de proyecto diferente, deben proporcionarse mediante `COMPOSE_FILE` y `COMPOSE_PROJECT_NAME`. La opción `--base-url` solo cambia la URL HTTP.

## Qué verifica

1. Los servicios están ejecutándose y solo el Gateway publica puertos.
2. El Gateway responde a `/health`.
3. Es posible crear un departamento y un empleado con datos válidos.
4. Al detener departamentos, el Gateway devuelve 503 con JSON.
5. El listado de empleados continúa funcionando.
6. Tras tres registros fallidos se abre el circuito y las solicitudes posteriores responden más rápido.
7. Al restaurar departamentos y esperar el periodo configurado se observa HALF_OPEN.
8. Un registro con departamento inexistente obtiene 400 por ese motivo y el circuito vuelve a CLOSED.

Cada petición imprime su código, duración y respuesta en formato JSON. El script restaura departamentos en el bloque de limpieza aunque falle una comprobación durante la caída. No elimina datos ni volúmenes y utiliza IDs nuevos para los registros de prueba.

## Evidencias para entregar

```bash
python3 tests/reto3_smoke.py > reto3-evidencia.jsonl
```

Capturar o grabar también:

- Una conexión directa rechazada y el recurso accesible a través del Gateway.
- Las duraciones antes y después de OPEN.
- El estado HALF_OPEN, la respuesta 400 por departamento inexistente y el regreso a CLOSED.

Las conexiones directas pueden comprobarse con:

```bash
curl -i --max-time 5 http://localhost:8081/departamentos
curl -i --max-time 5 http://localhost:8082/empleados
```

Estas comprobaciones presuponen que ningún otro programa utiliza esos puertos. La inspección de los contenedores realizada por el script confirma además que los servicios no publican puertos.

## Pruebas automatizadas por servicio

Con Maven y la versión de Java correspondiente, desde la raíz:

```bash
mvn -f api-gateway/pom.xml test
mvn -f services/employees-service/pom.xml test
```

Gateway requiere Java 17 o superior; empleados requiere Java 21. Se verificaron 6 pruebas de Gateway y 23 de empleados, todas aprobadas.

## Datos y tiempos

Los ejemplos simplificados del enunciado deben completarse con los campos obligatorios del modelo. Un 400 por campos faltantes o duplicados no demuestra que se haya consultado departamentos.

Los tiempos exactos dependen de la red y de los timeouts. En la ejecución comprobada, los primeros tres registros fallidos tardaron aproximadamente 19, 18 y 12 segundos; los siguientes, con OPEN, unos 10 ms.

La [evidencia de ejecución verificada](../docs/evidencias/reto3-evidencia.jsonl) contiene respuestas, tiempos, estados y resultados de pruebas. Las capturas o el video exigidos para la entrega todavía deben adjuntarse.

## Caso alternativo — Caché de contingencia

Se ha extendido la prueba para incluir el **Caché de contingencia**.

Al detener `departments-service`, el `employees-service` devolverá éxito en el registro (201) de los empleados al utilizar la información cacheada temporalmente, en lugar de un `503`. La prueba `tests/reto3_smoke.py` demuestra que, aunque el circuito se abre (OPEN), el sistema permite registros temporales apoyándose en este fallback. El comportamiento por defecto del 503 sigue ocurriendo si se consulta un departamento que nunca ha sido cacheado o expiró.
