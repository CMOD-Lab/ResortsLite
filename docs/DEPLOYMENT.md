# ResortsLite (BookingComp) – AWS ECS Fargate Deployment Guide

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Project Structure](#project-structure)
4. [Local Development with Docker Compose](#local-development-with-docker-compose)
5. [Build and Push Docker Image](#build-and-push-docker-image)
6. [AWS ECS Fargate Prerequisites](#aws-ecs-fargate-prerequisites)
7. [ECS Task Definition Explained](#ecs-task-definition-explained)
8. [ECS Service Configuration](#ecs-service-configuration)
9. [ECS Fargate Deployment Walkthrough](#ecs-fargate-deployment-walkthrough)
10. [ECS-Specific Troubleshooting](#ecs-specific-troubleshooting)
11. [ECS Fargate Scaling and Management](#ecs-fargate-scaling-and-management)
12. [Configuration Management](#configuration-management)
13. [Security Considerations](#security-considerations)
14. [Java-Specific Notes](#java-specific-notes)

---

## Overview

**Application**: ResortsLite  
**Module**: BookingComp  
**Framework**: Spring Boot 2.7.18  
**Java Version**: 8  
**Build Tool**: Maven  
**Target Platform**: AWS ECS Fargate  
**Application Port**: 8080  
**Health Endpoint**: `/actuator/health`

ResortsLite is a Spring Boot REST API providing resort booking management. It uses Spring Session with Redis for distributed session storage and exposes management endpoints via Spring Boot Actuator.

---

## Prerequisites

### Local Development
| Tool | Version | Purpose |
|------|---------|---------|
| Docker Desktop | 24+ | Build and run containers |
| Docker Compose | v2+ | Local multi-container orchestration |
| Java JDK | 8+ | Local development (optional) |
| Maven | 3.9+ | Local builds (optional) |

### AWS Deployment
| Tool | Version | Purpose |
|------|---------|---------|
| AWS CLI | v2 | Interact with AWS services |
| Docker | 24+ | Build and push images |
| Python 3 | 3.8+ | Used by deploy-image.sh for JSON manipulation |

---

## Project Structure

```
BookingComp/
├── Dockerfile                    # Multi-stage Docker build
├── docker-compose.yml            # Local development compose file
├── .dockerignore                 # Files excluded from Docker context
├── pom.xml                       # Maven build descriptor
├── src/
│   └── main/
│       ├── java/com/demo/resortslite/
│       │   ├── ResortsLiteApplication.java
│       │   ├── BookingController.java
│       │   ├── BookingService.java
│       │   └── ReportService.java
│       └── resources/
│           └── application.properties
├── ecs/
│   ├── task-definition.json      # ECS Fargate task definition
│   └── service-definition.json   # ECS service definition
├── scripts/
│   ├── build-push.sh             # Linux/macOS build & push
│   ├── build-push.bat            # Windows build & push
│   ├── deploy-image.sh           # Linux/macOS ECS deployment
│   └── deploy-image.bat          # Windows ECS deployment
└── docs/
    └── DEPLOYMENT.md             # This file
```

---

## Local Development with Docker Compose

### Quick Start

```bash
# 1. Clone / navigate to the project root
cd BookingComp

# 2. (Optional) Override environment variables
cp .env.example .env   # edit as needed

# 3. Start the application
docker compose up --build

# 4. Verify the application is running
curl http://localhost:8080/actuator/health

# 5. Stop the application
docker compose down
```

### Environment Variables (docker-compose.yml)

| Variable | Default | Description |
|----------|---------|-------------|
| `SPRING_PROFILES_ACTIVE` | `docker` | Active Spring profile |
| `SERVER_PORT` | `8080` | Application HTTP port |
| `REDIS_HOST` | `localhost` | Redis hostname |
| `REDIS_PORT` | `6379` | Redis port |
| `PAYMENT_API_URL` | `http://payment-service:9090/payments/charge` | Payment service URL |
| `REPORT_BASE_PATH` | `/tmp/reports` | Report output directory |
| `BACKUP_PATH` | `/tmp/backups` | Backup directory |
| `JAVA_OPTS` | `-Xms256m -Xmx512m ...` | JVM tuning flags |

### Available Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/bookings/create` | POST | Create a new booking |
| `/api/bookings/status/{id}` | GET | Get booking status |
| `/api/bookings/availability` | GET | Check room availability |
| `/api/bookings/report/download` | GET | Download booking report |
| `/actuator/health` | GET | Health check |
| `/actuator/info` | GET | Application info |
| `/h2-console` | GET | H2 in-memory DB console |

---

## Build and Push Docker Image

### Linux / macOS

```bash
chmod +x scripts/build-push.sh
./scripts/build-push.sh
```

### Windows

```cmd
scripts\build-push.bat
```

### Script Prompts

1. **Image tag** – defaults to `latest`
2. **Registry type** – `1` for AWS ECR, `2` for Docker Hub
3. **Registry details** – region/account (ECR) or username/password (Docker Hub)

### Manual Build (ECR example)

```bash
# Authenticate
aws ecr get-login-password --region us-east-1 | \
  docker login --username AWS --password-stdin \
  123456789.dkr.ecr.us-east-1.amazonaws.com

# Build
docker build -t 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest .

# Push
docker push 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest
```

---

## AWS ECS Fargate Prerequisites

### 1. IAM Roles

#### ECS Task Execution Role (`ecsTaskExecutionRole`)
Required for ECS to pull images from ECR and write logs to CloudWatch.

```bash
# Create the role
aws iam create-role \
  --role-name ecsTaskExecutionRole \
  --assume-role-policy-document '{
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Principal": {"Service": "ecs-tasks.amazonaws.com"},
      "Action": "sts:AssumeRole"
    }]
  }'

# Attach the managed policy
aws iam attach-role-policy \
  --role-name ecsTaskExecutionRole \
  --policy-arn arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy
```

#### ECS Task Role (`ecsTaskRole`)
Optional – grants the application container permissions to call AWS services.

```bash
aws iam create-role \
  --role-name ecsTaskRole \
  --assume-role-policy-document '{
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Principal": {"Service": "ecs-tasks.amazonaws.com"},
      "Action": "sts:AssumeRole"
    }]
  }'
```

### 2. VPC and Networking

```bash
# List available VPCs
aws ec2 describe-vpcs --query "Vpcs[*].{ID:VpcId,CIDR:CidrBlock}" --output table

# List subnets (choose at least 2 in different AZs)
aws ec2 describe-subnets \
  --filters "Name=vpc-id,Values=vpc-xxxxxxxx" \
  --query "Subnets[*].{ID:SubnetId,AZ:AvailabilityZone,CIDR:CidrBlock}" \
  --output table
```

### 3. Security Group

```bash
# Create security group
aws ec2 create-security-group \
  --group-name resortslite-sg \
  --description "ResortsLite ECS security group" \
  --vpc-id vpc-xxxxxxxx

# Allow inbound HTTP on port 8080
aws ec2 authorize-security-group-ingress \
  --group-id sg-xxxxxxxx \
  --protocol tcp \
  --port 8080 \
  --cidr 0.0.0.0/0

# Allow inbound HTTP on port 80 (if using ALB)
aws ec2 authorize-security-group-ingress \
  --group-id sg-xxxxxxxx \
  --protocol tcp \
  --port 80 \
  --cidr 0.0.0.0/0
```

### 4. CloudWatch Log Group

```bash
aws logs create-log-group \
  --log-group-name /ecs/resortslite \
  --region us-east-1

# Set retention (optional)
aws logs put-retention-policy \
  --log-group-name /ecs/resortslite \
  --retention-in-days 30
```

### 5. ECR Repository

```bash
aws ecr create-repository \
  --repository-name resortslite \
  --region us-east-1
```

---

## ECS Task Definition Explained

File: `ecs/task-definition.json`

| Field | Value | Notes |
|-------|-------|-------|
| `family` | `resortslite-task` | Task definition family name |
| `requiresCompatibilities` | `["FARGATE"]` | Fargate launch type |
| `networkMode` | `awsvpc` | Required for Fargate |
| `cpu` | `"512"` | 0.5 vCPU |
| `memory` | `"1024"` | 1 GB RAM |
| `executionRoleArn` | `ecsTaskExecutionRole` | ECR pull + CloudWatch logs |
| `taskRoleArn` | `ecsTaskRole` | Application AWS permissions |

### Valid Fargate CPU/Memory Combinations

| CPU | Memory Options |
|-----|---------------|
| 256 (.25 vCPU) | 512, 1024, 2048 MB |
| **512 (.5 vCPU)** | **1024**, 2048, 3072, 4096 MB |
| 1024 (1 vCPU) | 2048–8192 MB |
| 2048 (2 vCPU) | 4096–16384 MB |
| 4096 (4 vCPU) | 8192–30720 MB |

### Container Definition Key Fields

```json
{
  "name": "resortslite",
  "image": "{{IMAGE_URI}}",
  "essential": true,
  "portMappings": [{"containerPort": 8080, "protocol": "tcp"}],
  "logConfiguration": {
    "logDriver": "awslogs",
    "options": {
      "awslogs-group": "/ecs/resortslite",
      "awslogs-region": "us-east-1",
      "awslogs-stream-prefix": "ecs"
    }
  }
}
```

---

## ECS Service Configuration

File: `ecs/service-definition.json`

| Field | Value | Notes |
|-------|-------|-------|
| `serviceName` | `resortslite-service` | ECS service name |
| `launchType` | `FARGATE` | Serverless compute |
| `desiredCount` | `2` | Two tasks for HA |
| `networkMode` | `awsvpc` | Each task gets its own ENI |
| `assignPublicIp` | `ENABLED` | Required for public ECR access |
| `maximumPercent` | `200` | Rolling deploy: up to 4 tasks |
| `minimumHealthyPercent` | `50` | At least 1 task during deploy |

---

## ECS Fargate Deployment Walkthrough

### Step 1 – Build and push the image

```bash
./scripts/build-push.sh
# Note the full image URI output, e.g.:
# 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest
```

### Step 2 – Run the deployment script

```bash
chmod +x scripts/deploy-image.sh
./scripts/deploy-image.sh
```

Provide the following when prompted:
- AWS Region (e.g. `us-east-1`)
- ECS Cluster name (e.g. `resortslite-cluster`)
- ECR Image URI (from Step 1)
- Subnet 1 ID
- Subnet 2 ID
- Security Group ID
- Load balancer preference (y/n)

### Step 3 – Verify deployment

```bash
# Check service status
aws ecs describe-services \
  --cluster resortslite-cluster \
  --services resortslite-service \
  --region us-east-1

# List running tasks
aws ecs list-tasks \
  --cluster resortslite-cluster \
  --region us-east-1

# View logs
aws logs tail /ecs/resortslite --follow --region us-east-1
```

### Step 4 – Test the application

```bash
# If using ALB
curl http://<ALB_DNS>/actuator/health

# If using task public IP (development only)
TASK_ARN=$(aws ecs list-tasks --cluster resortslite-cluster --query "taskArns[0]" --output text)
TASK_IP=$(aws ecs describe-tasks --cluster resortslite-cluster --tasks $TASK_ARN \
  --query "tasks[0].attachments[0].details[?name=='privateIPv4Address'].value" --output text)
curl http://$TASK_IP:8080/actuator/health
```

---

## ECS-Specific Troubleshooting

### Task fails to start

```bash
# Check stopped task reason
aws ecs describe-tasks \
  --cluster resortslite-cluster \
  --tasks <TASK_ARN> \
  --query "tasks[0].{Status:lastStatus,StopReason:stoppedReason,Containers:containers[*].{Name:name,Reason:reason,ExitCode:exitCode}}" \
  --output json
```

Common causes:
- **ImagePullBackOff**: ECR permissions missing on `ecsTaskExecutionRole`
- **ResourceInitializationError**: Fargate agent cannot reach ECR/S3 endpoints – check VPC routing
- **OutOfMemoryError**: Increase `memory` in task definition (use valid Fargate combination)
- **Port conflict**: Ensure `containerPort` matches `SERVER_PORT` env var

### Service not reaching desired count

```bash
# Check service events
aws ecs describe-services \
  --cluster resortslite-cluster \
  --services resortslite-service \
  --query "services[0].events[:10]" \
  --output table
```

### Application health check failing

```bash
# Check actuator health directly
curl -v http://<TASK_IP>:8080/actuator/health

# Check if Redis is reachable (Spring Session dependency)
# Ensure REDIS_HOST env var points to a reachable ElastiCache endpoint
```

### CloudWatch logs not appearing

```bash
# Verify log group exists
aws logs describe-log-groups --log-group-name-prefix /ecs/resortslite

# Check execution role has CloudWatch permissions
aws iam simulate-principal-policy \
  --policy-source-arn arn:aws:iam::ACCOUNT_ID:role/ecsTaskExecutionRole \
  --action-names logs:CreateLogStream logs:PutLogEvents \
  --resource-arns "arn:aws:logs:us-east-1:ACCOUNT_ID:log-group:/ecs/resortslite:*"
```

### Invalid CPU/Memory combination

Ensure you use only valid Fargate combinations. The default (`cpu: "512"`, `memory: "1024"`) is always valid.

---

## ECS Fargate Scaling and Management

### Manual scaling

```bash
aws ecs update-service \
  --cluster resortslite-cluster \
  --service resortslite-service \
  --desired-count 4 \
  --region us-east-1
```

### Auto Scaling

```bash
# Register scalable target
aws application-autoscaling register-scalable-target \
  --service-namespace ecs \
  --resource-id service/resortslite-cluster/resortslite-service \
  --scalable-dimension ecs:service:DesiredCount \
  --min-capacity 2 \
  --max-capacity 10

# CPU-based scaling policy
aws application-autoscaling put-scaling-policy \
  --service-namespace ecs \
  --resource-id service/resortslite-cluster/resortslite-service \
  --scalable-dimension ecs:service:DesiredCount \
  --policy-name resortslite-cpu-scaling \
  --policy-type TargetTrackingScaling \
  --target-tracking-scaling-policy-configuration '{
    "TargetValue": 70.0,
    "PredefinedMetricSpecification": {
      "PredefinedMetricType": "ECSServiceAverageCPUUtilization"
    },
    "ScaleInCooldown": 300,
    "ScaleOutCooldown": 60
  }'
```

### Blue/Green Deployment with CodeDeploy

1. Create a CodeDeploy application and deployment group targeting the ECS service
2. Configure two target groups (blue and green) on the ALB
3. Use `aws deploy create-deployment` to trigger blue/green deployments
4. CodeDeploy shifts traffic gradually and rolls back on health check failures

### Force new deployment (rolling update)

```bash
aws ecs update-service \
  --cluster resortslite-cluster \
  --service resortslite-service \
  --force-new-deployment \
  --region us-east-1
```

---

## Configuration Management

### Environment Variables via ECS Task Definition

Update the `environment` array in `ecs/task-definition.json` and re-register the task definition:

```bash
aws ecs register-task-definition \
  --cli-input-json file://ecs/task-definition.json \
  --region us-east-1
```

### Secrets via AWS Secrets Manager

For sensitive values (DB passwords, API keys), use `secrets` instead of `environment`:

```json
"secrets": [
  {
    "name": "DB_PASSWORD",
    "valueFrom": "arn:aws:secretsmanager:us-east-1:ACCOUNT_ID:secret:resortslite/db-password"
  }
]
```

Grant the execution role access:

```bash
aws iam attach-role-policy \
  --role-name ecsTaskExecutionRole \
  --policy-arn arn:aws:iam::aws:policy/SecretsManagerReadWrite
```

### Spring Profiles

The application uses `SPRING_PROFILES_ACTIVE=docker` in containers. Create `application-docker.properties` for Docker-specific overrides:

```properties
# src/main/resources/application-docker.properties
spring.redis.host=${REDIS_HOST:localhost}
spring.redis.port=${REDIS_PORT:6379}
logging.level.com.demo.resortslite=INFO
```

---

## Security Considerations

1. **Non-root container user**: The Dockerfile creates and uses `appuser` (non-root)
2. **No secrets in images**: All credentials are injected via environment variables or Secrets Manager
3. **Security groups**: Restrict inbound traffic to only required ports (8080 from ALB, not 0.0.0.0/0)
4. **ECR image scanning**: Enable ECR image scanning on push:
   ```bash
   aws ecr put-image-scanning-configuration \
     --repository-name resortslite \
     --image-scanning-configuration scanOnPush=true
   ```
5. **VPC endpoints**: Use VPC endpoints for ECR and CloudWatch to avoid public internet traffic
6. **Task role least privilege**: Grant `ecsTaskRole` only the permissions the application needs
7. **Log4j vulnerability**: The pom.xml includes log4j-core 2.14.1 (CVE-2021-44228). **Upgrade to 2.17.1+ immediately**
8. **Commons Collections**: Version 3.2.1 has CVE-2015-6420. **Upgrade to 3.2.2+**
9. **SQL injection**: BookingService uses string concatenation for SQL. **Migrate to parameterised queries**

---

## Java-Specific Notes

### JVM Memory Configuration

The container is configured with:
```
-Xms256m -Xmx512m
-XX:+UseContainerSupport        # Respect container memory limits
-XX:MaxRAMPercentage=75.0       # Use 75% of container memory as max heap
-XX:+UseG1GC                    # G1 garbage collector (recommended for containers)
-Djava.security.egd=file:/dev/./urandom  # Faster SecureRandom
```

With `memory: "1024"` (1 GB), the JVM will use up to ~768 MB for heap.

### Spring Boot Actuator

Health and info endpoints are exposed:
- `GET /actuator/health` – liveness/readiness probe
- `GET /actuator/info` – application metadata

### Spring Session + Redis

The application uses Spring Session with Redis for distributed session storage. Ensure:
- `REDIS_HOST` points to your Amazon ElastiCache Redis endpoint
- `REDIS_PORT` is set (default: 6379)
- The ECS security group allows outbound traffic to the ElastiCache security group on port 6379

### Graceful Shutdown

The container uses `exec java $JAVA_OPTS -jar /app/app.jar` as the entrypoint, ensuring the JVM receives `SIGTERM` directly (PID 1) for graceful shutdown. Spring Boot 2.7.x supports graceful shutdown via:

```properties
server.shutdown=graceful
spring.lifecycle.timeout-per-shutdown-phase=30s
```

### H2 Console

The H2 in-memory console is enabled at `/h2-console`. **Disable this in production** by setting:
```properties
spring.h2.console.enabled=false
```
or by using a production Spring profile that overrides this setting.
