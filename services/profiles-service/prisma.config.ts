import { defineConfig } from "prisma/config";

/**
 * Configuración del CLI de Prisma (generate, migrate).
 *
 * El `url` lleva un valor de reserva a propósito. `prisma generate` exige una
 * datasource aunque no se conecte a nada, de modo que sin la variable fallaba
 * incluso al generar el cliente para las pruebas unitarias, que usan dobles y
 * no tocan la base. Era lo que rompía `npm test` en cualquier equipo sin
 * `DATABASE_URL` y, con ello, la etapa de pruebas del pipeline, que corre en un
 * contenedor de Node sin esa variable.
 *
 * No debilita el arranque del servicio: el cliente en ejecución no usa este
 * archivo, sino `src/config/db.ts`, que sigue lanzando una excepción si falta
 * `DATABASE_URL`. Y una reserva que apunta al puerto 1 no puede conectarse por
 * accidente a ninguna base real: `prisma migrate deploy` sin la variable falla
 * al conectar, que es el comportamiento que se quiere.
 */
export default defineConfig({
  schema: "prisma/schema.prisma",
  datasource: {
    url:
      process.env.DATABASE_URL ??
      "postgresql://cliente-prisma:sin-conexion@localhost:1/no-se-usa",
  },
});
