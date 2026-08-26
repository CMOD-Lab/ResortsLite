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

# Build the fat JAR (skip tests – tests run in CI, not in Docker build)
RUN mvn clean package -DskipTests -B

# ---------------------------------------------------------------------------
# Stage 2 – Runtime
# ---------------------------------------------------------------------------
FROM eclipse-temurin:8-jre

# Metadata
LABEL maintainer="ResortsLite Team" \
      application="bookingcomp" \
      version="1.0.0" \
      description="ResortsLite Booking Component – Spring Boot 2.7.18 / Java 8"

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
EXPOSE 8080
# Management / Actuator port
EXPOSE 8081

# Create a non-root user for security
RUN groupadd --system appgroup && \
    useradd --system --gid appgroup --home /app --shell /bin/false appuser

WORKDIR /app

# Copy the fat JAR from the builder stage
COPY --from=builder /workspace/target/*.jar app.jar

# Create writable directories for reports and backups
RUN mkdir -p /reports /backups/nightly && \
    chown -R appuser:appgroup /app /reports /backups

USER appuser

# Graceful shutdown via SIGTERM
STOPSIGNAL SIGTERM

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
