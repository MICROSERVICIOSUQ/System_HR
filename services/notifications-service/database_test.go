package main

import (
	"context"
	"database/sql"
	"errors"
	"io"
	"log/slog"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/DATA-DOG/go-sqlmock"
	"go.opentelemetry.io/otel"
)

func mockDatabase(t *testing.T) (*PostgresRepository, sqlmock.Sqlmock) {
	t.Helper()
	db, mock, err := sqlmock.New(sqlmock.MonitorPingsOption(true))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := mock.ExpectationsWereMet(); err != nil {
			t.Error(err)
		}
		db.Close()
	})
	return &PostgresRepository{DB: db}, mock
}
func notificationRows() *sqlmock.Rows {
	return sqlmock.NewRows([]string{"id", "messageId", "eventType", "producer", "employeeId", "employeeName", "email", "processedAt", "payload"}).AddRow(
		"id", "message", "usuario.creado", "auth-service", "E001", "Ana", "persona@example.test", time.Now(),
		`{"id":"message","type":"usuario.creado","data":{"id":"E001","token":"ANTIGUO-SECRETO","nested":{"password":"CLAVE"}}}`)
}

func TestDatabaseInitAndPing(t *testing.T) {
	repo, mock := mockDatabase(t)
	mock.ExpectExec("CREATE TABLE").WillReturnResult(sqlmock.NewResult(0, 0))
	if err := repo.Init(context.Background()); err != nil {
		t.Fatal(err)
	}
	mock.ExpectPing()
	if err := repo.Ping(context.Background()); err != nil {
		t.Fatal(err)
	}
}

func TestDatabaseInsert(t *testing.T) {
	repo, mock := mockDatabase(t)
	n := Notification{MessageID: "m", Payload: map[string]any{"data": map[string]any{"id": "E001"}}}
	for _, count := range []int64{1, 0} {
		mock.ExpectExec("INSERT INTO").WithArgs("m", "", "", "", "", "", sqlmock.AnyArg()).WillReturnResult(sqlmock.NewResult(0, count))
		inserted, err := repo.Insert(context.Background(), n)
		if err != nil || inserted != (count == 1) {
			t.Fatal(inserted, err)
		}
	}
	mock.ExpectExec("INSERT INTO").WillReturnError(errors.New("base caída"))
	if _, err := repo.Insert(context.Background(), n); err == nil {
		t.Fatal("Error ocultado")
	}
	n.Payload = make(chan int)
	if _, err := repo.Insert(context.Background(), n); err == nil {
		t.Fatal("Payload inválido aceptado")
	}
}

func TestDatabaseFind(t *testing.T) {
	repo, mock := mockDatabase(t)
	mock.ExpectQuery("SELECT").WithArgs("message").WillReturnRows(notificationRows())
	n, err := repo.Find(context.Background(), "message")
	if err != nil || n.EmployeeID != "E001" {
		t.Fatal(n, err)
	}
	if strings.Contains(n.Payload.(map[string]any)["data"].(map[string]any)["id"].(string), "SECRETO") {
		t.Fatal("payload")
	}
	if _, found := n.Payload.(map[string]any)["data"].(map[string]any)["token"]; found {
		t.Fatal("Token histórico expuesto")
	}
	mock.ExpectQuery("SELECT").WillReturnError(sql.ErrNoRows)
	if n, err = repo.Find(context.Background(), "missing"); n != nil || err != nil {
		t.Fatal(n, err)
	}
	mock.ExpectQuery("SELECT").WillReturnError(errors.New("base caída"))
	if _, err = repo.Find(context.Background(), "message"); err == nil {
		t.Fatal("Error ocultado")
	}
}

func TestDatabaseList(t *testing.T) {
	for _, paginated := range []bool{false, true} {
		t.Run(map[bool]string{false: "array", true: "page"}[paginated], func(t *testing.T) {
			repo, mock := mockDatabase(t)
			mock.ExpectQuery("SELECT count").WithArgs("E001").WillReturnRows(sqlmock.NewRows([]string{"count"}).AddRow(1))
			query := mock.ExpectQuery("SELECT .* ORDER BY")
			if paginated {
				query.WithArgs("E001", 10, 10)
			} else {
				query.WithArgs("E001")
			}
			query.WillReturnRows(notificationRows())
			items, total, err := repo.List(context.Background(), Query{EmployeeID: "E001", Paginated: paginated, Page: 2, Limit: 10, SortBy: "email", Order: "ASC"})
			if err != nil || len(items) != 1 || total != 1 {
				t.Fatal(items, total, err)
			}
		})
	}
	repo, mock := mockDatabase(t)
	mock.ExpectQuery("SELECT count").WillReturnError(errors.New("base caída"))
	if _, _, err := repo.List(context.Background(), Query{}); err == nil {
		t.Fatal("Error de count ocultado")
	}
	mock.ExpectQuery("SELECT count").WillReturnRows(sqlmock.NewRows([]string{"count"}).AddRow(1))
	mock.ExpectQuery("SELECT .* ORDER BY").WillReturnError(errors.New("consulta fallida"))
	if _, _, err := repo.List(context.Background(), Query{}); err == nil {
		t.Fatal("Error de consulta ocultado")
	}
	mock.ExpectQuery("SELECT count").WillReturnRows(sqlmock.NewRows([]string{"count"}).AddRow(1))
	mock.ExpectQuery("SELECT .* ORDER BY").WillReturnRows(sqlmock.NewRows([]string{"wrong"}).AddRow("bad"))
	if _, _, err := repo.List(context.Background(), Query{}); err == nil {
		t.Fatal("Fila inválida aceptada")
	}
}

func TestStructuredLoggerAndTracing(t *testing.T) {
	shutdown, err := setupTracing()
	if err != nil {
		t.Fatal(err)
	}
	ctx, span := otel.Tracer("test").Start(context.Background(), "correlacion")
	original := os.Stdout
	reader, writer, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	os.Stdout = writer
	logger := newLogger()
	logger.InfoContext(ctx, "prueba")
	logger.With("extra", "valor").InfoContext(ctx, "con atributo", "context", map[string]string{"origen": "test"})
	logger.WithGroup("grupo").InfoContext(ctx, "con grupo")
	_ = writer.Close()
	os.Stdout = original
	output, _ := io.ReadAll(reader)
	_ = reader.Close()
	for _, key := range []string{"timestamp", "level", "service", "message", "context", "traceId", span.SpanContext().TraceID().String()} {
		if !strings.Contains(string(output), key) {
			t.Fatalf("Falta %s: %s", key, output)
		}
	}
	span.End()
	if err := shutdown(context.Background()); err != nil {
		t.Fatal(err)
	}
	// Activa también la rama de configuración de exportación sin enviar datos reales.
	t.Setenv("OTEL_EXPORTER_ZIPKIN_ENDPOINT", "http://localhost:1/api/v2/spans")
	shutdown, err = setupTracing()
	if err != nil {
		t.Fatal(err)
	}
	if err = shutdown(context.Background()); err != nil {
		t.Fatal(err)
	}
	_ = slog.Default()
}

func TestConsumerConnectionFailureAndShutdown(t *testing.T) {
	s, _, _ := testService(t)
	app := newApp(s)
	t.Setenv("RABBITMQ_URL", "://invalid")
	if err := app.consumeConnection(context.Background()); err == nil {
		t.Fatal("Aceptó dirección inválida")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	app.consume(ctx)
}
