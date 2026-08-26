# ============================================================
# Stage 1: Builder
# ============================================================
FROM maven:3.8.6-openjdk-8-slim AS builder

WORKDIR /workspace

# Copy dependency descriptor first for layer caching
COPY pom.xml .

# Download all dependencies (cached layer unless pom.xml changes)
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the application JAR (skip tests for Docker build)
RUN mvn clean package -DskipTests -B

# ============================================================
# Stage 2: Runtime
# ============================================================
FROM amazoncorretto:8

WORKDIR /app

# Create non-root user for security
RUN groupadd -r appgroup && useradd -r -g appgroup -s /sbin/nologin appuser

# Set timezone
ENV TZ=UTC

# JVM tuning for containerized environments
ENV JAVA_OPTS="-Xms256m -Xmx512m \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -Djava.security.egd=file:/dev/./urandom \
  -Dfile.encoding=UTF-8 \
  -Duser.timezone=UTC"

# Spring Boot profile and application environment variables
ENV SPRING_PROFILES_ACTIVE=docker
ENV SERVER_PORT=8080

# Redis / ElastiCache connection (injected via Kubernetes ConfigMap / Secrets)
ENV REDIS_HOST=localhost
ENV REDIS_PORT=6379
ENV REDIS_PASSWORD=

# Path variables (injected via Kubernetes ConfigMap)
ENV REPORT_BASE_PATH=/var/reports
ENV BACKUP_PATH=/var/backups/nightly

# Payment API URL (injected via Kubernetes ConfigMap)
ENV PAYMENT_API_URL=http://payment-service/payments/charge

# Copy the built JAR from the builder stage
COPY --from=builder /workspace/target/*.jar app.jar

# Create required runtime directories and set ownership
RUN mkdir -p /var/reports /var/backups/nightly \
    && chown -R appuser:appgroup /app /var/reports /var/backups/nightly

USER appuser

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
