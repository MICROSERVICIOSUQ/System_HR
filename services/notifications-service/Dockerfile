FROM golang:1.25-alpine AS build
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY *.go schema.sql openapi.json ./
COPY templates ./templates
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /notifications .

FROM alpine:3.22
RUN apk add --no-cache ca-certificates curl && adduser -D -u 10001 app
COPY --from=build /notifications /notifications
USER app
ENV PORT=8082
EXPOSE 8082
ENTRYPOINT ["/notifications"]
