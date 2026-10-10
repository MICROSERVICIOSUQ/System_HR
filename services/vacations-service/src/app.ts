import express, { Request, Response, NextFunction } from 'express';
import vacationRoutes from './routes/vacation.routes.ts';
import balanceRoutes from './routes/balance.routes.ts';
import { VacationController } from './controllers/vacation.controller.ts';

const app = express();
const controller = new VacationController();

app.use(express.json());

// ── OpenAPI / Swagger ──────────────────────────────────────────────────────────
const openApiSpec = {
  openapi: '3.0.3',
  info: {
    title: 'API de Vacaciones',
    version: '2.1.0',
    description:
      'Programación y consulta de períodos de vacaciones. /vacaciones es la ruta canónica; /vacations se mantiene como alias.',
  },
  servers: [
    { url: 'http://localhost:8080', description: 'API Gateway' },
    { url: 'http://localhost:8084', description: 'Servicio directo (red local)' },
  ],
  tags: [
    { name: 'Períodos', description: 'Programación, consulta y cancelación de períodos' },
    { name: 'Saldos', description: 'Read model de saldos alimentado por eventos' },
    { name: 'Health', description: 'Estado del servicio' },
  ],
  paths: {
    '/health': {
      get: {
        tags: ['Health'],
        summary: 'Estado del servicio',
        responses: { '200': { description: 'Servicio disponible' } },
      },
    },
    '/vacaciones': {
      post: {
        tags: ['Períodos'],
        summary: 'Programar un período de vacaciones',
        description:
          'Valida los campos requeridos, las fechas, la existencia del empleado en la réplica local y los solapamientos con períodos PROGRAMADA o EN_CURSO. Publica vacaciones.programadas al crear el período.',
        requestBody: {
          required: true,
          content: {
            'application/json': {
              schema: { $ref: '#/components/schemas/NuevoPeriodo' },
              example: {
                empleadoId: 'E401',
                fechaInicio: '2026-11-01',
                fechaFin: '2026-11-15',
              },
            },
          },
        },
        responses: {
          '200': {
            description: 'Éxito según el contrato del reto. La implementación actual responde 201.',
            content: { 'application/json': { schema: { $ref: '#/components/schemas/Periodo' } } },
          },
          '201': {
            description: 'Respuesta de éxito actual de la implementación.',
            content: { 'application/json': { schema: { $ref: '#/components/schemas/Periodo' } } },
          },
          '400': {
            description: 'Solicitud inválida. Ejemplos de validaciones de negocio:',
            content: {
              'application/json': {
                schema: { $ref: '#/components/schemas/Error' },
                examples: {
                  camposRequeridos: {
                    summary: 'Faltan campos obligatorios',
                    value: { status: 400, error: 'Bad Request', message: 'Los campos empleadoId, fechaInicio y fechaFin son obligatorios' },
                  },
                  fechasIncoherentes: {
                    summary: 'fechaFin menor o igual a fechaInicio',
                    value: { status: 400, error: 'Bad Request', message: 'fechaFin debe ser posterior a fechaInicio' },
                  },
                  fechaPasada: {
                    summary: 'fechaInicio anterior a hoy',
                    value: { status: 400, error: 'Bad Request', message: 'fechaInicio no puede ser una fecha pasada' },
                  },
                  solapamiento: {
                    summary: 'Solapamiento con período PROGRAMADA o EN_CURSO',
                    value: { status: 400, error: 'Bad Request', message: 'El período solicitado se solapa con uno existente' },
                  },
                },
              },
            },
          },
          '404': {
            description:
              'Empleado no encontrado en la réplica local. El controlador actual retorna 404; el requisito del reto solicita 400.',
            content: { 'application/json': { schema: { $ref: '#/components/schemas/Error' } } },
          },
        },
      },
      get: {
        tags: ['Períodos'],
        summary: 'Listar períodos, opcionalmente filtrados por empleado',
        parameters: [
          {
            name: 'empleadoId',
            in: 'query',
            required: false,
            schema: { type: 'string' },
            example: 'E401',
            description: 'Si se especifica, devuelve solo los períodos de ese empleado.',
          },
        ],
        responses: {
          '200': {
            description: 'Lista de períodos',
            content: {
              'application/json': {
                schema: { type: 'array', items: { $ref: '#/components/schemas/Periodo' } },
              },
            },
          },
        },
      },
    },
    '/vacaciones/{id}': {
      get: {
        tags: ['Períodos'],
        summary: 'Consultar período por ID',
        parameters: [
          { name: 'id', in: 'path', required: true, schema: { type: 'string' }, example: 'uuid-del-periodo' },
        ],
        responses: {
          '200': {
            description: 'Período encontrado',
            content: { 'application/json': { schema: { $ref: '#/components/schemas/Periodo' } } },
          },
          '404': { description: 'Período no encontrado' },
        },
      },
      delete: {
        tags: ['Períodos'],
        summary: 'Cancelar período futuro',
        description:
          'Cambia el estado a CANCELADA. Solo permite cancelar si fechaInicio es posterior a hoy.',
        parameters: [
          { name: 'id', in: 'path', required: true, schema: { type: 'string' }, example: 'uuid-del-periodo' },
        ],
        responses: {
          '200': {
            description: 'Período cancelado',
            content: { 'application/json': { schema: { $ref: '#/components/schemas/RespuestaCancelacion' } } },
          },
          '400': {
            description: 'El período ya inició o finalizó',
            content: {
              'application/json': {
                schema: { $ref: '#/components/schemas/Error' },
                example: {
                  status: 400,
                  error: 'Bad Request',
                  message: 'No es posible cancelar un período de vacaciones que ya ha iniciado o finalizado',
                },
              },
            },
          },
          '404': { description: 'Período no encontrado' },
        },
      },
    },
    '/vacaciones/balances': {
      get: {
        tags: ['Saldos'],
        summary: 'Listar saldos de vacaciones',
        parameters: [
          { name: 'estado', in: 'query', schema: { type: 'string', enum: ['ACTIVO', 'RETIRADO'] } },
        ],
        responses: { '200': { description: 'Lista de saldos de empleados' } },
      },
    },
    '/vacaciones/balances/{employeeId}': {
      get: {
        tags: ['Saldos'],
        summary: 'Consultar saldo de un empleado',
        parameters: [
          { name: 'employeeId', in: 'path', required: true, schema: { type: 'string' } },
        ],
        responses: {
          '200': { description: 'Saldo encontrado' },
          '404': { description: 'Empleado no encontrado' },
        },
      },
    },
  },
  components: {
    schemas: {
      NuevoPeriodo: {
        type: 'object',
        required: ['empleadoId', 'fechaInicio', 'fechaFin'],
        properties: {
          empleadoId: { type: 'string', example: 'E401' },
          fechaInicio: { type: 'string', format: 'date', example: '2026-11-01' },
          fechaFin: { type: 'string', format: 'date', example: '2026-11-15' },
        },
      },
      Periodo: {
        type: 'object',
        properties: {
          id: { type: 'string', format: 'uuid' },
          empleadoId: { type: 'string' },
          fechaInicio: { type: 'string', format: 'date' },
          fechaFin: { type: 'string', format: 'date' },
          estado: { type: 'string', enum: ['PROGRAMADA', 'EN_CURSO', 'COMPLETADA', 'CANCELADA'] },
          fechaCreacion: { type: 'string', format: 'date-time' },
        },
      },
      Error: {
        type: 'object',
        properties: {
          status: { type: 'integer' },
          error: { type: 'string' },
          message: { type: 'string' },
        },
      },
      RespuestaCancelacion: {
        type: 'object',
        properties: {
          message: { type: 'string', example: 'Período de vacaciones cancelado correctamente' },
          data: { $ref: '#/components/schemas/Periodo' },
        },
      },
    },
  },
};

const swaggerUiHtml = `<!doctype html>
<html lang="es">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>API de Vacaciones - Swagger UI</title>
    <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5/swagger-ui.css">
  </head>
  <body>
    <div id="swagger-ui"></div>
    <script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
    <script>
      window.onload = () => SwaggerUIBundle({
        url: '/vacaciones/openapi.json',
        dom_id: '#swagger-ui'
      });
    </script>
  </body>
</html>`;

app.get(['/vacaciones/docs', '/vacations/docs'], (_req: Request, res: Response) => {
  res.type('html').send(swaggerUiHtml);
});

app.get(
  ['/api-docs', '/vacaciones/openapi.json', '/vacations/openapi.json'],
  (_req: Request, res: Response) => res.json(openApiSpec)
);

// Endpoint de salud
app.get('/health', controller.getHealth);

// Rutas de períodos de vacaciones (Productor)
// Importante: /balances debe registrarse ANTES de /:id para evitar colisión de rutas
app.use('/vacations/balances', balanceRoutes);
app.use('/vacations', vacationRoutes);
app.use('/vacaciones', vacationRoutes);

// Manejador 404
app.use((_req: Request, res: Response) => {
  res.status(404).json({
    status: 404,
    error: 'Not Found',
    message: 'Recurso no encontrado',
  });
});

// Manejador global de errores
app.use((err: any, _req: Request, res: Response, _next: NextFunction) => {
  console.error('[VACATIONS] [ERROR] Error no controlado:', err);
  res.status(500).json({
    status: 500,
    error: 'Internal Server Error',
    message: err.message || 'Error interno del servidor',
  });
});

export default app;
