# BookingComp – AWS ECS Fargate Deployment Guide

> **Application**: ResortsLite BookingComp  
> **Framework**: Spring Boot 2.7.18 / Java 8  
> **Target Platform**: AWS ECS Fargate  
> **Build Tool**: Maven  

---

## Table of Contents

1. [Prerequisites](#1-prerequisites)
2. [Project Structure](#2-project-structure)
3. [Local Development with Docker Compose](#3-local-development-with-docker-compose)
4. [Build and Push Docker Image](#4-build-and-push-docker-image)
5. [AWS ECS Fargate Prerequisites](#5-aws-ecs-fargate-prerequisites)
6. [ECS Task Definition Explained](#6-ecs-task-definition-explained)
7. [ECS Service Configuration](#7-ecs-service-configuration)
8. [ECS Fargate Deployment Walkthrough](#8-ecs-fargate-deployment-walkthrough)
9. [Environment Variables Reference](#9-environment-variables-reference)
10. [ECS-Specific Troubleshooting](#10-ecs-specific-troubleshooting)
11. [Scaling and Management](#11-scaling-and-management)
12. [Security Considerations](#12-security-considerations)
13. [Java / JVM Notes](#13-java--jvm-notes)

---

## 1. Prerequisites

### Local Machine

| Tool | Minimum Version | Purpose |
|------|----------------|---------|
| Docker Desktop | 24.x | Build and run containers |
| AWS CLI | 2.x | Interact with AWS services |
| Java JDK | 8 | Local development |
| Maven | 3.9.x | Local builds |

### AWS Account Requirements

- IAM user/role with permissions for: ECS, ECR, IAM, CloudWatch Logs, ELBv2, EC2 (VPC)
- VPC with at least **two public or private subnets** in different AZs
- Security group allowing inbound TCP on port **8080** (application) and **8081** (management)

---

## 2. Project Structure

```
BookingComp/
├── Dockerfile                  # Multi-stage build (Maven builder + JRE runtime)
├── docker-compose.yml          # Local development (app only)
├── .dockerignore               # Excludes target/, wrapper files, IDE artefacts
├── pom.xml                     # Maven build descriptor
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
│   ├── task-definition.json    # ECS Fargate task definition
│   └── service-definition.json # ECS service definition
├── scripts/
│   ├── build-push.sh           # Linux/macOS: build & push to ECR or Docker Hub
│   ├── build-push.bat          # Windows: build & push to ECR or Docker Hub
│   ├── deploy-image.sh         # Linux/macOS: deploy to ECS Fargate
│   └── deploy-image.bat        # Windows: deploy to ECS Fargate
└── docs/
    └── DEPLOYMENT.md           # This file
```

---

## 3. Local Development with Docker Compose

### 3.1 Configure Environment

Create a `.env` file in the project root (never commit this file):

```dotenv
REDIS_HOST=your-redis-host
REDIS_PORT=6379
PAYMENT_API_URL=http://payment-service:9090/payments/charge
```

### 3.2 Start the Application

```bash
# Build and start
docker compose up --build

# Start in background
docker compose up -d --build

# View logs
docker compose logs -f bookingcomp

# Stop
docker compose down
```

### 3.3 Verify

```bash
# Application health
curl http://localhost:8081/actuator/health

# Create a booking
curl -X POST "http://localhost:8080/api/bookings/create?guestName=John&roomType=SUITE&checkIn=2024-06-01&checkOut=2024-06-05"

# Check availability
curl "http://localhost:8080/api/bookings/availability?roomType=DELUXE"
```

---

## 4. Build and Push Docker Image

### 4.1 Linux / macOS

```bash
chmod +x scripts/build-push.sh
bash scripts/build-push.sh
```

The script will prompt you to:
1. Choose registry: **1) AWS ECR** or **2) Docker Hub**
2. Enter an image tag (defaults to `latest`)
3. Provide registry credentials

### 4.2 Windows

```cmd
scripts\build-push.bat
```

### 4.3 Manual Build (ECR example)

```bash
# Authenticate
aws ecr get-login-password --region us-east-1 | \
  docker login --username AWS --password-stdin \
  123456789012.dkr.ecr.us-east-1.amazonaws.com

# Create repository (first time only)
aws ecr create-repository --repository-name bookingcomp --region us-east-1

# Build
docker build -t 123456789012.dkr.ecr.us-east-1.amazonaws.com/bookingcomp:latest .

# Push
docker push 123456789012.dkr.ecr.us-east-1.amazonaws.com/bookingcomp:latest
```

---

## 5. AWS ECS Fargate Prerequisites

### 5.1 IAM Roles

#### ecsTaskExecutionRole (required)

Allows ECS to pull images from ECR and write logs to CloudWatch.

```bash
# Create the role
aws iam create-role \
  --role-name ecsTaskExecutionRole \
  --assume-role-policy-document '{
    "Version":"2012-10-17",
    "Statement":[{
      "Effect":"Allow",
      "Principal":{"Service":"ecs-tasks.amazonaws.com"},
      "Action":"sts:AssumeRole"
    }]
  }'

# Attach the managed policy
aws iam attach-role-policy \
  --role-name ecsTaskExecutionRole \
  --policy-arn arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy
```

#### ecsTaskRole (optional – for task-level AWS API access)

```bash
aws iam create-role \
  --role-name ecsTaskRole \
  --assume-role-policy-document '{
    "Version":"2012-10-17",
    "Statement":[{
      "Effect":"Allow",
      "Principal":{"Service":"ecs-tasks.amazonaws.com"},
      "Action":"sts:AssumeRole"
    }]
  }'
```

### 5.2 CloudWatch Log Group

```bash
aws logs create-log-group --log-group-name /ecs/bookingcomp --region us-east-1
```

### 5.3 Security Group

```bash
# Create security group
SG_ID=$(aws ec2 create-security-group \
  --group-name bookingcomp-sg \
  --description "BookingComp ECS security group" \
  --vpc-id vpc-xxxxxxxx \
  --query GroupId --output text)

# Allow application port
aws ec2 authorize-security-group-ingress \
  --group-id $SG_ID --protocol tcp --port 8080 --cidr 0.0.0.0/0

# Allow management port (restrict to internal CIDR in production)
aws ec2 authorize-security-group-ingress \
  --group-id $SG_ID --protocol tcp --port 8081 --cidr 10.0.0.0/8
```

---

## 6. ECS Task Definition Explained

File: `ecs/task-definition.json`

| Field | Value | Notes |
|-------|-------|-------|
| `family` | `bookingcomp-task` | Task definition family name |
| `requiresCompatibilities` | `["FARGATE"]` | Fargate launch type |
| `networkMode` | `awsvpc` | Required for Fargate |
| `cpu` | `"512"` | 0.5 vCPU |
| `memory` | `"1024"` | 1 GB RAM |
| `executionRoleArn` | `ecsTaskExecutionRole` | ECR pull + CloudWatch logs |
| `taskRoleArn` | `ecsTaskRole` | Task-level AWS API permissions |

### Container Definition Highlights

- **Image**: Replaced at deploy time via `{{IMAGE_URI}}` placeholder
- **Ports**: 8080 (app), 8081 (management/actuator)
- **Logging**: `awslogs` driver → `/ecs/bookingcomp` log group
- **JVM flags**: Container-aware heap sizing via `JAVA_OPTS`

### Valid Fargate CPU / Memory Combinations

| CPU | Valid Memory Options |
|-----|---------------------|
| 256 | 512, 1024, 2048 MB |
| **512** | **1024**, 2048, 3072, 4096 MB ← *used here* |
| 1024 | 2048–8192 MB |
| 2048 | 4096–16384 MB |
| 4096 | 8192–30720 MB |

---

## 7. ECS Service Configuration

File: `ecs/service-definition.json`

| Field | Value | Notes |
|-------|-------|-------|
| `serviceName` | `bookingcomp-service` | ECS service name |
| `launchType` | `FARGATE` | Serverless compute |
| `desiredCount` | `2` | Two tasks for HA |
| `networkMode` | `awsvpc` | Each task gets its own ENI |
| `assignPublicIp` | `ENABLED` | Required for public subnets |
| `maximumPercent` | `200` | Rolling deploy: up to 4 tasks |
| `minimumHealthyPercent` | `50` | At least 1 task always running |

---

## 8. ECS Fargate Deployment Walkthrough

### 8.1 Automated Deployment (Recommended)

**Linux / macOS:**
```bash
chmod +x scripts/deploy-image.sh
bash scripts/deploy-image.sh
```

**Windows:**
```cmd
scripts\deploy-image.bat
```

The script will:
1. Prompt for AWS region, cluster name, image URI, subnets, security group
2. Resolve your AWS Account ID automatically
3. Create the CloudWatch log group if absent
4. Create the ECS cluster if absent
5. Optionally create an Application Load Balancer and Target Group
6. Register the task definition
7. Create or update the ECS service
8. Wait for service stability
9. Print service status and access URLs

### 8.2 Manual Deployment

```bash
AWS_REGION="us-east-1"
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
IMAGE_URI="123456789012.dkr.ecr.us-east-1.amazonaws.com/bookingcomp:latest"

# Substitute placeholders
sed -e "s|{{ACCOUNT_ID}}|$ACCOUNT_ID|g" \
    -e "s|{{AWS_REGION}}|$AWS_REGION|g" \
    -e "s|{{IMAGE_URI}}|$IMAGE_URI|g" \
    ecs/task-definition.json > /tmp/task-def.json

# Register task definition
TASK_ARN=$(aws ecs register-task-definition \
  --cli-input-json file:///tmp/task-def.json \
  --region $AWS_REGION \
  --query "taskDefinition.taskDefinitionArn" --output text)

# Create service
sed -e "s|{{CLUSTER_NAME}}|my-cluster|g" \
    -e "s|{{SUBNET_1}}|subnet-aaa|g" \
    -e "s|{{SUBNET_2}}|subnet-bbb|g" \
    -e "s|{{SECURITY_GROUP}}|sg-xxx|g" \
    ecs/service-definition.json > /tmp/svc-def.json

aws ecs create-service \
  --cli-input-json file:///tmp/svc-def.json \
  --region $AWS_REGION
```

---

## 9. Environment Variables Reference

| Variable | Default | Description |
|----------|---------|-------------|
| `SPRING_PROFILES_ACTIVE` | `docker` | Active Spring profile |
| `SERVER_PORT` | `8080` | Application HTTP port |
| `MANAGEMENT_PORT` | `8081` | Actuator management port |
| `JAVA_OPTS` | See Dockerfile | JVM tuning flags |
| `REDIS_HOST` | `localhost` | Redis/ElastiCache hostname |
| `REDIS_PORT` | `6379` | Redis port |
| `PAYMENT_API_URL` | `http://payment-service:9090/...` | Payment service endpoint |
| `REPORT_BASE_PATH` | `/reports` | Report file storage path |
| `BACKUP_PATH` | `/backups/nightly` | Backup storage path |
| `BOOKING_CACHE_MAX_SIZE` | `500` | Max booking cache entries |
| `TZ` | `UTC` | Container timezone |

### Storing Secrets in AWS

For sensitive values (Redis auth token, DB credentials), use **AWS Secrets Manager** or **SSM Parameter Store** and reference them in the task definition:

```json
"secrets": [
  {
    "name": "REDIS_AUTH_TOKEN",
    "valueFrom": "arn:aws:secretsmanager:us-east-1:123456789012:secret:bookingcomp/redis-auth"
  }
]
```

---

## 10. ECS-Specific Troubleshooting

### Task Fails to Start

```bash
# List stopped tasks
aws ecs list-tasks --cluster my-cluster --desired-status STOPPED --region us-east-1

# Get failure reason
aws ecs describe-tasks \
  --cluster my-cluster \
  --tasks <TASK_ARN> \
  --region us-east-1 \
  --query "tasks[0].{Status:lastStatus,StopCode:stopCode,StopReason:stoppedReason}"
```

**Common causes:**
- `CannotPullContainerError` → ECR permissions missing on `ecsTaskExecutionRole`
- `ResourceInitializationError` → Fargate agent cannot reach ECR/S3 endpoints (check VPC endpoints or NAT Gateway)
- `OutOfMemoryError` in logs → Increase task memory or tune `JAVA_OPTS`

### Container Exits Immediately

```bash
# Tail CloudWatch logs
aws logs tail /ecs/bookingcomp --follow --region us-east-1
```

**Common causes:**
- Redis connection refused → Verify `REDIS_HOST` / `REDIS_PORT` and security group rules
- Port already in use → Ensure `SERVER_PORT` matches `containerPort` in task definition

### Network / Connectivity Issues

- Fargate tasks in **private subnets** require a **NAT Gateway** or **VPC Endpoints** for ECR, S3, CloudWatch
- Security group must allow **outbound** traffic on port 443 (HTTPS) for ECR pulls
- For inter-service communication, use **AWS Cloud Map** or **ECS Service Connect**

### CPU / Memory Errors

```
InvalidParameterException: Invalid CPU or Memory value
```

Ensure you use a valid Fargate combination. This deployment uses `cpu: "512"` + `memory: "1024"`.

---

## 11. Scaling and Management

### Manual Scaling

```bash
aws ecs update-service \
  --cluster my-cluster \
  --service bookingcomp-service \
  --desired-count 4 \
  --region us-east-1
```

### Auto Scaling

```bash
# Register scalable target
aws application-autoscaling register-scalable-target \
  --service-namespace ecs \
  --resource-id service/my-cluster/bookingcomp-service \
  --scalable-dimension ecs:service:DesiredCount \
  --min-capacity 2 \
  --max-capacity 10

# CPU-based scaling policy
aws application-autoscaling put-scaling-policy \
  --service-namespace ecs \
  --resource-id service/my-cluster/bookingcomp-service \
  --scalable-dimension ecs:service:DesiredCount \
  --policy-name bookingcomp-cpu-scaling \
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

1. Enable `deploymentController: { type: CODE_DEPLOY }` in the service definition
2. Create a CodeDeploy application and deployment group targeting the ECS service
3. Use `appspec.yaml` to define the blue/green traffic shifting strategy

### Rolling Update (default)

The current configuration uses ECS rolling updates:
- `maximumPercent: 200` → ECS can run up to 4 tasks during deployment
- `minimumHealthyPercent: 50` → At least 1 task always healthy

---

## 12. Security Considerations

1. **Non-root container user**: The Dockerfile creates and uses `appuser` (non-root)
2. **No secrets in images**: All credentials injected via environment variables or Secrets Manager
3. **Least-privilege IAM**: `ecsTaskRole` should only have permissions the application needs
4. **Private subnets**: Run Fargate tasks in private subnets with NAT Gateway for production
5. **Security group hardening**: Restrict port 8081 (actuator) to internal CIDR only
6. **Image scanning**: Enable ECR image scanning on push:
   ```bash
   aws ecr put-image-scanning-configuration \
     --repository-name bookingcomp \
     --image-scanning-configuration scanOnPush=true \
     --region us-east-1
   ```
7. **Dependency vulnerabilities**: The project currently includes `log4j-core:2.14.1` (CVE-2021-44228) and `commons-collections:3.2.1` (CVE-2015-6420). **Upgrade these before production deployment.**
8. **TLS termination**: Terminate TLS at the ALB (HTTPS listener with ACM certificate); keep container-to-ALB traffic on HTTP within the VPC

---

## 13. Java / JVM Notes

### JVM Flags Explained

| Flag | Purpose |
|------|---------|
| `-Xms256m` | Initial heap size |
| `-Xmx512m` | Maximum heap size |
| `-XX:+UseContainerSupport` | Respect cgroup memory limits (Java 8u191+) |
| `-XX:MaxRAMPercentage=75.0` | Use 75% of container RAM for heap |
| `-XX:+UseG1GC` | G1 garbage collector (good for low-latency) |
| `-Djava.security.egd=file:/dev/./urandom` | Faster SecureRandom initialisation |

### Spring Boot Actuator Endpoints

| Endpoint | URL | Purpose |
|----------|-----|---------|
| Health | `http://host:8081/actuator/health` | Liveness / readiness |
| Info | `http://host:8081/actuator/info` | Application metadata |

### Spring Session / Redis

The application uses Spring Session backed by Redis (ElastiCache). Ensure:
- `REDIS_HOST` points to your ElastiCache primary endpoint
- The ECS task security group allows outbound TCP on port 6379 to the Redis security group
- Redis security group allows inbound TCP on port 6379 from the ECS task security group

### H2 In-Memory Database

The application currently uses H2 for persistence. For production:
- Replace with Amazon RDS (PostgreSQL/MySQL)
- Update `spring.datasource.*` properties
- Store credentials in AWS Secrets Manager

### Upgrading Vulnerable Dependencies

```xml
<!-- Replace in pom.xml -->
<!-- log4j-core: upgrade to 2.17.2+ -->
<dependency>
    <groupId>org.apache.logging.log4j</groupId>
    <artifactId>log4j-core</artifactId>
    <version>2.17.2</version>
</dependency>

<!-- commons-collections: upgrade to 3.2.2+ -->
<dependency>
    <groupId>commons-collections</groupId>
    <artifactId>commons-collections</artifactId>
    <version>3.2.2</version>
</dependency>
```
