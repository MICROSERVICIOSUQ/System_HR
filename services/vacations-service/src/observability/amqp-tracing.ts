/**
 * Propagación del contexto de traza a través de RabbitMQ (Reto 8).
 *
 * El SDK de OpenTelemetry propaga el contexto por HTTP solo: añade la cabecera
 * `traceparent` a cada petición saliente y la lee en cada entrante. Por el
 * broker NO lo hace, y ahí está el problema: en este ecosistema casi toda la
 * lógica interesante viaja por eventos, así que sin esto la mitad asíncrona del
 * sistema queda fuera de las trazas.
 *
 * El síntoma es engañoso porque no hay ningún error. Zipkin muestra spans
 * sueltos —uno del publicador, otro del consumidor, otro del consumidor del
 * consumidor— sin relación entre sí. Parece que funciona, hasta que hay que
 * depurar por qué un empleado no recibió su correo y resulta imposible seguir
 * el hilo.
 *
 * La solución son las dos mitades de este módulo:
 *
 *   1. Al publicar: inyectar el contexto activo en las cabeceras del mensaje.
 *   2. Al consumir: extraerlo y abrir el span como hijo de ese contexto.
 *
 * El formato es W3C Trace Context, el mismo que usa Spring AMQP en los
 * servicios Java cuando se activa su instrumentación por observación. Por eso
 * una traza que nace en employees-service (Java) y se consume en
 * notifications-service (Go) queda en el mismo árbol: los dos hablan el mismo
 * estándar sin saber nada el uno del otro.
 */
import {
  SpanKind,
  SpanStatusCode,
  context,
  propagation,
  trace,
} from '@opentelemetry/api';

const NOMBRE_SERVICIO = process.env.OTEL_SERVICE_NAME ?? 'vacations-service';

/** Cabeceras AMQP tal como las entrega o las espera amqplib. */
export type CabecerasAmqp = Record<string, unknown>;

/**
 * Devuelve las cabeceras del mensaje con el contexto de traza inyectado.
 *
 * Se parte de las cabeceras que ya traiga el mensaje para no pisar nada (por
 * ejemplo el correlationId que este ecosistema ya propagaba desde el Reto 4).
 */
export function cabecerasConContexto(base: CabecerasAmqp = {}): CabecerasAmqp {
  const cabeceras: CabecerasAmqp = { ...base };
  propagation.inject(context.active(), cabeceras);
  return cabeceras;
}

/**
 * Normaliza las cabeceras antes de extraer el contexto.
 *
 * amqplib puede entregar los valores de cabecera como Buffer en lugar de
 * string, según cómo los haya escrito el publicador. El getter por omisión de
 * OpenTelemetry espera strings, así que un Buffer haría que la extracción
 * fallara en silencio y el span se quedara huérfano — el mismo síntoma que no
 * propagar nada.
 */
function aTexto(cabeceras: CabecerasAmqp | undefined): Record<string, string> {
  const salida: Record<string, string> = {};
  for (const [clave, valor] of Object.entries(cabeceras ?? {})) {
    if (valor === null || valor === undefined) continue;
    salida[clave.toLowerCase()] = Buffer.isBuffer(valor)
      ? valor.toString('utf8')
      : String(valor);
  }
  return salida;
}

/**
 * Ejecuta el procesamiento de un mensaje dentro de un span hijo de la traza que
 * lo originó.
 *
 * El span se marca como CONSUMER, que es lo que hace que Zipkin lo dibuje como
 * un salto por el broker y no como una llamada HTTP más.
 *
 * Si las cabeceras no traen contexto —por ejemplo un mensaje publicado por un
 * servicio todavía sin instrumentar— `extract` devuelve el contexto vacío y el
 * span arranca como raíz. Degrada bien: se pierde el enlace con el origen, no
 * la traza del consumidor.
 */
export async function conSpanDeConsumo<T>(
  nombreEvento: string,
  cabeceras: CabecerasAmqp | undefined,
  atributos: Record<string, string | number>,
  procesar: () => Promise<T>,
): Promise<T> {
  const contextoPadre = propagation.extract(context.active(), aTexto(cabeceras));
  const tracer = trace.getTracer(NOMBRE_SERVICIO);

  return context.with(contextoPadre, () =>
    tracer.startActiveSpan(
      `consume ${nombreEvento}`,
      {
        kind: SpanKind.CONSUMER,
        attributes: {
          'messaging.system': 'rabbitmq',
          'messaging.operation': 'process',
          'messaging.destination.kind': 'queue',
          'event.type': nombreEvento,
          ...atributos,
        },
      },
      async (span) => {
        try {
          const resultado = await procesar();
          span.setStatus({ code: SpanStatusCode.OK });
          return resultado;
        } catch (error) {
          span.recordException(error as Error);
          span.setStatus({
            code: SpanStatusCode.ERROR,
            message: (error as Error).message,
          });
          throw error;
        } finally {
          span.end();
        }
      },
    ),
  );
}

/**
 * Abre un span RAÍZ para un trabajo que no lo origina ninguna petición.
 *
 * Es el caso que el enunciado señala como el difícil: el scheduler de vacaciones
 * dispara `vacaciones.iniciadas` por su cuenta, de madrugada y sin nadie
 * mirando. No hay petición HTTP entrante, así que la instrumentación automática
 * no crea ninguna traza y toda la cadena que sigue —auth-service suspende la
 * cuenta, notifications-service envía el correo— aparecería desconectada.
 *
 * Creando el span aquí y publicando dentro de él, el contexto se inyecta en las
 * cabeceras del mensaje y la cadena completa cuelga de esta raíz.
 */
export async function conSpanRaiz<T>(
  nombre: string,
  atributos: Record<string, string | number>,
  trabajo: () => Promise<T>,
): Promise<T> {
  const tracer = trace.getTracer(NOMBRE_SERVICIO);

  // root: true descarta cualquier contexto activo residual. Sin esto, un span
  // que siguiera activo de una petición anterior convertiría el trabajo del
  // scheduler en hijo suyo, y la traza mezclaría dos cosas sin relación.
  return tracer.startActiveSpan(
    nombre,
    { root: true, kind: SpanKind.INTERNAL, attributes: atributos },
    async (span) => {
      try {
        const resultado = await trabajo();
        span.setStatus({ code: SpanStatusCode.OK });
        return resultado;
      } catch (error) {
        span.recordException(error as Error);
        span.setStatus({
          code: SpanStatusCode.ERROR,
          message: (error as Error).message,
        });
        throw error;
      } finally {
        span.end();
      }
    },
  );
}
