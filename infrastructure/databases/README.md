# Bases de datos

Este directorio contiene la configuracion de ejecucion compartida de las bases de datos del proyecto.

## Regla de propiedad

Cada microservicio es dueño de su propia base de datos. El esquema, las migraciones y los modelos de persistencia permanecen dentro del servicio que posee la informacion.

Por ejemplo:

```text
services/departments-service/prisma/schema.prisma
services/employees-service/src/main/java/com/microservicios/employees/model/Employee.java
services/employees-service/src/main/resources/db/migration/V1__create_employees.sql
infrastructure/databases/docker-compose.yml
```

El Compose de este directorio define los contenedores `database-departamentos` y `database-empleados`. El Compose raiz lo incluye y continua siendo el punto de entrada para levantar todo el entorno.

Los datos no se guardan dentro del repositorio. PostgreSQL utiliza los volumenes Docker `gestion-departamentos_postgres_data` y `gestion-empleados_postgres_data`, lo que permite conservar la informacion al recrear los contenedores.

No se debe ejecutar un Compose local duplicado dentro de un microservicio, porque puede crear otra red, otro volumen o una base de datos diferente.