package main

import (
	"context"
	"database/sql"
	"embed"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	_ "github.com/jackc/pgx/v5/stdlib"
)

//go:embed schema.sql
var schemaSQL string

//go:embed templates/*.txt
var templateFiles embed.FS

//go:embed openapi.json
var openAPI []byte

func env(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}

func main() {
	logger := newLogger()
	if os.Getenv("DATABASE_URL") == "" {
		logger.Error("DATABASE_URL es obligatoria")
		os.Exit(1)
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	shutdownTracing, err := setupTracing()
	if err != nil {
		logger.Error("No se pudo configurar tracing")
		os.Exit(1)
	}
	defer func() {
		c, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = shutdownTracing(c)
	}()
	db, err := sql.Open("pgx", os.Getenv("DATABASE_URL"))
	if err != nil {
		logger.Error("Configuración PostgreSQL inválida")
		os.Exit(1)
	}
	defer db.Close()
	db.SetMaxOpenConns(5)
	db.SetConnMaxLifetime(5 * time.Minute)
	repo := &PostgresRepository{DB: db}
	initCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
	err = repo.Init(initCtx)
	cancel()
	if err != nil {
		logger.Error("No se pudo preparar la base de datos")
		os.Exit(1)
	}
	templates, err := loadTemplates(os.Getenv("TEMPLATES_DIR"))
	if err != nil {
		logger.Error("Plantillas inválidas")
		os.Exit(1)
	}
	emailSender, err := configuredEmailSender()
	if err != nil {
		logger.Error("Configuración de correo inválida")
		os.Exit(1)
	}
	app := newApp(&Service{Repo: repo, Templates: templates, Logger: logger, EmailSender: emailSender})
	go app.consume(ctx)
	server := &http.Server{Addr: ":" + env("PORT", "8082"), Handler: app.Handler(), ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 10 * time.Second, WriteTimeout: 10 * time.Second, IdleTimeout: 60 * time.Second}
	go func() {
		logger.Info("Servidor HTTP iniciado")
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Error("Servidor HTTP detenido por un error")
			stop()
		}
	}()
	<-ctx.Done()
	closeCtx, closeCancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer closeCancel()
	_ = server.Shutdown(closeCtx)
}
