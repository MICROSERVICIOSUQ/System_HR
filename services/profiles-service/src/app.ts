import express, { Request, Response, NextFunction } from 'express';
import profileRoutes from './routes/profile.routes.ts';
import { ProfileController } from './controllers/profile.controller.ts';

const app = express();
const controller = new ProfileController();

app.use(express.json());

// Endpoint de salud
app.get('/health', controller.getHealth);

// Rutas de perfiles
app.use('/profiles', profileRoutes);

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
  console.error('[PROFILE] [ERROR] Error no controlado:', err);
  res.status(500).json({
    status: 500,
    error: 'Internal Server Error',
    message: err.message || 'Error interno del servidor',
  });
});

export default app;
