package main

import (
	"context"
	"errors"
	"net"
	"net/url"
	"os"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/trace"
)

func rabbitURL() string {
	if value := os.Getenv("RABBITMQ_URL"); value != "" {
		return value
	}
	address := url.URL{Scheme: "amqp", Host: net.JoinHostPort(env("RABBITMQ_HOST", "rabbitmq"), env("RABBITMQ_PORT", "5672")), User: url.UserPassword(os.Getenv("RABBITMQ_USER"), os.Getenv("RABBITMQ_PASSWORD")), Path: "/"}
	return address.String()
}

func waitRetry(ctx context.Context, delay time.Duration) bool {
	select {
	case <-ctx.Done():
		return false
	case <-time.After(delay):
		return true
	}
}

// Tres intentos locales. Si sigue fallando, el consumidor reencola sin ACK.
func processWithRetry(ctx context.Context, process func() (string, error), delay time.Duration) (string, error) {
	var err error
	for attempt := 0; attempt < 3; attempt++ {
		var result string
		result, err = process()
		if err == nil || errors.Is(err, errInvalidEvent) {
			return result, err
		}
		if attempt < 2 && !waitRetry(ctx, delay) {
			return "", ctx.Err()
		}
	}
	return "", err
}

func (a *App) consume(ctx context.Context) {
	for ctx.Err() == nil {
		if err := a.consumeConnection(ctx); err != nil && ctx.Err() == nil {
			// No imprimir el error de conexión: podría contener la URL con credenciales.
			a.Service.Logger.ErrorContext(ctx, "Consumidor desconectado; se reintentará", "context", map[string]any{"retrySeconds": 5})
		}
		a.BrokerReady.Store(false)
		if !waitRetry(ctx, 5*time.Second) {
			return
		}
	}
}

func (a *App) consumeConnection(ctx context.Context) error {
	conn, err := amqp.DialConfig(rabbitURL(), amqp.Config{Dial: amqp.DefaultDial(5 * time.Second)})
	if err != nil {
		return err
	}
	defer conn.Close()
	channel, err := conn.Channel()
	if err != nil {
		return err
	}
	defer channel.Close()
	if err = channel.Qos(1, 0, false); err != nil {
		return err
	}
	// La topología sigue siendo propiedad de infrastructure/rabbitmq.
	messages, err := channel.ConsumeWithContext(ctx, env("RABBITMQ_QUEUE", "notifications.queue"), "", false, false, false, false, nil)
	if err != nil {
		return err
	}
	a.BrokerReady.Store(true)
	defer a.BrokerReady.Store(false)
	a.Service.Logger.InfoContext(ctx, "Consumidor listo", "context", map[string]any{"queue": "notifications.queue"})
	for msg := range messages {
		if err := a.handleDelivery(ctx, msg, time.Second); err != nil {
			return err
		}
	}

	return errors.New("canal cerrado")
}

func (a *App) handleDelivery(ctx context.Context, msg amqp.Delivery, delay time.Duration) error {
	carrier := propagation.MapCarrier{}
	for key, value := range msg.Headers {
		if text, ok := value.(string); ok {
			carrier.Set(key, text)
		}
	}
	parent := otel.GetTextMapPropagator().Extract(ctx, carrier)
	messageCtx, span := otel.Tracer("notifications-service").Start(parent, "notifications.process", trace.WithSpanKind(trace.SpanKindConsumer))
	result, err := processWithRetry(messageCtx, func() (string, error) { return a.Service.Process(messageCtx, msg.Body) }, delay)
	if err == nil {
		a.Events.WithLabelValues(result).Inc()
		err = msg.Ack(false)
	} else if errors.Is(err, errInvalidEvent) {
		a.Events.WithLabelValues("INVALID").Inc()
		a.Service.Logger.WarnContext(messageCtx, "Evento inválido rechazado; no se imprime su contenido")
		err = msg.Reject(false)
	} else {
		a.Events.WithLabelValues("ERROR").Inc()
		span.SetStatus(codes.Error, "Fallo al procesar evento")
		a.Service.Logger.ErrorContext(messageCtx, "Procesamiento fallido; mensaje pendiente de reentrega")
		if !waitRetry(ctx, 5*delay) {
			span.End()
			return ctx.Err()
		}
		err = msg.Nack(false, true)
	}
	span.End()
	if err != nil {
		return err
	}
	return nil
}
