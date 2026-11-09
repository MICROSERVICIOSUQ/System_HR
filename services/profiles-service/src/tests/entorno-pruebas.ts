/**
 * Preparación del entorno para las pruebas unitarias.
 *
 * Se carga con `--import`, antes de que se evalúe cualquier módulo de la
 * aplicación.
 *
 * ¿Por qué hace falta? `src/config/db.ts` lanza una excepción de inmediato si no
 * hay `DATABASE_URL`. Eso es deliberado en producción —es mejor que el
 * contenedor no arranque que atender peticiones sin base de datos— pero la
 * cadena de imports de `profile.service.ts` llega hasta ese módulo, así que sin
 * la variable las pruebas ni se cargan. Antes cada archivo de prueba la ponía
 * por su cuenta, lo que funcionaba por accidente: dependía de que la asignación
 * ocurriera antes del primer `import` del módulo, y los imports se evalúan
 * primero.
 *
 * La URL es un marcador, no una base real: las pruebas unitarias usan dobles y
 * nunca ejecutan una consulta, y Prisma no abre la conexión hasta la primera.
 * Si alguna intentara tocar la base, fallaría al conectar en lugar de contaminar
 * una base de verdad, que es justo lo que se quiere.
 *
 * Si la variable ya viene del entorno no se toca, para no interferir con las
 * pruebas de integración ni con el contenedor.
 */
process.env.DATABASE_URL ??=
  'postgresql://pruebas-unitarias:sin-conexion@localhost:1/no-se-usa';
