package main

import (
	"crypto/tls"
	"errors"
	"fmt"
	"net"
	"net/mail"
	"net/smtp"
	"os"
	"strconv"
	"strings"
	"time"
)

type EmailSender interface {
	Send(to string, subject string, body string) error
}

type MockEmailSender struct{}

func (MockEmailSender) Send(string, string, string) error {
	return nil
}

type SMTPEmailSender struct {
	Host     string
	Port     string
	Username string
	Password string
	TLS      bool
	From     string
}

func (s SMTPEmailSender) Send(to string, subject string, body string) error {
	if s.Host == "" || s.Port == "" || s.From == "" || to == "" {
		return errors.New("configuración SMTP incompleta")
	}
	fromAddress, err := mail.ParseAddress(s.From)
	if err != nil {
		return errors.New("remitente SMTP inválido")
	}
	toAddress, err := mail.ParseAddress(to)
	if err != nil {
		return errors.New("destinatario SMTP inválido")
	}
	subject = strings.ReplaceAll(strings.ReplaceAll(subject, "\r", " "), "\n", " ")
	message := []byte("From: " + fromAddress.String() + "\r\n" +
		"To: " + toAddress.String() + "\r\n" +
		"Subject: " + subject + "\r\n" +
		"MIME-Version: 1.0\r\n" +
		"Content-Type: text/plain; charset=UTF-8\r\n\r\n" +
		body)
	address := net.JoinHostPort(s.Host, s.Port)
	connection, err := net.DialTimeout("tcp", address, 10*time.Second)
	if err != nil {
		return fmt.Errorf("no se pudo conectar al servidor SMTP: %w", err)
	}
	defer connection.Close()
	if err := connection.SetDeadline(time.Now().Add(15 * time.Second)); err != nil {
		return errors.New("no se pudo configurar el timeout SMTP")
	}
	client, err := smtp.NewClient(connection, s.Host)
	if err != nil {
		return fmt.Errorf("no se pudo iniciar la sesión SMTP: %w", err)
	}
	defer client.Close()
	if s.TLS {
		if err := client.StartTLS(&tls.Config{ServerName: s.Host, MinVersion: tls.VersionTLS12}); err != nil {
			return fmt.Errorf("no se pudo establecer TLS con SMTP: %w", err)
		}
	}
	if s.Username != "" {
		if err := client.Auth(smtp.PlainAuth("", s.Username, s.Password, s.Host)); err != nil {
			return fmt.Errorf("falló la autenticación SMTP: %w", err)
		}
	}
	if err := client.Mail(fromAddress.Address); err != nil {
		return fmt.Errorf("SMTP rechazó el remitente: %w", err)
	}
	if err := client.Rcpt(toAddress.Address); err != nil {
		return fmt.Errorf("SMTP rechazó el destinatario: %w", err)
	}
	writer, err := client.Data()
	if err != nil {
		return fmt.Errorf("SMTP no aceptó el mensaje: %w", err)
	}
	if _, err := writer.Write(message); err != nil {
		_ = writer.Close()
		return fmt.Errorf("no se pudo transmitir el mensaje SMTP: %w", err)
	}
	if err := writer.Close(); err != nil {
		return fmt.Errorf("SMTP no confirmó el mensaje: %w", err)
	}
	return nil
}

func configuredEmailSender() (EmailSender, error) {
	if strings.ToLower(os.Getenv("EMAIL_SENDER")) != "smtp" {
		return MockEmailSender{}, nil
	}
	host := strings.TrimSpace(os.Getenv("EMAIL_SMTP_HOST"))
	port := strings.TrimSpace(os.Getenv("EMAIL_SMTP_PORT"))
	from := strings.TrimSpace(os.Getenv("EMAIL_SMTP_FROM"))
	if host == "" || port == "" || from == "" {
		return nil, errors.New("EMAIL_SMTP_HOST, EMAIL_SMTP_PORT y EMAIL_SMTP_FROM son obligatorias con EMAIL_SENDER=smtp")
	}
	if _, err := strconv.Atoi(port); err != nil {
		return nil, errors.New("EMAIL_SMTP_PORT debe ser numérico")
	}
	useTLS, err := strconv.ParseBool(env("EMAIL_SMTP_TLS", "true"))
	if err != nil {
		return nil, errors.New("EMAIL_SMTP_TLS debe ser true o false")
	}
	return SMTPEmailSender{
		Host:     host,
		Port:     port,
		Username: os.Getenv("EMAIL_SMTP_USERNAME"),
		Password: os.Getenv("EMAIL_SMTP_PASSWORD"),
		TLS:      useTLS,
		From:     from,
	}, nil
}
