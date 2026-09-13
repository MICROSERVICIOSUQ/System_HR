import express from 'express';
import departmentRoutes from './routes/department.routes.ts';
import { errorHandler, notFoundHandler } from './middleware/error.middleware.ts';

const app = express();

app.use(express.json());

app.get('/health', (_req, res) => {
  res.json({ status: 'ok', service: 'departamentos' });
});

app.use('/departamentos', departmentRoutes);
app.use(notFoundHandler);
app.use(errorHandler);

export default app;
