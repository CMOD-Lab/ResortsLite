# =============================================================================
# Multi-stage Dockerfile for ResortsLite (BookingComp)
# Framework : Spring Boot 2.7.18
# Java      : 8
# Build Tool: Maven
# =============================================================================

# ---------------------------------------------------------------------------
# Stage 1 – Builder
# ---------------------------------------------------------------------------
FROM maven:3.9.4-eclipse-temurin-8 AS builder

WORKDIR /workspace

# Copy dependency manifests first for layer-cache efficiency
COPY pom.xml .

# Pre-download all dependencies (cached unless pom.xml changes)
RUN mvn dependency:go-offline -B

# Copy the full source tree
COPY src ./src

# Build the fat JAR (skip tests – tests run in CI pipeline)
RUN mvn clean package -DskipTests -B

# ---------------------------------------------------------------------------
# Stage 2 – Runtime
# ---------------------------------------------------------------------------
FROM eclipse-temurin:8-jdk

# Metadata
LABEL maintainer="platform-team" \
      app="resortslite" \
      version="1.0.0" \
      description="ResortsLite Spring Boot application"

# Timezone
ENV TZ=UTC

# JVM tuning – container-aware heap sizing
ENV JAVA_OPTS="-Xms256m -Xmx512m \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+UseG1GC \
  -Djava.security.egd=file:/dev/./urandom \
  -Dfile.encoding=UTF-8 \
  -Duser.timezone=UTC"

# Spring profile
ENV SPRING_PROFILES_ACTIVE=docker

# Application port
ENV SERVER_PORT=8080

# Report / backup paths (container-safe defaults)
ENV REPORT_BASE_PATH=/tmp/reports
ENV BACKUP_PATH=/tmp/backups

# Redis defaults (override at runtime via ECS task env vars)
ENV REDIS_HOST=localhost
ENV REDIS_PORT=6379

# Payment / inventory / notification service endpoints
ENV PAYMENT_API_URL=http://payment-service:9090/payments/charge
ENV APP_PAYMENT_ENDPOINT=http://payment-svc.internal:9090/charge
ENV APP_INVENTORY_ENDPOINT=http://inventory-svc.internal:8081/rooms
ENV APP_NOTIFICATION_ENDPOINT=http://notify.internal:7070/send

# Create a non-root user for security
RUN groupadd --system appgroup && \
    useradd --system --gid appgroup --shell /bin/false appuser

# Application directory
WORKDIR /app

# Copy the fat JAR from the builder stage
COPY --from=builder /workspace/target/*.jar app.jar

# Ensure the non-root user owns the app directory
RUN chown -R appuser:appgroup /app

# Switch to non-root user
USER appuser

# Expose application port
EXPOSE 8080

# Graceful shutdown via exec form (PID 1 receives SIGTERM)
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
