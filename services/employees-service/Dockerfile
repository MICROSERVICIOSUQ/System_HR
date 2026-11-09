# syntax=docker/dockerfile:1
# ── Etapa 1: compilación ──────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .

COPY src ./src

RUN --mount=type=cache,id=hr-ci-maven-build,target=/root/.m2,sharing=locked mvn -B clean package -DskipTests

# ── Etapa 2: runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN apk add --no-cache curl
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
