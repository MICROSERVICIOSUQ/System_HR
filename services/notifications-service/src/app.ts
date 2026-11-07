import express, { Request, Response, NextFunction } from 'express';
import notificationRoutes from './routes/notification.routes.ts';
import { NotificationController } from './controllers/notification.controller.ts';
import { NotificationError } from './errors/notification.errors.ts';
import { middlewareMetricas, handlerMetricas } from './observability/metrics.ts';
import { comprobarSalud } from './observability/health.ts';

const app = express();
const controller = new NotificationController();

app.use(express.json());

// Mide todas las peticiones (Reto 8). Va antes de las rutas para que el
// temporizador arranque cuando la peticion entra, no cuando llega al controlador.
app.use(middlewareMetricas);

// Endpoint que raspa Prometheus cada 15 s (modelo pull).
app.get('/metrics', handlerMetricas);

// Sonda de salud con verificacion real de dependencias: antes devolvia
// {"status":"ok"} sin comprobar nada.
app.get('/health', async (_req, res, next) => {
  try {
    const salud = await comprobarSalud();
    res.status(salud.status === 'UP' ? 200 : 503).json(salud);
  } catch (error) {
    next(error);
  }
});

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
  if (err instanceof NotificationError) {
    return res.status(err.statusCode).json({
      status: err.statusCode,
      error: err.statusCode === 404
        ? 'Not Found'
        : err.statusCode === 409
        ? 'Conflict'
        : 'Bad Request',
      message: err.message,
    });
  }

  if (err?.code === 'P2025') {
    return res.status(404).json({
      status: 404,
      error: 'Not Found',
      message: 'Notificación no encontrada',
    });
  }

  if (err?.code === 'P2002') {
    return res.status(409).json({
      status: 409,
      error: 'Conflict',
      message: 'El messageId ya fue procesado',
    });
  }

  res.status(500).json({
    status: 500,
    error: 'Internal Server Error',
    message: err.message || 'Error interno del servidor',
  });
});

export default app;
