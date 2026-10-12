# ADR-004: Selección de RabbitMQ como Message Broker

## Estado

Aceptada — 2026-09-28

## Contexto

El **Reto 4** del sistema System_HR requería incorporar comunicación asíncrona entre microservicios para desacoplar el servicio principal (`employees-service`) de los servicios interesados en los cambios de estado de los empleados (`notifications-service`, `profiles-service` y `vacations-service`).

Bajo el modelo síncrono previo (HTTP directo), cualquier operación de creación, actualización o retiro de empleados requería llamadas secuenciales o dependencias temporales, aumentando la latencia y la fragilidad del sistema. Se requería un intermediario de mensajería (*Message Broker*) que permitiera distribuir un mismo evento a múltiples consumidores de manera confiable, desacoplada y con soporte para confirmaciones de entrega.

## Decisión

Se seleccionó **RabbitMQ** como el broker de mensajería oficial del sistema.

### Justificación técnica

RabbitMQ fue seleccionado en función de los siguientes factores técnicos:

1. **Implementación de AMQP 0-9-1:** Estándar maduro de mensajería empresarial que define con claridad conceptos de exchanges, colas, enlaces (*bindings*) y confirmaciones (*acknowledgements*).
2. **Soporte nativo de Exchanges tipo Fanout:** Permite distribuir idénticos mensajes a múltiples colas suscritas de forma inmediata, sin sobrecarga de procesamiento en el emisor.
3. **Interfaz de Gestión Integrada (RabbitMQ Management UI):** Proporciona visibilidad en tiempo real del estado de conexiones, canales, colas, tasas de entrega y mensajes encolados en el puerto `15672`.
4. **Madurez y Ecosistema de Clientes:** Soporte robusto y oficial tanto para el ecosistema Java/Spring Boot (`spring-boot-starter-amqp`) como para Node.js/TypeScript (`amqplib`).
5. **Idoneidad para la escala del sistema:** Excelente rendimiento y bajo consumo de recursos para sistemas de microservicios de escala pequeña a mediana, sin requerir clusters pesados de coordinación externa.

## Consecuencias

### Positivas
* **Desacoplamiento temporal y espacial:** `employees-service` publica el evento y finaliza su flujo sin depender de la disponibilidad o tiempo de respuesta de los consumidores.
* **Distribución automática 1 a N:** Un solo evento emitido llega automáticamente a todas las colas asociadas al exchange.
* **Mecanismo de confirmación (ACK):** Garantiza que un mensaje no se descarte de la cola hasta que el consumidor haya procesado la lógica de negocio exitosamente.
* **Persistencia y colas duraderas:** Configuración duradera (`durable: true`) que previene la pérdida de mensajes ante reinicios del broker.
* **Monitoreo simplificado:** Diagnóstico visual accesible mediante consola web y métricas de salud por healthcheck.

### Negativas
* **Componente de infraestructura adicional:** Se incorpora un servicio más a la orquestación de Docker Compose (`rabbitmq:3-management`), con su respectivo consumo de memoria y CPU.
* **Complejidad operativa:** Requiere gestión de políticas de conexión, reconexiones automáticas, manejo de canales y supervisión de colas muertas o bloqueadas en comparación con llamadas HTTP directas.

## Alternativas consideradas

* **Apache Kafka:** Descartada. Diseñado para streaming de eventos a gran escala y retención masiva de logs en disco. Para el volumen y patrones de System_HR (mensajería transaccional punto a multipunto), Kafka introduce una complejidad operativa desproporcionada (gestión de particiones, offsets y coordinadores KRaft/ZooKeeper).
* **Redis Pub/Sub:** Descartada. Aunque es muy rápido y ligero, el mecanismo Pub/Sub estándar de Redis no garantiza persistencia durable de mensajes en colas si un consumidor se encuentra temporalmente desconectado ("fire-and-forget"), ni provee confirmación formal de ACK a nivel de mensaje.
* **Comunicación REST directa (HTTP en cascada):** Descartada. Obligaría a `employees-service` a conocer las URLs de todos los servicios receptores, multiplicaría la latencia acumulada y causaría fallos en cascada si alguno de los receptores no está disponible.
