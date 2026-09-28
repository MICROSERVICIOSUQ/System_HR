import express, { Request, Response, NextFunction } from 'express';
import notificationRoutes from './routes/notification.routes.ts';
import { NotificationController } from './controllers/notification.controller.ts';

const app = express();
const controller = new NotificationController();

app.use(express.json());

// Endpoint de salud
app.get('/health', controller.getHealth);

// Rutas de notificaciones
app.use('/notifications', notificationRoutes);

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
  console.error('[EXPRESS] Error no controlado:', err);
  res.status(500).json({
    status: 500,
    error: 'Internal Server Error',
    message: err.message || 'Error interno del servidor',
  });
});

export default app;
