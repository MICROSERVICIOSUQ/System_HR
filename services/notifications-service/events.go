package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/prometheus/client_golang/prometheus"
	"io/fs"
	"log/slog"
	"os"
	"strings"
	"text/template"
	"time"
)

// Se conservan los contratos que ya publican Empleados, Auth y Vacaciones.
type Event struct {
	ID         string         `json:"id"`
	Type       string         `json:"type"`
	Version    string         `json:"version"`
	OccurredAt string         `json:"occurredAt"`
	Producer   string         `json:"producer"`
	Data       map[string]any `json:"data"`
}

type Notification struct {
	ID           string    `json:"id"`
	MessageID    string    `json:"messageId"`
	EventType    string    `json:"eventType"`
	Producer     string    `json:"producer"`
	EmployeeID   string    `json:"employeeId"`
	EmployeeName string    `json:"employeeName"`
	Email        string    `json:"email"`
	ProcessedAt  time.Time `json:"processedAt"`
	Payload      any       `json:"payload"`
}

var eventTemplates = map[string]string{
	"usuario.creado":         "bienvenida.txt",
	"usuario.recuperacion":   "recuperacion.txt",
	"vacaciones.programadas": "vacaciones.txt",
	"vacaciones.iniciadas":   "inicio.txt",
	"vacaciones.finalizadas": "regreso.txt",
	"cuenta.activada":        "activacion.txt",
	"cuenta.desactivada":     "desactivacion.txt",
	"empleado.retirado":      "retiro.txt",
	// Compatibilidad con el historial anterior, sin enviar otra bienvenida.
	"empleado.creado":      "",
	"empleado.actualizado": "",
}

var errInvalidEvent = errors.New("evento inválido")

type Service struct {
	EmailCount  prometheus.Counter
	EmailSender EmailSender
	Repo        Repository
	Templates   *template.Template
	Logger      *slog.Logger
}

func loadTemplates(directory string) (*template.Template, error) {
	var source fs.FS = templateFiles
	pattern := "templates/*.txt"
	if directory != "" {
		source = os.DirFS(directory)
		pattern = "*.txt"
	}
	templates, err := template.New("correos").Option("missingkey=zero").ParseFS(source, pattern)
	if err != nil {
		return nil, err
	}
	for _, name := range eventTemplates {
		if name != "" && templates.Lookup(name) == nil {
			return nil, fmt.Errorf("falta plantilla %s", name)
		}
	}
	return templates, nil
}

func textField(data map[string]any, name string) string {
	value, _ := data[name].(string)
	return strings.TrimSpace(value)
}

// Solo estos datos públicos se guardan. Los campos desconocidos tampoco se copian.
func safePayload(event Event) map[string]any {
	data := map[string]any{}
	for _, key := range []string{"id", "empleadoId", "nombre", "apellido", "email", "departamentoId", "cargo", "fechaIngreso", "fechaRetiro", "fechaInicio", "fechaFin", "estado", "fechaCreacion", "motivo", "permanente"} {
		value, exists := event.Data[key]
		if !exists {
			continue
		}
		switch value.(type) {
		case string, bool, nil:
			data[key] = value
		}
	}
	return map[string]any{"id": event.ID, "type": event.Type, "version": event.Version, "occurredAt": event.OccurredAt, "producer": event.Producer, "data": data}
}

func (s *Service) Process(ctx context.Context, body []byte) (string, error) {
	var event Event
	if json.Unmarshal(body, &event) != nil {
		return "", errInvalidEvent
	}
	filename, allowed := eventTemplates[event.Type]
	if !allowed {
		return "IGNORED", nil
	}
	employeeID := textField(event.Data, "id")
	if strings.HasPrefix(event.Type, "vacaciones.") {
		employeeID = textField(event.Data, "empleadoId")
	}
	if event.ID == "" || employeeID == "" {
		return "", errInvalidEvent
	}
	if event.Type == "usuario.creado" || event.Type == "usuario.recuperacion" {
		if textField(event.Data, "token") == "" || textField(event.Data, "email") == "" {
			return "", errInvalidEvent
		}
	}
	// Vacaciones identifica al empleado con empleadoId, no con data.id (período).
	// El correo se obtiene del historial local, sin llamar a otro microservicio.
	email := textField(event.Data, "email")
	if strings.HasPrefix(event.Type, "vacaciones.") && email == "" {
		items, _, err := s.Repo.List(ctx, Query{EmployeeID: employeeID, SortBy: "processedAt", Order: "DESC"})
		if err != nil {
			return "", err
		}
		for _, item := range items {
			if item.Email != "" {
				email = item.Email
				break
			}
		}
		if email == "" {
			return "", errors.New("destinatario aún no disponible")
		}
	}
	var emailBody string
	if filename != "" {
		var content bytes.Buffer
		if err := s.Templates.ExecuteTemplate(&content, filename, event.Data); err != nil {
			return "", fmt.Errorf("no se pudo preparar la plantilla")
		}
		emailBody = content.String()
	}
	n := Notification{
		MessageID: event.ID, EventType: event.Type, Producer: event.Producer,
		EmployeeID: employeeID, EmployeeName: strings.TrimSpace(textField(event.Data, "nombre") + " " + textField(event.Data, "apellido")),
		Email: email, Payload: safePayload(event),
	}
	existing, err := s.Repo.Find(ctx, event.ID)
	if err != nil {
		return "", err
	}
	if existing != nil {
		return "DUPLICATE", nil
	}
	if filename != "" {
		sender := s.EmailSender
		if sender == nil {
			sender = MockEmailSender{}
		}
		if err := sender.Send(n.Email, event.Type, emailBody); err != nil {
			return "", fmt.Errorf("no se pudo enviar el correo: %w", err)
		}
	}
	inserted, err := s.Repo.Insert(ctx, n)
	if err != nil {
		return "", err
	}
	if !inserted {
		return "DUPLICATE", nil
	}
	if filename != "" {
		if s.EmailCount != nil {
			s.EmailCount.Inc()
		}
		s.Logger.InfoContext(ctx, "Correo procesado", "context", map[string]any{"eventId": event.ID, "eventType": event.Type, "empleadoId": employeeID, "destinatario": n.Email, "plantilla": filename})
	}
	return "PROCESSED", nil
}
