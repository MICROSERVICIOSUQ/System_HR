package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"
)

type memoryRepo struct {
	items     []Notification
	err       error
	calls     int
	findCalls int
}

type recordingEmailSender struct {
	to, subject, body string
	calls             int
	err               error
}

func (s *recordingEmailSender) Send(to, subject, body string) error {
	s.calls++
	s.to, s.subject, s.body = to, subject, body
	return s.err
}

func (r *memoryRepo) Insert(_ context.Context, n Notification) (bool, error) {
	r.calls++
	if r.err != nil {
		return false, r.err
	}
	for _, item := range r.items {
		if item.MessageID == n.MessageID {
			return false, nil
		}
	}
	r.items = append(r.items, n)
	return true, nil
}
func (r *memoryRepo) List(_ context.Context, q Query) ([]Notification, int, error) {
	items := []Notification{}
	for _, n := range r.items {
		if q.EmployeeID == "" || n.EmployeeID == q.EmployeeID {
			items = append(items, n)
		}
	}
	return items, len(items), r.err
}
func (r *memoryRepo) Find(_ context.Context, id string) (*Notification, error) {
	r.findCalls++
	for _, n := range r.items {
		if n.MessageID == id {
			return &n, r.err
		}
	}
	return nil, r.err
}
func (r *memoryRepo) Ping(context.Context) error { return r.err }

func testService(t *testing.T) (*Service, *memoryRepo, *bytes.Buffer) {
	t.Helper()
	templates, err := loadTemplates("")
	if err != nil {
		t.Fatal(err)
	}
	repo := &memoryRepo{}
	logs := &bytes.Buffer{}
	return &Service{Repo: repo, Templates: templates, Logger: slog.New(slog.NewJSONHandler(logs, nil))}, repo, logs
}

func eventBody(eventType string) []byte {
	data := map[string]any{"id": "E001", "empleadoId": "E001", "email": "persona@example.test", "nombre": "Ana", "token": "TOKEN-QUE-NO-DEBE-FILTRARSE", "password": "CLAVE-QUE-NO-DEBE-FILTRARSE", "fechaInicio": "2026-12-01", "fechaFin": "2026-12-02", "motivo": "VACACIONES", "permanente": false}
	if strings.HasPrefix(eventType, "vacaciones.") {
		data["id"] = "PERIODO-123"
	}
	body, _ := json.Marshal(Event{ID: "event-" + eventType, Type: eventType, Version: "1.0", Producer: "test", Data: data})
	return body
}

func TestEventsAndSecrets(t *testing.T) {
	for eventType := range eventTemplates {
		t.Run(eventType, func(t *testing.T) {
			s, repo, logs := testService(t)
			result, err := s.Process(context.Background(), eventBody(eventType))
			if err != nil || result != "PROCESSED" {
				t.Fatalf("%s %v", result, err)
			}
			if len(repo.items) != 1 || repo.items[0].EmployeeID != "E001" {
				t.Fatal("ID de empleado incorrecto")
			}
			payload, _ := json.Marshal(repo.items[0])
			for _, secret := range []string{"TOKEN-QUE-NO-DEBE-FILTRARSE", "CLAVE-QUE-NO-DEBE-FILTRARSE", "password", "token"} {
				if strings.Contains(string(payload), secret) || strings.Contains(logs.String(), secret) {
					t.Fatal("Se filtró información sensible")
				}
			}
			before := logs.Len()
			result, err = s.Process(context.Background(), eventBody(eventType))
			if err != nil || result != "DUPLICATE" || len(repo.items) != 1 || logs.Len() != before {
				t.Fatal("Evento duplicado no fue idempotente")
			}
		})
	}
}

func TestEventValidation(t *testing.T) {
	s, repo, _ := testService(t)
	for _, body := range []string{"{", `null`, `{"id":"a","type":"usuario.creado","data":{"id":"E001"}}`, `{"type":"empleado.retirado","data":{"id":"E001"}}`, `{"id":"a","type":"empleado.retirado","data":{}}`} {
		result, err := s.Process(context.Background(), []byte(body))
		// null no tiene un tipo gestionado y se ignora.
		if body == "null" {
			if result != "IGNORED" {
				t.Fatal(result)
			}
			continue
		}
		if !errors.Is(err, errInvalidEvent) {
			t.Fatalf("Se aceptó evento inválido: %s", body)
		}
	}
	if len(repo.items) != 0 {
		t.Fatal("Se guardaron eventos inválidos")
	}
	result, err := s.Process(context.Background(), []byte(`{"type":"otro.evento"}`))
	if result != "IGNORED" || err != nil {
		t.Fatal(result, err)
	}
	repo.err = errors.New("base caída")
	if _, err = s.Process(context.Background(), eventBody("usuario.creado")); err == nil {
		t.Fatal("No propagó el fallo de persistencia")
	}
}

func TestVacationRecipientFromLocalHistory(t *testing.T) {
	s, repo, _ := testService(t)
	var event Event
	_ = json.Unmarshal(eventBody("vacaciones.programadas"), &event)
	delete(event.Data, "email")
	body, _ := json.Marshal(event)
	if _, err := s.Process(context.Background(), body); err == nil {
		t.Fatal("No debe simular correo sin destinatario")
	}
	repo.items = append(repo.items, Notification{EmployeeID: "E001", Email: "persona@example.test"})
	if _, err := s.Process(context.Background(), body); err != nil {
		t.Fatal(err)
	}
	if repo.items[1].Email != "persona@example.test" {
		t.Fatal("No recuperó el destinatario")
	}
	repo.err = errors.New("base caída")
	if _, err := s.Process(context.Background(), body); err == nil {
		t.Fatal("No propagó el fallo de consulta")
	}
}

func TestTemplates(t *testing.T) {
	if _, err := loadTemplates(t.TempDir()); err == nil {
		t.Fatal("Aceptó directorio vacío")
	}
	dir := t.TempDir()
	for _, name := range eventTemplates {
		if name != "" {
			if err := os.WriteFile(filepath.Join(dir, name), []byte("Hola {{.email}}"), 0600); err != nil {
				t.Fatal(err)
			}
		}
	}
	templates, err := loadTemplates(dir)
	if err != nil {
		t.Fatal(err)
	}
	s, _, _ := testService(t)
	s.Templates = templates
	if _, err = s.Process(context.Background(), eventBody("usuario.creado")); err != nil {
		t.Fatal(err)
	}
	_ = os.Remove(filepath.Join(dir, "bienvenida.txt"))
	if _, err = loadTemplates(dir); err == nil {
		t.Fatal("No detectó plantilla faltante")
	}
	s.Templates = templates.New("bienvenida.txt")
	if _, err = s.Templates.Parse(`{{template "no-existe" .}}`); err != nil {
		t.Fatal(err)
	}
	if _, err = s.Process(context.Background(), eventBody("usuario.creado")); err == nil {
		t.Fatal("No detectó error de plantilla")
	}
}

func TestHTTP(t *testing.T) {
	s, repo, _ := testService(t)
	_, _ = s.Process(context.Background(), eventBody("usuario.creado"))
	app := newApp(s)
	handler := app.Handler()
	for _, tc := range []struct {
		method, path string
		code         int
		contains     string
	}{
		{"GET", "/notificaciones", 200, "event-usuario.creado"},
		{"GET", "/notifications", 200, "event-usuario.creado"},
		{"GET", "/notificaciones?empleadoId=E001&page=1&limit=10&sortBy=email&order=asc", 200, "totalPages"},
		{"GET", "/notificaciones/empleado/E001", 200, "E001"},
		{"GET", "/notificaciones/empleado/OTRO", 200, "[]"},
		{"GET", "/notificaciones/event-usuario.creado", 200, "event-usuario.creado"},
		{"GET", "/notificaciones/no-existe", 404, "Notificación no encontrada"},
		{"POST", "/notificaciones", 405, "solo admite consultas"},
		{"PUT", "/notificaciones/event-usuario.creado", 405, "solo admite consultas"},
		{"DELETE", "/notifications/cualquiera", 405, "solo admite consultas"},
		{"POST", "/notifications/empleado/E001", 405, "solo admite consultas"},
		{"GET", "/no-existe", 404, "Recurso no encontrado"},
		{"GET", "/health", 503, "DOWN"},
		{"GET", "/openapi.json", 200, "3.0.3"},
		{"GET", "/docs", 200, "SwaggerUIBundle"},
		{"GET", "/metrics", 200, "http_request_duration_seconds"},
	} {
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, httptest.NewRequest(tc.method, tc.path, nil))
		if response.Code != tc.code || !strings.Contains(response.Body.String(), tc.contains) {
			t.Fatalf("%s: %d %s", tc.path, response.Code, response.Body.String())
		}
		if strings.Contains(response.Body.String(), "TOKEN-QUE") {
			t.Fatal("Token expuesto por HTTP")
		}
	}
	app.BrokerReady.Store(true)
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, httptest.NewRequest("GET", "/health", nil))
	if response.Code != 200 {
		t.Fatal(response.Body.String())
	}
	repo.err = errors.New("conexión rechazada")
	for _, path := range []string{"/notificaciones", "/notificaciones/event-usuario.creado", "/health"} {
		response = httptest.NewRecorder()
		handler.ServeHTTP(response, httptest.NewRequest("GET", path, nil))
		if response.Code < 500 {
			t.Fatal("Se ocultó fallo de dependencia")
		}
		if strings.Contains(response.Body.String(), "conexión rechazada") {
			t.Fatal("Se expuso el error interno")
		}
	}
}

func TestQueryValidation(t *testing.T) {
	for _, query := range []string{"page=0", "page=x", "page=1000001", "limit=101", "limit=0", "limit=x", "size=", "order=DROP", "sortBy=password"} {
		if _, err := parseQuery(httptest.NewRequest("GET", "/notificaciones?"+query, nil)); err == nil {
			t.Fatal("Aceptó " + query)
		}
	}
	q, err := parseQuery(httptest.NewRequest("GET", "/notificaciones?size=2&order=desc", nil))
	if err != nil || q.Limit != 2 || q.Order != "DESC" {
		t.Fatal(q, err)
	}
}

type acknowledger struct {
	acks, nacks, rejects int
	requeue, multiple    bool
}

func (a *acknowledger) Ack(uint64, bool) error {
	a.acks++
	return nil
}

func (a *acknowledger) Nack(_ uint64, multiple, requeue bool) error {
	a.nacks++
	a.multiple = multiple
	a.requeue = requeue
	return nil
}
func (a *acknowledger) Reject(_ uint64, requeue bool) error {
	a.rejects++
	a.requeue = requeue
	return nil
}

func TestAcknowledgements(t *testing.T) {
	for _, mode := range []string{"success", "duplicate", "invalid", "database-down"} {
		t.Run(mode, func(t *testing.T) {
			s, repo, _ := testService(t)
			app := newApp(s)
			ack := &acknowledger{}
			body := eventBody("usuario.creado")
			if mode == "duplicate" {
				_, _ = s.Process(context.Background(), body)
			}
			if mode == "invalid" {
				body = []byte("{")
			}
			if mode == "database-down" {
				repo.err = errors.New("base caída")
			}
			err := app.handleDelivery(context.Background(), amqp.Delivery{Body: body, Acknowledger: ack, Headers: amqp.Table{"traceparent": "00-11111111111111111111111111111111-2222222222222222-01"}}, 0)
			if err != nil {
				t.Fatal(err)
			}
			switch mode {
			case "success", "duplicate":
				if ack.acks != 1 || ack.nacks != 0 {
					t.Fatal(ack)
				}
			case "invalid":
				if ack.rejects != 1 || ack.acks != 0 || ack.requeue {
					t.Fatal(ack)
				}
			case "database-down":
				if repo.findCalls != 3 || ack.nacks != 1 || ack.requeue || ack.multiple || ack.acks != 0 {
					t.Fatal("ACK incorrecto o no reintentó", ack, repo.findCalls)
				}
			}
		})
	}
}

func TestEmailSendersAndTokenIsolation(t *testing.T) {
	if err := (MockEmailSender{}).Send("persona@example.test", "asunto", "cuerpo"); err != nil {
		t.Fatal(err)
	}
	s, repo, logs := testService(t)
	sender := &recordingEmailSender{}
	s.EmailSender = sender
	result, err := s.Process(context.Background(), eventBody("usuario.recuperacion"))
	if err != nil || result != "PROCESSED" {
		t.Fatal(result, err)
	}
	if sender.calls != 1 || sender.to != "persona@example.test" || sender.subject != "usuario.recuperacion" || !strings.Contains(sender.body, "TOKEN-QUE-NO-DEBE-FILTRARSE") {
		t.Fatal("No se despachó correctamente el mensaje renderizado")
	}
	persisted, _ := json.Marshal(repo.items)
	if strings.Contains(string(persisted), "TOKEN-QUE-NO-DEBE-FILTRARSE") || strings.Contains(logs.String(), "TOKEN-QUE-NO-DEBE-FILTRARSE") {
		t.Fatal("Se filtró el token fuera del cuerpo del correo")
	}
}

func TestEmailSenderConfigurationAndSMTPValidation(t *testing.T) {
	t.Setenv("EMAIL_SENDER", "")
	sender, err := configuredEmailSender()
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := sender.(MockEmailSender); !ok {
		t.Fatalf("proveedor predeterminado inesperado: %T", sender)
	}

	t.Setenv("EMAIL_SENDER", "smtp")
	if _, err := configuredEmailSender(); err == nil {
		t.Fatal("aceptó configuración SMTP incompleta")
	}
	t.Setenv("EMAIL_SMTP_HOST", "smtp.example.test")
	t.Setenv("EMAIL_SMTP_PORT", "587")
	t.Setenv("EMAIL_SMTP_FROM", "notificaciones@example.test")
	t.Setenv("EMAIL_SMTP_TLS", "true")
	sender, err = configuredEmailSender()
	if err != nil {
		t.Fatal(err)
	}
	smtpSender, ok := sender.(SMTPEmailSender)
	if !ok || !smtpSender.TLS || smtpSender.Host != "smtp.example.test" || smtpSender.Port != "587" {
		t.Fatalf("configuración SMTP inesperada: %#v", sender)
	}
	if err := (SMTPEmailSender{}).Send("to@example.test", "subject", "body"); err == nil {
		t.Fatal("SMTP aceptó configuración incompleta")
	}
	if err := (SMTPEmailSender{Host: "smtp.example.test", Port: "25", From: "bad\r\naddress"}).Send("to@example.test", "subject", "body"); err == nil {
		t.Fatal("SMTP aceptó remitente inválido")
	}
	if err := (SMTPEmailSender{Host: "smtp.example.test", Port: "25", From: "from@example.test"}).Send("bad\r\naddress", "subject", "body"); err == nil {
		t.Fatal("SMTP aceptó destinatario inválido")
	}

	t.Setenv("EMAIL_SMTP_PORT", "invalid")
	if _, err := configuredEmailSender(); err == nil {
		t.Fatal("aceptó puerto SMTP inválido")
	}
	t.Setenv("EMAIL_SMTP_PORT", "587")
	t.Setenv("EMAIL_SMTP_TLS", "not-a-bool")
	if _, err := configuredEmailSender(); err == nil {
		t.Fatal("aceptó configuración TLS inválida")
	}
}

func TestEmailSenderFailureIsRetried(t *testing.T) {
	s, repo, _ := testService(t)
	s.EmailSender = &recordingEmailSender{err: errors.New("smtp caído")}
	if _, err := s.Process(context.Background(), eventBody("usuario.recuperacion")); err == nil {
		t.Fatal("no propagó fallo de despacho")
	}
	if len(repo.items) != 0 {
		t.Fatal("persistió un evento cuyo correo no se despachó")
	}
}

func TestRetriesAndConfiguration(t *testing.T) {
	attempts := 0
	result, err := processWithRetry(context.Background(), func() (string, error) {
		attempts++
		if attempts < 3 {
			return "", errors.New("temporal")
		}
		return "OK", nil
	}, 0)
	if err != nil || result != "OK" || attempts != 3 {
		t.Fatal(attempts, result, err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if waitRetry(ctx, time.Hour) {
		t.Fatal("Ignoró cancelación")
	}
	if _, err = processWithRetry(ctx, func() (string, error) { return "", errors.New("temporal") }, time.Hour); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
	t.Setenv("RABBITMQ_URL", "amqp://example.test/")
	if rabbitURL() != "amqp://example.test/" {
		t.Fatal(rabbitURL())
	}
	t.Setenv("RABBITMQ_URL", "")
	t.Setenv("RABBITMQ_USER", "u@name")
	t.Setenv("RABBITMQ_PASSWORD", "a/b")
	if !strings.Contains(rabbitURL(), "u%40name:a%2Fb") {
		t.Fatal("URL no escapada")
	}
	t.Setenv("UNIT_ENV", "")
	if env("UNIT_ENV", "fallback") != "fallback" {
		t.Fatal("fallback")
	}
	t.Setenv("UNIT_ENV", "valor")
	if env("UNIT_ENV", "fallback") != "valor" {
		t.Fatal("env")
	}
}
