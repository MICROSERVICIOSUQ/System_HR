/**
 * Contrato OpenAPI del servicio de perfiles.
 *
 * <strong>Por qué está escrito a mano y qué lo protege.</strong> La auditoría
 * encontró en el antiguo departments-service un documento OpenAPI escrito a
 * mano, en un archivo aparte del esquema de validación, y los dos se habían
 * desincronizado. Ese servicio se reconstruyó en FastAPI, donde el contrato se
 * genera de los mismos modelos que validan (ADR-012), de modo que la
 * desincronización es imposible.
 *
 * Aquí no hay modelos: la validación de este servicio está escrita a mano en
 * `profile.service.ts` y en `profile.controller.ts`, sin librería de esquemas.
 * Generar el documento exigiría introducir una (Zod + un generador), reescribir
 * toda la validación y cambiar los mensajes de error, que son parte del contrato
 * publicado y están fijados por las colecciones de Postman.
 *
 * La alternativa elegida es más barata y cubre el riesgo real, que es la
 * deriva: el documento **importa del código** lo que puede importar
 * —`CAMPOS_ORDENABLES` para el enum de `sortBy`, las claves de la respuesta del
 * mapeador— y `openapi.test.ts` comprueba que las rutas documentadas son
 * exactamente las que el router registra y que las propiedades del esquema
 * `Perfil` son exactamente las que devuelve `presentarPerfil`. Si alguien añade
 * un campo, una ruta o un valor de ordenación y no toca este archivo, la suite
 * falla. Es la misma garantía por otro medio.
 */

import { CAMPOS_ORDENABLES } from '../repositories/profile.repository.ts';

/** Propiedades del perfil tal como salen por la API, con su tipo OpenAPI. */
const PROPIEDADES_DEL_PERFIL = {
  id: { type: 'string', format: 'uuid', description: 'Identificador interno del perfil' },
  empleadoId: {
    type: 'string',
    example: 'E601',
    description: 'Clave de negocio del empleado. Es el nombre canónico del campo.',
  },
  employeeId: {
    type: 'string',
    example: 'E601',
    deprecated: true,
    description:
      'Alias de empleadoId, conservado por compatibilidad (ADR-013). Los clientes nuevos deben leer empleadoId.',
  },
  nombre: { type: 'string', example: 'Laura' },
  apellido: { type: 'string', example: 'Méndez' },
  email: { type: 'string', format: 'email', example: 'laura.mendez@empresa.com' },
  cargo: { type: 'string', example: 'Desarrolladora' },
  departamentoId: { type: 'string', example: 'D001' },
  fechaIngreso: {
    type: 'string',
    format: 'date-time',
    description: 'Cuándo entró el empleado a la empresa. Lo gobierna employees-service.',
  },
  fechaRetiro: {
    type: 'string',
    format: 'date-time',
    nullable: true,
    description: 'Null mientras el empleado está ACTIVO.',
  },
  estado: { type: 'string', enum: ['ACTIVO', 'RETIRADO'], example: 'ACTIVO' },
  telefono: { type: 'string', nullable: true, example: '+57 300 000 0000' },
  direccion: { type: 'string', nullable: true, example: 'Calle 10 # 5-20' },
  ciudad: { type: 'string', nullable: true, example: 'Armenia' },
  biografia: { type: 'string', nullable: true, example: 'Desarrolladora backend.' },
  fechaCreacion: {
    type: 'string',
    format: 'date-time',
    description:
      'Cuándo se creó el perfil en el read model. Distinta de fechaIngreso: el perfil nace al procesar empleado.creado, que puede llegar mucho después del ingreso.',
  },
  archivado: {
    type: 'boolean',
    description:
      'Un perfil retirado no se borra —el historial se conserva— pero se archiva para que las consultas corrientes no lo arrastren.',
  },
  lastEventId: {
    type: 'string',
    description: 'UUID del último evento aplicado. Es la marca de idempotencia del consumidor.',
  },
  updatedAt: { type: 'string', format: 'date-time' },
} as const;

/** Campos que el propio empleado puede editar sobre su perfil. */
const CAMPOS_EDITABLES = {
  telefono: PROPIEDADES_DEL_PERFIL.telefono,
  direccion: PROPIEDADES_DEL_PERFIL.direccion,
  ciudad: PROPIEDADES_DEL_PERFIL.ciudad,
  biografia: PROPIEDADES_DEL_PERFIL.biografia,
  nombre: PROPIEDADES_DEL_PERFIL.nombre,
  apellido: PROPIEDADES_DEL_PERFIL.apellido,
  email: PROPIEDADES_DEL_PERFIL.email,
  cargo: PROPIEDADES_DEL_PERFIL.cargo,
  departamentoId: PROPIEDADES_DEL_PERFIL.departamentoId,
  estado: PROPIEDADES_DEL_PERFIL.estado,
  fechaRetiro: PROPIEDADES_DEL_PERFIL.fechaRetiro,
} as const;

const respuestaDeError = (descripcion: string) => ({
  description: descripcion,
  content: { 'application/json': { schema: { $ref: '#/components/schemas/Error' } } },
});

const respuestaConPerfil = (descripcion: string) => ({
  description: descripcion,
  content: { 'application/json': { schema: { $ref: '#/components/schemas/Perfil' } } },
});

const parametroEmpleadoId = {
  name: 'empleadoId',
  in: 'path',
  required: true,
  schema: { type: 'string' },
  example: 'E601',
  description: 'Clave de negocio del empleado, no el id interno del perfil',
};

const cabeceraIdempotencia = {
  name: 'Idempotency-Key',
  in: 'header',
  required: false,
  schema: { type: 'string' },
  description:
    'Si se repite el valor, la operación no se aplica dos veces: se devuelve el perfil tal como quedó la primera vez. Es el mismo mecanismo que usa el consumidor de eventos con el eventId.',
};

export const especificacionOpenApi = {
  openapi: '3.0.3',
  info: {
    title: 'API de Perfiles',
    version: '2.0.0',
    description:
      'Read model de perfiles de empleado (CQRS). El perfil se crea y se mantiene consumiendo los eventos empleado.creado, empleado.actualizado y empleado.retirado; el propio empleado completa después sus datos personales. /perfiles es la ruta canónica y /profiles se mantiene como alias.',
  },
  servers: [
    { url: 'http://localhost:8080', description: 'API Gateway (exige el JWT)' },
    { url: 'http://localhost:8083', description: 'Servicio directo (solo dentro de la red interna)' },
  ],
  tags: [
    { name: 'Perfiles', description: 'Consulta y edición de perfiles' },
    { name: 'Observabilidad', description: 'Salud, métricas y contrato' },
  ],
  // El gateway valida la firma del JWT en el borde (ADR-008); el servicio no
  // tiene seguridad propia porque usa expose y no es alcanzable desde el host.
  security: [{ bearerAuth: [] }],
  paths: {
    '/perfiles': {
      get: {
        tags: ['Perfiles'],
        summary: 'Listar perfiles',
        description:
          'Sin parámetros de paginación devuelve un array. Con cualquiera de page, limit, size, sortBy u order devuelve un objeto {data, meta}. La diferencia es deliberada y está publicada: un cliente que hoy recibe un array seguiría recibiéndolo.',
        parameters: [
          {
            name: 'estado',
            in: 'query',
            schema: { type: 'string', enum: ['ACTIVO', 'RETIRADO'] },
            description: 'Filtra por el estado del empleado',
          },
          {
            name: 'archivado',
            in: 'query',
            schema: { type: 'boolean' },
            description:
              'archivado=false es la consulta corriente: los perfiles de quien sigue en la empresa. Omitirlo no filtra, que no es lo mismo que false. Un valor que no sea true o false devuelve 400.',
          },
          { name: 'page', in: 'query', schema: { type: 'integer', minimum: 1, default: 1 } },
          {
            name: 'limit',
            in: 'query',
            schema: { type: 'integer', minimum: 1, maximum: 100, default: 10 },
            description: 'Acepta también el nombre size',
          },
          {
            name: 'sortBy',
            in: 'query',
            schema: { type: 'string', enum: [...CAMPOS_ORDENABLES], default: 'updatedAt' },
            description:
              'Lista blanca: un campo fuera de ella devuelve 400 en lugar de llegar a la consulta.',
          },
          { name: 'order', in: 'query', schema: { type: 'string', enum: ['ASC', 'DESC'], default: 'DESC' } },
        ],
        responses: {
          '200': {
            description: 'Listado de perfiles',
            content: {
              'application/json': {
                schema: {
                  oneOf: [
                    { type: 'array', items: { $ref: '#/components/schemas/Perfil' } },
                    { $ref: '#/components/schemas/PerfilesPaginados' },
                  ],
                },
              },
            },
          },
          '400': respuestaDeError('Estado, paginación, ordenación o archivado inválidos'),
        },
      },
      post: {
        tags: ['Perfiles'],
        summary: 'Crear un perfil',
        description:
          'El camino normal es que el perfil lo cree el consumidor al procesar empleado.creado. Esta ruta existe para altas manuales y para las pruebas.',
        parameters: [cabeceraIdempotencia],
        requestBody: {
          required: true,
          content: {
            'application/json': {
              schema: { $ref: '#/components/schemas/NuevoPerfil' },
            },
          },
        },
        responses: {
          '201': respuestaConPerfil('Perfil creado'),
          '400': respuestaDeError('Falta un campo obligatorio, el email no tiene formato válido o el estado inicial no es ACTIVO'),
          '409': respuestaDeError('Ya existe un perfil para ese empleado'),
        },
      },
    },
    '/perfiles/{empleadoId}': {
      get: {
        tags: ['Perfiles'],
        summary: 'Consultar un perfil',
        parameters: [parametroEmpleadoId],
        responses: {
          '200': respuestaConPerfil('Perfil encontrado'),
          '404': respuestaDeError('No hay perfil para ese empleadoId'),
        },
      },
      put: {
        tags: ['Perfiles'],
        summary: 'Actualizar un perfil',
        description:
          'Es la operación que la regla de propiedad del gateway autoriza a un USER sobre su propio perfil: editar el de otro devuelve 403 antes de llegar aquí (ADR-008). PATCH se comporta igual: las dos aplican solo los campos enviados.',
        parameters: [parametroEmpleadoId, cabeceraIdempotencia],
        requestBody: {
          required: true,
          content: {
            'application/json': { schema: { $ref: '#/components/schemas/CambiosDelPerfil' } },
          },
        },
        responses: {
          '200': respuestaConPerfil('Perfil actualizado'),
          '400': respuestaDeError('Ningún campo para actualizar, un campo vacío o un email inválido'),
          '404': respuestaDeError('No hay perfil para ese empleadoId'),
          '409': respuestaDeError('Un perfil RETIRADO no puede volver a ACTIVO'),
          '503': respuestaDeError('El retiro se guardó pero no se pudo publicar su evento'),
        },
      },
      patch: {
        tags: ['Perfiles'],
        summary: 'Actualizar un perfil (parcial)',
        description: 'Idéntica a PUT: ambas aplican solo los campos enviados.',
        parameters: [parametroEmpleadoId, cabeceraIdempotencia],
        requestBody: {
          required: true,
          content: {
            'application/json': { schema: { $ref: '#/components/schemas/CambiosDelPerfil' } },
          },
        },
        responses: {
          '200': respuestaConPerfil('Perfil actualizado'),
          '400': respuestaDeError('Ningún campo para actualizar, un campo vacío o un email inválido'),
          '404': respuestaDeError('No hay perfil para ese empleadoId'),
          '409': respuestaDeError('Un perfil RETIRADO no puede volver a ACTIVO'),
        },
      },
      delete: {
        tags: ['Perfiles'],
        summary: 'Retirar y archivar un perfil',
        description:
          'No borra nada: marca el perfil como RETIRADO, le pone fechaRetiro y lo archiva, todo en la misma escritura. Por eso responde 200 con el perfil resultante y no 204. El historial se conserva para auditoría.',
        parameters: [parametroEmpleadoId, cabeceraIdempotencia],
        responses: {
          '200': respuestaConPerfil('Perfil retirado y archivado'),
          '404': respuestaDeError('No hay perfil para ese empleadoId'),
          '409': respuestaDeError('El perfil ya estaba RETIRADO'),
          '503': respuestaDeError('El retiro se guardó pero no se pudo publicar su evento'),
        },
      },
    },
    '/health': {
      get: {
        tags: ['Observabilidad'],
        summary: 'Estado del servicio y de sus dependencias',
        description:
          'Comprueba de verdad la base de datos y el broker. Devuelve 503 si alguna falla, para que Docker retire el contenedor del balanceador en lugar de seguir enviándole peticiones.',
        security: [],
        responses: {
          '200': {
            description: 'Servicio sano',
            content: { 'application/json': { schema: { $ref: '#/components/schemas/Salud' } } },
          },
          '503': {
            description: 'Alguna dependencia no responde',
            content: { 'application/json': { schema: { $ref: '#/components/schemas/Salud' } } },
          },
        },
      },
    },
    '/metrics': {
      get: {
        tags: ['Observabilidad'],
        summary: 'Métricas en formato Prometheus',
        security: [],
        responses: {
          '200': {
            description: 'Métricas HTTP y de proceso',
            content: { 'text/plain': { schema: { type: 'string' } } },
          },
        },
      },
    },
  },
  components: {
    securitySchemes: {
      bearerAuth: {
        type: 'http',
        scheme: 'bearer',
        bearerFormat: 'JWT',
        description:
          'Access JWT emitido por auth-service. Lo valida el API Gateway, que además aplica las reglas de rol y de propiedad del recurso.',
      },
    },
    schemas: {
      Perfil: {
        type: 'object',
        description: 'Perfil tal como lo devuelve la API, con el alias employeeId incluido.',
        properties: PROPIEDADES_DEL_PERFIL,
      },
      PerfilesPaginados: {
        type: 'object',
        properties: {
          data: { type: 'array', items: { $ref: '#/components/schemas/Perfil' } },
          meta: {
            type: 'object',
            properties: {
              total: { type: 'integer' },
              page: { type: 'integer' },
              limit: { type: 'integer' },
              totalPages: { type: 'integer' },
            },
          },
        },
      },
      NuevoPerfil: {
        type: 'object',
        required: [
          'empleadoId',
          'nombre',
          'apellido',
          'email',
          'cargo',
          'departamentoId',
          'fechaIngreso',
        ],
        properties: {
          empleadoId: PROPIEDADES_DEL_PERFIL.empleadoId,
          nombre: PROPIEDADES_DEL_PERFIL.nombre,
          apellido: PROPIEDADES_DEL_PERFIL.apellido,
          email: PROPIEDADES_DEL_PERFIL.email,
          cargo: PROPIEDADES_DEL_PERFIL.cargo,
          departamentoId: PROPIEDADES_DEL_PERFIL.departamentoId,
          fechaIngreso: PROPIEDADES_DEL_PERFIL.fechaIngreso,
          estado: {
            type: 'string',
            enum: ['ACTIVO'],
            description: 'Un perfil nuevo solo puede iniciar en ACTIVO. Si se omite, se asume ACTIVO.',
          },
        },
      },
      CambiosDelPerfil: {
        type: 'object',
        minProperties: 1,
        description:
          'Solo los campos enviados se modifican. Los cuatro primeros son los datos personales que el propio empleado completa; el resto los gobierna employees-service y normalmente llegan por evento.',
        properties: CAMPOS_EDITABLES,
      },
      Salud: {
        type: 'object',
        properties: {
          status: { type: 'string', enum: ['UP', 'DOWN'] },
          service: { type: 'string', example: 'profiles-service' },
          checks: {
            type: 'object',
            properties: {
              database: { type: 'string', enum: ['UP', 'DOWN'] },
              messageBroker: { type: 'string', enum: ['UP', 'DOWN'] },
            },
          },
        },
      },
      Error: {
        type: 'object',
        properties: {
          status: { type: 'integer', example: 400 },
          error: { type: 'string', example: 'Bad Request' },
          message: { type: 'string', example: 'archivado debe ser true o false' },
        },
      },
    },
  },
} as const;

/** Claves que el esquema Perfil declara. La usa la prueba de consistencia. */
export const CAMPOS_DOCUMENTADOS_DEL_PERFIL = Object.keys(PROPIEDADES_DEL_PERFIL);

/** Página de Swagger UI. Se sirve igual que en vacations-service. */
export const paginaSwaggerUi = `<!doctype html>
<html lang="es">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>API de Perfiles - Swagger UI</title>
    <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5/swagger-ui.css">
  </head>
  <body>
    <div id="swagger-ui"></div>
    <script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
    <script>
      window.onload = () => SwaggerUIBundle({
        url: '/perfiles/openapi.json',
        dom_id: '#swagger-ui'
      });
    </script>
  </body>
</html>`;
