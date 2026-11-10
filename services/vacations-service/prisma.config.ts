// @ts-nocheck
import { defineConfig } from "prisma/config";

/**
 * Configuracion del CLI de Prisma (generate, migrate).
 *
 * El `url` lleva un valor de reserva a proposito. `prisma generate` exige una
 * datasource aunque no se conecte a nada, de modo que sin la variable fallaba al
 * generar el cliente para las pruebas unitarias, que usan dobles y no tocan la
 * base. Y el cliente generado no esta en Git, asi que en un clon nuevo —o en el
 * contenedor del pipeline— las pruebas no podian ni cargarse:
 * "Cannot find module '../generated/prisma/client.ts'".
 *
 * No debilita el arranque del servicio: el cliente en ejecucion no usa este
 * archivo, sino src/config/db.ts, que sigue fallando si falta DATABASE_URL. Y
 * una reserva que apunta al puerto 1 no puede conectarse por accidente a
 * ninguna base real.
 */
export default defineConfig({
  schema: "prisma/schema.prisma",
  datasource: {
    url:
      process.env.DATABASE_URL ??
      "postgresql://cliente-prisma:sin-conexion@localhost:1/no-se-usa",
  },
});
