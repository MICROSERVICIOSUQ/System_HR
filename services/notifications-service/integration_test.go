//go:build integration

package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"
)

// Se ejecuta exclusivamente con PostgreSQL y RabbitMQ del Compose de pruebas.
func TestIntegrationPostgresRabbitHTTP(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	db, err := sql.Open("pgx", os.Getenv("DATABASE_URL"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	repo := &PostgresRepository{DB: db}
	if err = repo.Init(ctx); err != nil {
		t.Fatal(err)
	}
	// Comprueba también la limpieza de datos de la versión anterior.
	_, err = db.ExecContext(ctx, `INSERT INTO "NotificationHistory" ("id","messageId","eventType","producer","employeeId","employeeName","email","payload") VALUES ('legacy','legacy','usuario.creado','auth','E001','Ana','persona@example.test','{"id":"legacy","type":"usuario.creado","data":{"id":"E001","token":"LEGACY-SECRET"}}') ON CONFLICT DO NOTHING`)
	if err != nil {
		t.Fatal(err)
	}
	if err = repo.Init(ctx); err != nil {
		t.Fatal(err)
	}
	var payload string
	if err = db.QueryRowContext(ctx, `SELECT "payload"::text FROM "NotificationHistory" WHERE "id"='legacy'`).Scan(&payload); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(payload, "LEGACY-SECRET") {
		t.Fatal("Token histórico no fue limpiado")
	}
	s, _, _ := testService(t)
	s.Repo = repo
	app := newApp(s)
	conn, err := amqp.Dial(rabbitURL())
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	ch, err := conn.Channel()
	if err != nil {
		t.Fatal(err)
	}
	defer ch.Close()
	if err = ch.ExchangeDeclare("hr.events", "fanout", true, false, false, false, nil); err != nil {
		t.Fatal(err)
	}
	queue, err := ch.QueueDeclare("", false, true, false, false, nil)
	if err != nil {
		t.Fatal(err)
	}
	if err = ch.QueueBind(queue.Name, "", "hr.events", false, nil); err != nil {
		t.Fatal(err)
	}
	t.Setenv("RABBITMQ_QUEUE", queue.Name)
	go app.consume(ctx)
	for !app.BrokerReady.Load() {
		if !waitRetry(ctx, 20*time.Millisecond) {
			t.Fatal("Consumidor no arrancó")
		}
	}
	for _, eventType := range []string{"usuario.creado", "usuario.recuperacion", "vacaciones.programadas", "cuenta.desactivada", "cuenta.activada", "empleado.retirado", "vacaciones.iniciadas", "vacaciones.finalizadas"} {
		for i := 0; i < 2; i++ {
			if err = ch.PublishWithContext(ctx, "hr.events", "", false, false, amqp.Publishing{ContentType: "application/json", Body: eventBody(eventType)}); err != nil {
				t.Fatal(err)
			}
		}
	}
	for {
		var count int
		if err = db.QueryRowContext(ctx, `SELECT count(*) FROM "NotificationHistory" WHERE "messageId" LIKE 'event-%'`).Scan(&count); err != nil {
			t.Fatal(err)
		}
		if count == 8 {
			break
		}
		if !waitRetry(ctx, 20*time.Millisecond) {
			t.Fatalf("Solo se procesaron %d eventos", count)
		}
	}
	handler := app.Handler()
	for _, path := range []string{"/health", "/notificaciones", "/notificaciones?size=2", "/notificaciones/empleado/E001", "/notificaciones/event-usuario.creado", "/metrics", "/openapi.json", "/docs"} {
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, httptest.NewRequest("GET", path, nil))
		if response.Code != 200 {
			t.Fatalf("%s: %d %s", path, response.Code, response.Body.String())
		}
		if strings.Contains(response.Body.String(), "TOKEN-QUE") || strings.Contains(response.Body.String(), "LEGACY-SECRET") {
			t.Fatal("Token expuesto")
		}
		if path == "/notificaciones?size=2" {
			var page struct{ Data []Notification }
			if json.Unmarshal(response.Body.Bytes(), &page) != nil || len(page.Data) != 2 {
				t.Fatal("Paginación incorrecta")
			}
		}
	}
}
