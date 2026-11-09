FROM maven:3.9.9-eclipse-temurin-21

WORKDIR /app

COPY . .

# Compila la suite y conserva las dependencias en la imagen. Los escenarios
# se ejecutan al iniciar el contenedor, cuando el sistema ya está saludable.
RUN mkdir -p reports && mvn --batch-mode test -DskipTests

ENV BASE_URL=http://api-gateway:8080

CMD ["mvn", "--batch-mode", "test"]
