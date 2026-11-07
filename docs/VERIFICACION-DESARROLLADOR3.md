# Verificación de la entrega del desarrollador 3

Fecha: 7 de noviembre de 2026. Rama de trabajo: valentina, actualizada con origin/main (e5a62cb) mediante la fusión local 744048e. El trabajo del desarrollador 3 se conserva en esa misma rama.

## Resultados observados después de integrar main

| Comprobación | Resultado |
|---|---|
| Construcción Docker de los seis servicios de negocio y Gateway | Aprobada |
| Pruebas unitarias de Vacaciones en Node 22/Docker | 66 aprobadas, 0 fallidas |
| Cobertura de líneas de dominio y servicios de Vacaciones en Docker | 77,75 % |
| Pruebas de Notificaciones en Go, unitarias e integración | Aprobadas |
| Cobertura de sentencias de todo el paquete Go con integración | 83,3 % |
| HTTP, PostgreSQL y RabbitMQ reales en entorno aislado | Aprobados en ambos servicios |
| Go vet y compilación local | Aprobados |
| Salud del sistema de negocio y sus dependencias | Los 15 contenedores iniciados, incluido Zipkin, running/healthy |
| Demostración a través del Gateway | Los siete pasos aprobados |
| Historial sin token/contraseña del empleado de demostración | Verificado por el script |
| Logs de Notificaciones | No se detectaron JWT en texto plano |
| Trazabilidad entre lenguajes | Verificada en Zipkin, incluyendo Notificaciones en Go |
| Sintaxis de scripts, configuración Compose y conflictos de Git | Verificadas |

Las pruebas aisladas se ejecutaron con `scripts/probar-desarrollador3.ps1`. No usan los datos del sistema principal. Su salida final indicó pruebas de ambos servicios aprobadas y limpieza de sus contenedores.

## Demostración ejecutada

El script `scripts/demo-desarrollador3.cjs` verificó:

1. Login ADMIN 200.
2. Alta y activación del empleado con un token observado en una cola temporal, conservado únicamente en memoria.
3. Creación de vacaciones y rechazo 400 de fechas iguales y solapamiento.
4. Inicio manual: EN_CURSO y login del empleado 401.
5. Fin manual: FINALIZADA y login del empleado 200.
6. Historial de notificaciones consultable sin credenciales.
7. Retiro: login 401 y nueva solicitud de vacaciones rechazada con 400.

Se conservaron el empleado `DEMO-42750fa3` y el período `6066a295-c984-4d97-bf61-48a447e5cc99` para inspección. Cada nueva ejecución genera IDs diferentes.

Se observó en Zipkin la traza `c8d6eaa6561dae61f11e89cc2e33b905`, con 23 spans de api-gateway, employees-service, auth-service, profiles-service, vacations-service y notifications-service. Es una evidencia de esta ejecución; en otro computador se generarán otras trazas. Zipkin utiliza memoria y las pierde al reiniciarse.

## Evidencias regenerables

Los resultados locales se guardan en carpetas reports, excluidas de Git: construcción y demostración en `reports/dev3`, cobertura Go en `services/notifications-service/reports`, y LCOV/JUnit en `services/vacations-service/reports`. Se pueden regenerar con las instrucciones de las guías; no contienen las credenciales de la demostración en su salida normal.

## Límites y pendientes del equipo

La cobertura de Go incluye su paquete completo. La de Vacaciones conserva el alcance de dominio/servicios del pipeline existente; no acredita el porcentaje global de todas las capas. El desarrollador 4 debe revisar ese alcance en SonarQube al completar la evaluación global.

No se ejecutaron los pipelines Jenkins/SonarQube ni la suite Cucumber completa. BDD debe dejar de leer tokens del historial y observarlos en una cola exclusiva de prueba. Las rutas canónicas de Notificaciones y el Swagger agregado requieren la actualización del Gateway indicada en la guía de Dev 4.

Los correos son simulados, sin SMTP. Vacaciones conserva la limitación previa de publicaciones sin Outbox. El catálogo normativo del profesor no estuvo disponible; los contratos se contrastaron con el PDF y los productores existentes.

La auditoría de dependencias de Vacaciones conserva dos avisos moderados de node-cron 3 y su uuid transitivo. Se aplicaron las correcciones compatibles de los avisos altos; no se forzó una migración mayor del scheduler.

El primer arranque del sistema principal notificó RabbitMQ no disponible durante su inicialización. Después quedó healthy y se repitió el arranque sin eliminar datos ni modificar su configuración. La demostración final se ejecutó con todos los servicios necesarios saludables.
