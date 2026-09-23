# API Gateway

Punto de entrada público del sistema. Implementado con Java 17, Spring Boot y Spring Cloud Gateway. Se construye como un servicio independiente dentro de Docker Compose.

## Responsabilidades

Enrutar a empleados y departamentos, propagar solicitudes y respuestas, exponer su propia salud y responder con JSON cuando un destino no está disponible. Las reglas de negocio permanecen en los servicios.

## Rutas

| Ruta pública | Destino |
|---|---|
| `/health` | Respuesta propia del Gateway |
| `/empleados` y `/empleados/**` | `EMPLEADOS_URL` |
| `/departamentos` y `/departamentos/**` | `DEPARTAMENTOS_URL` |

URL base: `http://localhost:8080`. Las rutas se reenvían sin eliminar sus prefijos.

## Configuración

Compose configura `EMPLEADOS_URL` y `DEPARTAMENTOS_URL` con los nombres de los servicios y sus puertos internos. No deben apuntar al puerto del propio Gateway.

El timeout de conexión es de 3 segundos y el timeout de respuesta es de 45 segundos. Este último deja margen para los intentos y esperas del cliente de departamentos en empleados.

## Salud y errores

```bash
curl -i http://localhost:8080/health
```

Respuesta propia:

```json
{"status":"ok","service":"api-gateway"}
```

Si falla la conexión o vence el timeout, devuelve `503` con `timestamp`, `status`, `error`, `message` y `path`. El mensaje es `El servicio de destino no está disponible temporalmente`.

Los errores HTTP recibidos del servicio, como un `400` de validación, se propagan con su código y cuerpo originales.

## Ejecución

Desde la raíz del repositorio:

```bash
docker compose up --build -d
```

El Dockerfile compila con Maven y ejecuta el JAR en una imagen Java. No necesita Maven ni Java instalados en el host para esta modalidad.

## Pruebas del Gateway

Con Maven y Java 17 o superior, desde esta carpeta:

```bash
mvn test
```

Las pruebas cubren salud propia, rutas, parámetros de consulta, propagación de cuerpo y cabeceras, errores de negocio, destino inaccesible y timeout.

## Alcance actual

No incluye autenticación, autorización ni agregación de respuestas. Las interfaces Swagger internas de los servicios no están enrutadas públicamente en esta entrega.
