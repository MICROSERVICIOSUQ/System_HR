package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
)

type Query struct {
	EmployeeID string
	Page       int
	Limit      int
	SortBy     string
	Order      string
	Paginated  bool
}

type Repository interface {
	Insert(context.Context, Notification) (bool, error)
	List(context.Context, Query) ([]Notification, int, error)
	Find(context.Context, string) (*Notification, error)
	Ping(context.Context) error
}

type PostgresRepository struct{ DB *sql.DB }

func (r *PostgresRepository) Init(ctx context.Context) error {
	_, err := r.DB.ExecContext(ctx, schemaSQL)
	return err
}

func (r *PostgresRepository) Ping(ctx context.Context) error { return r.DB.PingContext(ctx) }

func (r *PostgresRepository) Insert(ctx context.Context, n Notification) (bool, error) {
	payload, err := json.Marshal(n.Payload)
	if err != nil {
		return false, err
	}
	result, err := r.DB.ExecContext(ctx, `INSERT INTO "NotificationHistory"
	 ("id","messageId","eventType","producer","employeeId","employeeName","email","payload")
	 VALUES ($1,$1,$2,$3,$4,$5,$6,$7) ON CONFLICT ("messageId") DO NOTHING`,
		n.MessageID, n.EventType, n.Producer, n.EmployeeID, n.EmployeeName, n.Email, payload)
	if err != nil {
		return false, err
	}
	count, err := result.RowsAffected()
	return count == 1, err
}

const columns = `"id","messageId","eventType","producer","employeeId","employeeName","email","processedAt","payload"`

func scanNotification(row interface{ Scan(...any) error }) (*Notification, error) {
	var n Notification
	var payload []byte
	if err := row.Scan(&n.ID, &n.MessageID, &n.EventType, &n.Producer, &n.EmployeeID, &n.EmployeeName, &n.Email, &n.ProcessedAt, &payload); err != nil {
		return nil, err
	}
	// También protege las respuestas de registros creados antes de la migración.
	var event Event
	if err := json.Unmarshal(payload, &event); err != nil {
		return nil, err
	}
	n.Payload = safePayload(event)
	return &n, nil
}

func (r *PostgresRepository) Find(ctx context.Context, messageID string) (*Notification, error) {
	n, err := scanNotification(r.DB.QueryRowContext(ctx, `SELECT `+columns+` FROM "NotificationHistory" WHERE "messageId"=$1`, messageID))
	if err == sql.ErrNoRows {
		return nil, nil
	}
	return n, err
}

var sortColumns = map[string]string{
	"messageId": "messageId", "eventType": "eventType", "producer": "producer", "employeeId": "employeeId",
	"employeeName": "employeeName", "email": "email", "processedAt": "processedAt",
}

func (r *PostgresRepository) List(ctx context.Context, q Query) ([]Notification, int, error) {
	where := ` WHERE ($1='' OR "employeeId"=$1)`
	var total int
	if err := r.DB.QueryRowContext(ctx, `SELECT count(*) FROM "NotificationHistory"`+where, q.EmployeeID).Scan(&total); err != nil {
		return nil, 0, err
	}
	sort := sortColumns[q.SortBy]
	if sort == "" {
		sort = "processedAt"
	}
	order := "DESC"
	if q.Order == "ASC" {
		order = "ASC"
	}
	statement := `SELECT ` + columns + ` FROM "NotificationHistory"` + where + fmt.Sprintf(` ORDER BY "%s" %s, "id" %s`, sort, order, order)
	args := []any{q.EmployeeID}
	if q.Paginated {
		statement += ` LIMIT $2 OFFSET $3`
		args = append(args, q.Limit, (q.Page-1)*q.Limit)
	}
	rows, err := r.DB.QueryContext(ctx, statement, args...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	items := []Notification{}
	for rows.Next() {
		n, err := scanNotification(rows)
		if err != nil {
			return nil, 0, err
		}
		items = append(items, *n)
	}
	return items, total, rows.Err()
}
