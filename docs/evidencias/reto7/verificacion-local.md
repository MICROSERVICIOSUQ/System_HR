# Comprobaciones de la implementación

Las pruebas locales de vacaciones pasaron con Node 22: 64 pruebas, sin omisiones.
La compilación TypeScript terminó correctamente. Empleados pasó sus 77 pruebas
con Java 21 y PostgreSQL real mediante Testcontainers, sin omisiones.

Los dos Jenkinsfiles también se validaron con el validador declarativo del
Jenkins instalado. La configuración Compose se verificó con `docker compose config`.
Las cinco pruebas de `ci/test_configuracion.py` pasaron: aislamiento E2E,
conservación de secretos existentes, rechazo de integración omitida, rechazo de
limpieza de proyectos ajenos a CI y rechazo de una configuración sin suite BDD.

Durante la validación se corrigieron el arranque TCP de PostgreSQL, la conexión
entre el agente Maven y Testcontainers, la configuración del analizador
TypeScript y los permisos de los archivos generados por agentes Docker.

Las comprobaciones completas de Jenkins, incluidos los fallos controlados y las
capturas, están en el [registro de evidencias](README.md).

La limpieza de permisos también se ejecutó con `HOSTNAME=contenedor-anterior`: pasó al resolver el identificador actual con `hostname`, sin depender del valor heredado de la ejecución anterior.
