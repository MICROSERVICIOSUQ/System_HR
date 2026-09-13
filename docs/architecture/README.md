# Arquitectura

Este directorio contiene los diagramas y descripciones de la arquitectura del sistema.

Reglas iniciales:

- Cada microservicio es responsable de su propio dominio.
- Cada microservicio es dueño de su propia base de datos.
- La comunicacion entre servicios se realiza mediante contratos HTTP definidos.
- No se comparten tablas ni modelos de persistencia entre servicios.