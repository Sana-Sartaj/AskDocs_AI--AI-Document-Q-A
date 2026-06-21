# ── Build stage ───────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine AS build
ARG APP_VERSION=0.0.1-SNAPSHOT
WORKDIR /app

# Cache Maven dependencies separately from source (speeds up rebuilds)
COPY pom.xml .
RUN mvn dependency:go-offline -B -q

COPY src ./src
RUN mvn clean package -DskipTests -B -q

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

RUN addgroup -S docqa && adduser -S docqa -G docqa

COPY --from=build /app/target/*.jar app.jar
COPY scripts/entrypoint.sh ./entrypoint.sh
RUN chmod +x ./entrypoint.sh

USER docqa

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=5 \
    CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["./entrypoint.sh"]
