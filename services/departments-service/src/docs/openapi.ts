export const openApiSpec = {
  openapi: '3.0.3',
  info: {
    title: 'Microservicio de Departamentos',
    version: '1.0.0',
    description:
      'API REST para la gestión y administración de departamentos de la organización. ' +
      'Provee operaciones CRUD completas y valida la existencia de departamentos para otros servicios del ecosistema.',
  },
  servers: [
    {
      url: '/',
      description: 'Servidor local o interno',
    },
  ],
  paths: {
    '/health': {
      get: {
        summary: 'Verificar estado y disponibilidad del servicio',
        description: 'Retorna el estado de disponibilidad del microservicio para health checks de Docker o monitoreo.',
        responses: {
          '200': {
            description: 'Servicio operativo',
            content: {
              'application/json': {
                schema: { $ref: '#/components/schemas/HealthResponse' },
              },
            },
          },
          '500': {
            description: 'Error interno del servidor',
            content: {
              'application/json': {
                schema: { $ref: '#/components/schemas/ErrorResponse' },
              },
            },
          },
        },
      },
    },
    '/departamentos': {
      get: {
        summary: 'Listar todos los departamentos',
        description: 'Obtiene el listado completo de los departamentos registrados, ordenados por fecha de creación descendente.',
        responses: {
          '200': {
            description: 'Listado de departamentos obtenido exitosamente',
            content: {
              'application/json': {
                schema: {
                  type: 'array',
                  items: { $ref: '#/components/schemas/Departamento' },
                },
              },
            },
          },
          '503': {
            description: 'Base de datos no disponible temporalmente',
            content: {
              'application/json': {
                schema: { $ref: '#/components/schemas/ErrorResponse' },
              },
            },
          },
        },
      },
      post: {
        summary: 'Registrar un nuevo departamento',
        description: 'Crea un nuevo departamento asignándole un identificador secuencial formateado automáticamente (ej: D001, D002).',
        requestBody: {
          required: true,
          content: {
            'application/json': {
              schema: { $ref: '#/components/schemas/CreateDepartamentoDTO' },
            },
          },
        },
        responses: {
          '201': {
            description: 'Departamento creado exitosamente',
            content: {
              'application/json': {
                schema: { $ref: '#/components/schemas/Departamento' },
              },
            },
          },
          '400': {
            description: 'Datos de entrada inválidos o faltantes',
            content: {
              'application/json': {
                schema: { $ref: '#/components/schemas/ValidationErrorResponse' },
              },
            },
          },
          '503': {
            description: 'Base de datos no disponible temporalmente',
            content: {
              'application/json': {
                schema: { $ref: '#/components/schemas/ErrorResponse' },
              },
            },
          },
        },
      },
    },
    '/departamentos/{id}': {
      get: {
        summary: 'Consultar departamento por identificador',
        description: 'Obtiene los datos de un departamento específico utilizando su identificador único (ej: D001).',
        parameters: [
          {
            name: 'id',
            in: 'path',
            required: true,
            description: 'Identificador del departamento (ej: D001)',
            schema: { type: 'string', example: 'D001' },
          },
        ],
        responses: {
          '200': {
            description: 'Departamento encontrado exitosamente',
            content: {
              'application/json': {
                schema: { $ref: '#/components/schemas/Departamento' },
              },
            },
          },
          '400': {
            description: 'Parámetros de ruta inválidos',
            content: {
              'application/json': {
                schema: { $ref: '#/components/schemas/ValidationErrorResponse' },
              },
            },
          },
          '404': {
            description: 'Departamento no encontrado',
            content: {
              'application/json': {
                schema: { $ref: '#/components/schemas/ErrorResponse' },
              },
            },
          },
          '503': {
            description: 'Base de datos no disponible temporalmente',
            content: {
              'application/json': {
                schema: { $ref: '#/components/schemas/ErrorResponse' },
              },
            },
          },
        },
      },
      put: {
        summary: 'Actualizar un departamento existente',
        description: 'Actualiza el nombre o la descripción de un departamento existente.',
        parameters: [
          {
            name: 'id',
            in: 'path',
            required: true,
            description: 'Identificador del departamento',
            schema: { type: 'string', example: 'D001' },
          },
        ],
        requestBody: {
          required: true,
          content: {
            'application/json': {
              schema: { $ref: '#/components/schemas/UpdateDepartamentoDTO' },
            },
          },
        },
        responses: {
          '200': {
            description: 'Departamento actualizado exitosamente',
            content: {
              'application/json': {
                schema: { $ref: '#/components/schemas/Departamento' },
              },
            },
          },
          '400': {
            description: 'Datos de actualización inválidos',
            content: {
              'application/json': {
                schema: { $ref: '#/components/schemas/ValidationErrorResponse' },
              },
            },
          },
          '404': {
            description: 'Departamento no encontrado',
            content: {
              'application/json': {
                schema: { $ref: '#/components/schemas/ErrorResponse' },
              },
            },
          },
          '503': {
            description: 'Base de datos no disponible temporalmente',
            content: {
              'application/json': {
                schema: { $ref: '#/components/schemas/ErrorResponse' },
              },
            },
          },
        },
      },
      delete: {
        summary: 'Eliminar un departamento',
        description: 'Elimina permanentemente un departamento a partir de su identificador.',
        parameters: [
          {
            name: 'id',
            in: 'path',
            required: true,
            description: 'Identificador del departamento',
            schema: { type: 'string', example: 'D001' },
          },
        ],
        responses: {
          '204': {
            description: 'Departamento eliminado exitosamente (sin contenido)',
          },
          '400': {
            description: 'Identificador inválido',
            content: {
              'application/json': {
                schema: { $ref: '#/components/schemas/ValidationErrorResponse' },
              },
            },
          },
          '404': {
            description: 'Departamento no encontrado',
            content: {
              'application/json': {
                schema: { $ref: '#/components/schemas/ErrorResponse' },
              },
            },
          },
          '503': {
            description: 'Base de datos no disponible temporalmente',
            content: {
              'application/json': {
                schema: { $ref: '#/components/schemas/ErrorResponse' },
              },
            },
          },
        },
      },
    },
  },
  components: {
    schemas: {
      Departamento: {
        type: 'object',
        required: ['id', 'nombre', 'descripcion', 'createdAt', 'updatedAt'],
        properties: {
          id: {
            type: 'string',
            description: 'Identificador secuencial con formato D + 3 dígitos',
            example: 'D001',
          },
          nombre: {
            type: 'string',
            description: 'Nombre descriptivo del departamento',
            example: 'Tecnología',
          },
          descripcion: {
            type: 'string',
            description: 'Propósito y funciones del departamento',
            example: 'Departamento encargado del desarrollo y soporte técnico',
          },
          createdAt: {
            type: 'string',
            format: 'date-time',
            example: '2026-09-12T10:00:00.000Z',
          },
          updatedAt: {
            type: 'string',
            format: 'date-time',
            example: '2026-09-12T10:00:00.000Z',
          },
        },
      },
      CreateDepartamentoDTO: {
        type: 'object',
        required: ['nombre', 'descripcion'],
        properties: {
          id: {
            type: 'string',
            description: 'Identificador del departamento (opcional; si no se provee, se autogenera secuencialmente)',
            example: 'IT',
          },
          nombre: {
            type: 'string',
            description: 'Nombre del departamento (obligatorio)',
            example: 'Tecnología',
          },
          descripcion: {
            type: 'string',
            description: 'Descripción del departamento (obligatoria)',
            example: 'Departamento encargado del desarrollo y soporte técnico',
          },
        },
      },
      UpdateDepartamentoDTO: {
        type: 'object',
        properties: {
          nombre: {
            type: 'string',
            description: 'Nuevo nombre del departamento',
            example: 'Infraestructura y Tecnología',
          },
          descripcion: {
            type: 'string',
            description: 'Nueva descripción del departamento',
            example: 'Departamento responsable de infraestructura, operaciones e I+D',
          },
        },
      },
      ErrorResponse: {
        type: 'object',
        required: ['status', 'message'],
        properties: {
          status: {
            type: 'string',
            example: 'error',
          },
          message: {
            type: 'string',
            example: 'Departamento no encontrado',
          },
        },
      },
      ValidationErrorResponse: {
        type: 'object',
        required: ['status', 'message', 'errors'],
        properties: {
          status: {
            type: 'string',
            example: 'error',
          },
          message: {
            type: 'string',
            example: 'Datos inválidos',
          },
          errors: {
            type: 'array',
            items: {
              type: 'object',
              properties: {
                field: { type: 'string', example: 'nombre' },
                message: { type: 'string', example: 'El nombre es obligatorio' },
              },
            },
          },
        },
      },
      HealthResponse: {
        type: 'object',
        required: ['status', 'service'],
        properties: {
          status: {
            type: 'string',
            example: 'ok',
          },
          service: {
            type: 'string',
            example: 'departamentos',
          },
        },
      },
    },
  },
};
