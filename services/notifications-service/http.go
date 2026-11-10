package main

import (
	"context"
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
	"sync/atomic"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
)

type App struct {
	Service     *Service
	BrokerReady atomic.Bool
	Events      *prometheus.CounterVec
	Registry    *prometheus.Registry
}

func newApp(service *Service) *App {
	registry := prometheus.NewRegistry()
	events := prometheus.NewCounterVec(prometheus.CounterOpts{Name: "notifications_events_total", Help: "Resultados del consumo de eventos."}, []string{"result"})
	service.EmailCount = prometheus.NewCounter(prometheus.CounterOpts{Name: "notifications_emails_processed_total", Help: "Correos procesados sin contar duplicados."})
	if service.EmailSender == nil {
		service.EmailSender = MockEmailSender{}
	}
	registry.MustRegister(service.EmailCount, events, prometheus.NewGoCollector(), prometheus.NewProcessCollector(prometheus.ProcessCollectorOpts{}))
	return &App{Service: service, Events: events, Registry: registry}
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func apiError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]any{"status": status, "error": http.StatusText(status), "message": message})
}

func (a *App) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", a.health)
	mux.Handle("GET /metrics", promhttp.HandlerFor(a.Registry, promhttp.HandlerOpts{}))
	mux.HandleFunc("GET /openapi.json", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(openAPI)
	})
	mux.HandleFunc("GET /docs", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write([]byte(swaggerHTML))
	})
	for _, prefix := range []string{"/notificaciones", "/notifications"} {
		mux.HandleFunc("GET "+prefix, a.list)
		mux.HandleFunc("GET "+prefix+"/empleado/{empleadoId}", a.list)
		mux.HandleFunc("GET "+prefix+"/{messageId}", a.find)
		mux.HandleFunc(prefix, func(w http.ResponseWriter, r *http.Request) { apiError(w, 405, "El historial solo admite consultas") })
		mux.HandleFunc(prefix+"/", func(w http.ResponseWriter, r *http.Request) { apiError(w, 405, "El historial solo admite consultas") })
	}
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) { apiError(w, 404, "Recurso no encontrado") })
	latency := prometheus.NewHistogramVec(prometheus.HistogramOpts{Name: "http_request_duration_seconds", Help: "Duración HTTP."}, []string{"method", "status"})
	requests := prometheus.NewCounterVec(prometheus.CounterOpts{Name: "http_requests_total", Help: "Solicitudes HTTP."}, []string{"method", "status"})
	a.Registry.MustRegister(latency, requests)
	measured := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/metrics" {
			mux.ServeHTTP(w, r)
			return
		}
		start := time.Now()
		response := &statusResponse{ResponseWriter: w, status: 200}
		mux.ServeHTTP(response, r)
		status := strconv.Itoa(response.status)
		latency.WithLabelValues(r.Method, status).Observe(time.Since(start).Seconds())
		requests.WithLabelValues(r.Method, status).Inc()
	})
	return otelhttp.NewHandler(measured, "notifications.http")
}

// Registra el código HTTP sin modificar el cuerpo de la respuesta.
type statusResponse struct {
	http.ResponseWriter
	status int
}

func (w *statusResponse) WriteHeader(code int) { w.status = code; w.ResponseWriter.WriteHeader(code) }

func (a *App) health(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
	defer cancel()
	database, broker := "UP", "UP"
	if a.Service.Repo.Ping(ctx) != nil {
		database = "DOWN"
	}
	if !a.BrokerReady.Load() {
		broker = "DOWN"
	}
	status, code := "UP", http.StatusOK
	if database != "UP" || broker != "UP" {
		status, code = "DOWN", http.StatusServiceUnavailable
	}
	writeJSON(w, code, map[string]any{"status": status, "service": "notifications-service", "checks": map[string]string{"database": database, "messageBroker": broker}})
}

func parseQuery(r *http.Request) (Query, error) {
	q := Query{EmployeeID: r.PathValue("empleadoId"), Page: 1, Limit: 10, SortBy: "processedAt", Order: "DESC"}
	values := r.URL.Query()
	if q.EmployeeID == "" {
		q.EmployeeID = values.Get("empleadoId")
	}
	for _, key := range []string{"page", "limit", "size", "sortBy", "order"} {
		if values.Has(key) {
			q.Paginated = true
		}
	}
	var err error
	if values.Has("page") {
		q.Page, err = strconv.Atoi(values.Get("page"))
		if err != nil || q.Page < 1 || q.Page > 1000000 {
			return q, errInvalidEvent
		}
	}
	limit := values.Get("limit")
	if !values.Has("limit") {
		limit = values.Get("size")
	}
	if values.Has("limit") || values.Has("size") {
		q.Limit, err = strconv.Atoi(limit)
		if err != nil || q.Limit < 1 || q.Limit > 100 {
			return q, errInvalidEvent
		}
	}
	if values.Has("sortBy") {
		q.SortBy = values.Get("sortBy")
		if sortColumns[q.SortBy] == "" {
			return q, errInvalidEvent
		}
	}
	if values.Has("order") {
		q.Order = strings.ToUpper(values.Get("order"))
		if q.Order != "ASC" && q.Order != "DESC" {
			return q, errInvalidEvent
		}
	}
	return q, nil
}

func (a *App) list(w http.ResponseWriter, r *http.Request) {
	query, err := parseQuery(r)
	if err != nil {
		apiError(w, 400, "Paginación u orden inválido; page >= 1, limit entre 1 y 100")
		return
	}
	items, total, err := a.Service.Repo.List(r.Context(), query)
	if err != nil {
		apiError(w, 500, "No se pudo consultar el historial")
		return
	}
	if !query.Paginated {
		writeJSON(w, 200, items)
		return
	}
	pages := (total + query.Limit - 1) / query.Limit
	writeJSON(w, 200, map[string]any{"data": items, "meta": map[string]int{"total": total, "page": query.Page, "limit": query.Limit, "totalPages": pages}})
}

func (a *App) find(w http.ResponseWriter, r *http.Request) {
	n, err := a.Service.Repo.Find(r.Context(), r.PathValue("messageId"))
	if err != nil {
		apiError(w, 500, "No se pudo consultar la notificación")
		return
	}
	if n == nil {
		apiError(w, 404, "Notificación no encontrada")
		return
	}
	writeJSON(w, 200, n)
}

const swaggerHTML = `<!doctype html><html lang="es"><head><meta charset="utf-8"><title>Notificaciones</title>
<link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5/swagger-ui.css"></head>
<body><div id="swagger-ui"></div><script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
<script>SwaggerUIBundle({url:'./openapi.json',dom_id:'#swagger-ui'});</script></body></html>`
