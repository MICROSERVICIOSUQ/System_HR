FROM maven:3.9.9-eclipse-temurin-21

WORKDIR /app

COPY . .

RUN mkdir -p reports

ENV BASE_URL=http://api-gateway:8080

CMD ["mvn", "--batch-mode", "test"]
