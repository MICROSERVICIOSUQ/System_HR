# ADR-008: Validar el JWT en el API Gateway y no en cada microservicio

## Estado

Aceptada — 2026-10-20

## Contexto

Con la introducción del `auth-service` (Reto 5), todas las peticiones a recursos
protegidos deben llevar un Access JWT en `Authorization: Bearer <token>`, y
alguien tiene que verificar su firma antes de permitir el acceso.

Hay dos lugares posibles donde hacerlo:

1. **En el API Gateway**, que ya es el único punto de entrada publicado al host.
2. **En cada microservicio**, con una librería de seguridad por servicio.

El ecosistema tiene siete componentes (`api-gateway`, `auth-service`,
`employees-service` en Java; `departments`, `notifications`, `profiles`,
`vacations` en TypeScript). Además de autenticar hay que autorizar: RBAC por rol
y una comprobación de propiedad del recurso para que un `USER` pueda modificar su
propio perfil y solo el suyo.

## Decisión

La verificación del JWT y las reglas de autorización viven **exclusivamente en el
API Gateway**, implementadas como dos `GlobalFilter` de Spring Cloud Gateway:

| Filtro | Orden | Responsabilidad | Respuesta al fallar |
|---|---|---|---|
| `JwtAuthenticationFilter` | -100 | Verifica firma, emisor, vigencia y que no sea un token de reset | `401 Unauthorized` |
| `AuthorizationFilter` | -99 | RBAC + propiedad del recurso | `403 Forbidden` |

Tras validar, el gateway **propaga la identidad** a los servicios en dos
cabeceras: `X-Auth-User` (claim `sub`) y `X-Auth-Role` (claim `role`). Los
servicios de negocio no vuelven a verificar la firma: confían en esas cabeceras.

Dos detalles que hacen que esa confianza sea segura:

- **El gateway borra las cabeceras `X-Auth-*` que lleguen del cliente** antes de
  validar. Sin eso, cualquiera podría enviar `X-Auth-Role: ADMIN` y los servicios
  lo creerían.
- **Solo el gateway está publicado al host.** En `docker-compose.yml` los demás
  servicios usan `expose`, no `ports`: no son alcanzables desde fuera de la red
  de Docker, así que no hay forma de esquivar el borde.

La firma es simétrica (HMAC-SHA256) y el secreto se inyecta por variable de
entorno `JWT_SECRET` a los dos contenedores que lo necesitan: `auth-service` para
firmar y `api-gateway` para verificar. Ambos abortan el arranque si el secreto
tiene menos de 32 bytes.

## Alternativas consideradas

**Interceptor o middleware en cada microservicio.**
Descartada. Obligaría a resolver la validación de JWT **dos veces** con librerías
distintas (jjwt en Java, jsonwebtoken o jose en Node) y a mantener las dos
sincronizadas: el mismo criterio de expiración, el mismo manejo de `type`, la
misma distinción entre 401 y 403. Cada servicio nuevo del curso añadiría una
tercera, cuarta o quinta copia. Y cuando la validación pase a JWKS contra un
Identity Provider externo, ese cambio habría que hacerlo en cada servicio en vez
de en un solo sitio. Es exactamente el problema que el gateway del Reto 3 se
anticipó a resolver.

**Spring Security completo en cada servicio Java.**
Descartada por el mismo motivo, y porque añade una cadena de filtros y una curva
de configuración que no aporta nada cuando el borde ya decidió. En `auth-service`
solo se usa `spring-security-crypto`, el módulo que aporta `BCryptPasswordEncoder`
sin activar seguridad web.

**Validación en el gateway y además en cada servicio (defensa en profundidad).**
Es lo correcto en producción, pero duplica el trabajo sin beneficio real mientras
los servicios no estén publicados al host. Queda anotado como posible evolución.

## Consecuencias

**Positivas**
- La lógica de autenticación y autorización está escrita una sola vez, en un solo
  lenguaje, y se prueba en un solo lugar.
- Los microservicios de negocio quedan libres de dependencias de seguridad: su
  código no cambió al añadir JWT al sistema.
- El cambio futuro a JWKS o a firma asimétrica se hace en un único componente.
- Los 401 y 403 del borde usan el mismo cuerpo JSON que los errores de los
  servicios, así que el cliente no distingue de dónde vino el rechazo.

**Negativas / asumidas**
- **Los servicios confían en las cabeceras del gateway.** Si alguien publicara un
  puerto de servicio al host, o entrara en la red de Docker, podría suplantar
  identidades. La mitigación es operativa (no publicar puertos) y está documentada
  aquí para que no se pierda.
- El gateway se vuelve un componente crítico: si está mal configurado, afecta a
  todo el sistema.
- La autorización por propiedad del recurso en el borde obliga a conocer la forma
  de las rutas (`PUT /perfiles/{empleadoId}`). Es acoplamiento del gateway al
  contrato de los servicios; aceptable mientras la regla se exprese sobre la ruta
  y no sobre el cuerpo de la petición.
