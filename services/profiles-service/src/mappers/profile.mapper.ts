/**
 * Forma del perfil tal como sale por la API.
 *
 * Existe por una razón concreta: la columna pasó de llamarse `employeeId` a
 * `empleadoId`, para alinearla con el modelo canónico del sistema, que usa
 * nombres en español (`departamentoId`, `fechaIngreso`, `fechaRetiro`). Pero
 * `employeeId` era parte de la respuesta publicada, y quitarlo rompería a
 * cualquier cliente que lo lea.
 *
 * La solución es la que ya usa `employees-service`, que expone `id` y
 * `employeeId` como alias del mismo valor: la respuesta lleva los dos nombres.
 * `empleadoId` es el canónico y `employeeId` queda como alias en desuso, que se
 * podrá retirar cuando ningún cliente lo consulte.
 */

/** Perfil tal como lo devuelve Prisma. */
export interface PerfilPersistido {
  id: string;
  empleadoId: string;
  nombre: string;
  apellido: string;
  email: string;
  cargo: string;
  departamentoId: string;
  fechaIngreso: Date;
  fechaRetiro: Date | null;
  estado: string;
  telefono: string | null;
  direccion: string | null;
  ciudad: string | null;
  biografia: string | null;
  fechaCreacion: Date;
  archivado: boolean;
  lastEventId: string;
  updatedAt: Date;
}

export function presentarPerfil(perfil: PerfilPersistido) {
  return {
    id: perfil.id,
    empleadoId: perfil.empleadoId,
    /** @deprecated Alias de `empleadoId`, conservado por compatibilidad. */
    employeeId: perfil.empleadoId,
    nombre: perfil.nombre,
    apellido: perfil.apellido,
    email: perfil.email,
    cargo: perfil.cargo,
    departamentoId: perfil.departamentoId,
    fechaIngreso: perfil.fechaIngreso,
    fechaRetiro: perfil.fechaRetiro,
    estado: perfil.estado,
    // Los cinco campos canónicos del perfil. Se devuelven siempre, también
    // cuando están vacíos: un cliente que dibuje un formulario necesita saber
    // que el campo existe aunque no tenga valor, y un esquema de respuesta
    // variable obliga a comprobar la existencia de cada clave.
    telefono: perfil.telefono,
    direccion: perfil.direccion,
    ciudad: perfil.ciudad,
    biografia: perfil.biografia,
    fechaCreacion: perfil.fechaCreacion,
    archivado: perfil.archivado,
    lastEventId: perfil.lastEventId,
    updatedAt: perfil.updatedAt,
  };
}

export function presentarPerfiles(perfiles: PerfilPersistido[]) {
  return perfiles.map(presentarPerfil);
}
