/**
 * Preparación del entorno para las pruebas unitarias.
 *
 * Se carga con `--import` antes que cualquier módulo de la aplicación, por lo que
 * se ejecuta antes de que se evalúen los imports de los archivos de prueba.
 *
 * ¿Por qué hace falta? `src/config/db.ts` falla de inmediato si no hay
 * `DATABASE_URL`. Eso es deliberado en producción —es mejor que el contenedor no
 * arranque que atender peticiones sin base de datos—, pero la cadena de imports
 * de `vacation.service.ts` llega hasta ese módulo, así que sin la variable las
 * pruebas ni siquiera se cargan.
 *
 * La URL que se inyecta es un marcador, no una base de datos real: las pruebas
 * unitarias usan dobles (`RepositorioFake`, `PublisherFake`) y nunca ejecutan una
 * consulta, y Prisma no abre la conexión hasta la primera consulta. Si la suite
 * intentara tocar la base de datos, fallaría al conectar en lugar de contaminar
 * una base real, que es exactamente el comportamiento que se quiere.
 *
 * Si la variable ya viene del entorno no se toca, para no interferir con las
 * pruebas de integración ni con el contenedor.
 */
process.env.DATABASE_URL ??=
  'postgresql://pruebas-unitarias:sin-conexion@localhost:1/no-se-usa';
