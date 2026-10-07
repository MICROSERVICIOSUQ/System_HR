import express, { Request, Response, NextFunction } from 'express';
import vacationRoutes from './routes/vacation.routes.ts';
import balanceRoutes from './routes/balance.routes.ts';
import { VacationController } from './controllers/vacation.controller.ts';

const app = express();
const controller = new VacationController();

app.use(express.json());

// ── OpenAPI / Swagger ──────────────────────────────────────────────────────────
// Documentación inline sin dep externa de swagger-ui-express
// Disponible en GET /api-docs (JSON spec)
app.get('/api-docs', (_req: Request, res: Response) => {
  res.json({
    openapi: '3.0.3',
    info: {
      title: 'Vacations Service API',
      version: '2.0.0',
      description:
        'Microservicio de Vacaciones — Actúa como PRODUCTOR (POST /vacations, publica vacaciones.programadas) y CONSUMIDOR CQRS (saldos desde eventos RabbitMQ).',
    },
    servers: [{ url: 'http://localhost:8084', description: 'Local' }],
    tags: [
      { name: 'Períodos', description: 'CRUD de períodos de vacaciones (Productor)' },
      { name: 'Saldos', description: 'Read Model CQRS de saldos (Consumidor asíncrono)' },
      { name: 'Health', description: 'Estado del servicio' },
    ],
    paths: {
      '/health': {
        get: {
          tags: ['Health'],
          summary: 'Estado del servicio',
          responses: { '200': { description: '{ status: "ok", service: "vacations" }' } },
        },
      },
      '/vacations': {
        post: {
          tags: ['Períodos'],
          summary: 'Programar un período de vacaciones',
          description:
            'Valida: fechaFin > fechaInicio, fechaInicio no en el pasado, empleado existente (en VacationBalance), sin solapamiento con PROGRAMADA/EN_CURSO. Publica vacaciones.programadas en RabbitMQ.',
          requestBody: {
            required: true,
            content: {
              'application/json': {
                schema: {
                  type: 'object',
                  required: ['empleadoId', 'fechaInicio', 'fechaFin'],
                  properties: {
                    empleadoId: { type: 'string', example: 'E401' },
                    fechaInicio: { type: 'string', format: 'date', example: '2026-11-01' },
                    fechaFin: { type: 'string', format: 'date', example: '2026-11-15' },
                  },
                },
              },
            },
          },
          responses: {
            '201': { description: 'Período creado y evento publicado' },
            '400': { description: 'Validación fallida (fechas, solapamiento)' },
            '404': { description: 'Empleado no encontrado' },
          },
        },
        get: {
          tags: ['Períodos'],
          summary: 'Listar períodos de vacaciones',
          parameters: [
            {
              name: 'empleadoId',
              in: 'query',
              schema: { type: 'string' },
              description: 'Filtrar por ID de empleado',
            },
          ],
          responses: { '200': { description: 'Array de períodos' } },
        },
      },
      '/vacations/{id}': {
        get: {
          tags: ['Períodos'],
          summary: 'Obtener período por ID',
          parameters: [{ name: 'id', in: 'path', required: true, schema: { type: 'string' } }],
          responses: {
            '200': { description: 'Período encontrado' },
            '404': { description: 'Período no encontrado' },
          },
        },
        delete: {
          tags: ['Períodos'],
          summary: 'Eliminar período programado',
          parameters: [{ name: 'id', in: 'path', required: true, schema: { type: 'string' } }],
          responses: {
            '200': { description: 'Período eliminado' },
            '404': { description: 'Período no encontrado' },
          },
        },
      },
      '/vacations/balances': {
        get: {
          tags: ['Saldos'],
          summary: 'Listar saldos de vacaciones (Read Model CQRS)',
          parameters: [
            {
              name: 'estado',
              in: 'query',
              schema: { type: 'string', enum: ['ACTIVO', 'RETIRADO'] },
            },
          ],
          responses: { '200': { description: 'Array de VacationBalance' } },
        },
      },
      '/vacations/balances/{employeeId}': {
        get: {
          tags: ['Saldos'],
          summary: 'Consultar saldo de un empleado por ID',
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
  });
});

// Endpoint de salud
app.get('/health', controller.getHealth);

// Rutas de períodos de vacaciones (Productor)
// Importante: /balances debe registrarse ANTES de /:id para evitar colisión de rutas
app.use('/vacations/balances', balanceRoutes);
app.use('/vacations', vacationRoutes);

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
