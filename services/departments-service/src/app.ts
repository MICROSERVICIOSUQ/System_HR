import express from 'express';
import swaggerUi from 'swagger-ui-express';
import departmentRoutes from './routes/department.routes.ts';
import { openApiSpec } from './docs/openapi.ts';
import { errorHandler, notFoundHandler } from './middleware/error.middleware.ts';

const app = express();

app.use(express.json());

app.get('/health', (_req, res) => {
  res.json({ status: 'ok', service: 'departamentos' });
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
