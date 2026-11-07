import express from 'express';
import swaggerUi from 'swagger-ui-express';
import departmentRoutes from './routes/department.routes.ts';
import { openApiSpec } from './docs/openapi.ts';
import { errorHandler, notFoundHandler } from './middleware/error.middleware.ts';
import { middlewareMetricas, handlerMetricas } from './observability/metrics.ts';
import { comprobarSalud } from './observability/health.ts';

const app = express();

app.use(express.json());

// Mide todas las peticiones. Va antes de las rutas para que el temporizador
// arranque cuando la petición entra, no cuando llega al controlador.
app.use(middlewareMetricas);

// Endpoint que raspa Prometheus cada 15 s (modelo pull).
app.get('/metrics', handlerMetricas);

// Sonda de salud: verifica PostgreSQL de verdad y devuelve 503 si está caído.
app.get('/health', async (_req, res, next) => {
  try {
    const salud = await comprobarSalud();
    res.status(salud.status === 'UP' ? 200 : 503).json(salud);
  } catch (error) {
    next(error);
  }
});

// Documentación OpenAPI / Swagger UI
app.get('/docs/openapi.json', (_req, res) => {
  res.json(openApiSpec);
});
app.use('/docs', swaggerUi.serve, swaggerUi.setup(openApiSpec));
app.get('/swagger-ui', (_req, res) => {
  res.redirect('/docs');
});

app.use('/departamentos', departmentRoutes);
app.use(notFoundHandler);
app.use(errorHandler);

export default app;
