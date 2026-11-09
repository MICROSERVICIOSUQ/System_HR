import { NodeSDK } from '@opentelemetry/sdk-node';
import { BatchSpanProcessor } from '@opentelemetry/sdk-trace';
import { ZipkinExporter } from '@opentelemetry/exporter-zipkin';
import { HttpInstrumentation } from '@opentelemetry/instrumentation-http';

const endpoint = process.env.OTEL_EXPORTER_ZIPKIN_ENDPOINT;
export const telemetry = new NodeSDK({
  serviceName: 'vacations-service',
  spanProcessors: endpoint ? [new BatchSpanProcessor({ exporter: new ZipkinExporter({ url: endpoint }) })] : [],
  instrumentations: [new HttpInstrumentation()],
});
telemetry.start();
