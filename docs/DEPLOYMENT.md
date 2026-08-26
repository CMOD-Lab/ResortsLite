# BookingComp — Deployment Guide

## Overview

**Application**: BookingComp (ResortsLite)  
**Framework**: Spring Boot 2.7.x  
**Java Version**: 8  
**Build Tool**: Maven  
**Target Platform**: AWS EKS (Elastic Kubernetes Service)  
**Container Registry**: AWS ECR or Docker Hub  

---

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Project Structure](#project-structure)
3. [Local Development with Docker Compose](#local-development-with-docker-compose)
4. [Build and Push Docker Image](#build-and-push-docker-image)
5. [AWS EKS Prerequisites](#aws-eks-prerequisites)
6. [EKS Cluster Setup](#eks-cluster-setup)
7. [Kubernetes Deployment Walkthrough](#kubernetes-deployment-walkthrough)
8. [Deploy with Script](#deploy-with-script)
9. [Configuration Management](#configuration-management)
10. [Scaling and Management](#scaling-and-management)
11. [Troubleshooting](#troubleshooting)
12. [Security Considerations](#security-considerations)
13. [Java-Specific Notes](#java-specific-notes)

---

## Prerequisites

### Local Development Tools
| Tool | Version | Purpose |
|------|---------|---------|
| Docker | 20.10+ | Build and run containers |
| Docker Compose | 2.x | Local multi-container orchestration |
| Java JDK | 8 | Local build (optional) |
| Maven | 3.8+ | Local build (optional) |

### AWS EKS Deployment Tools
| Tool | Version | Purpose |
|------|---------|---------|
| AWS CLI | 2.x | AWS authentication and ECR access |
| kubectl | 1.27+ | Kubernetes cluster management |
| eksctl | 0.150+ | EKS cluster creation (optional) |

### AWS IAM Permissions Required
- `ecr:GetAuthorizationToken`
- `ecr:BatchCheckLayerAvailability`
- `ecr:PutImage`
- `ecr:InitiateLayerUpload`
- `ecr:UploadLayerPart`
- `ecr:CompleteLayerUpload`
- `ecr:CreateRepository`
- `eks:DescribeCluster`
- `eks:UpdateKubeconfig`

---

## Project Structure

```
BookingComp/
├── Dockerfile                  # Multi-stage Docker build (Java 8 / amazoncorretto:8)
├── docker-compose.yml          # Local development compose file
├── .dockerignore               # Files excluded from Docker build context
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
├── kubernetes/
│   ├── namespace.yaml          # Kubernetes namespace
│   ├── deployment.yaml         # Application deployment (2 replicas)
│   ├── service.yaml            # ClusterIP service
│   └── ingress.yaml            # AWS ALB ingress
├── scripts/
│   ├── build-push.sh           # Linux/macOS build & push script
│   ├── build-push.bat          # Windows build & push script
│   ├── deploy-image.sh         # Linux/macOS EKS deploy script
│   └── deploy-image.bat        # Windows EKS deploy script
└── docs/
    └── DEPLOYMENT.md           # This file
```

---

## Local Development with Docker Compose

### 1. Configure Environment Variables

Create a `.env` file in the project root (never commit this file):

```bash
# Redis connection (use a local Redis or ElastiCache endpoint)
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=

# Path configuration
REPORT_BASE_PATH=/var/reports
BACKUP_PATH=/var/backups/nightly

# External service endpoints
PAYMENT_API_URL=http://payment-service/payments/charge
```

> **Note**: You will need a Redis instance available. For local development, you can run Redis separately:
> ```bash
> docker run -d --name redis -p 6379:6379 redis:7-alpine
> ```

### 2. Build and Start the Application

```bash
# Build and start
docker compose up --build

# Run in background
docker compose up --build -d

# View logs
docker compose logs -f bookingcomp

# Stop
docker compose down
```

### 3. Verify the Application

```bash
# Health check
curl http://localhost:8080/actuator/health

# Create a booking
curl -X POST "http://localhost:8080/api/bookings/create?guestName=John&roomType=SUITE&checkIn=2024-06-01&checkOut=2024-06-05"

# Check booking status
curl http://localhost:8080/api/bookings/status/BK-XXXXXXXX

# Check room availability
curl "http://localhost:8080/api/bookings/availability?roomType=DELUXE"
```

---

## Build and Push Docker Image

### Linux / macOS

```bash
# Make the script executable
chmod +x scripts/build-push.sh

# Run from project root
./scripts/build-push.sh
```

The script will prompt you to:
1. Select registry type (AWS ECR or Docker Hub)
2. Enter registry credentials / AWS details
3. Enter an image tag (defaults to `latest`)

### Windows

```cmd
scripts\build-push.bat
```

### Manual Build (without script)

```bash
# Build
docker build -t bookingcomp:latest .

# Tag for ECR
docker tag bookingcomp:latest <ACCOUNT_ID>.dkr.ecr.<REGION>.amazonaws.com/bookingcomp:latest

# Login to ECR
aws ecr get-login-password --region <REGION> | \
  docker login --username AWS --password-stdin <ACCOUNT_ID>.dkr.ecr.<REGION>.amazonaws.com

# Push
docker push <ACCOUNT_ID>.dkr.ecr.<REGION>.amazonaws.com/bookingcomp:latest
```

---

## AWS EKS Prerequisites

### 1. Configure AWS CLI

```bash
aws configure
# Enter: AWS Access Key ID, Secret Access Key, Region, Output format
```

### 2. Verify AWS Identity

```bash
aws sts get-caller-identity
```

### 3. Install kubectl

```bash
# macOS
brew install kubectl

# Linux
curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
chmod +x kubectl && sudo mv kubectl /usr/local/bin/

# Windows (via Chocolatey)
choco install kubernetes-cli
```

### 4. Install AWS Load Balancer Controller (required for Ingress)

The AWS Load Balancer Controller must be installed in your EKS cluster to handle ALB Ingress resources.

```bash
# Add the EKS chart repo
helm repo add eks https://aws.github.io/eks-charts
helm repo update

# Install the controller (replace <CLUSTER_NAME> and <REGION>)
helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system \
  --set clusterName=<CLUSTER_NAME> \
  --set serviceAccount.create=false \
  --set serviceAccount.name=aws-load-balancer-controller
```

---

## EKS Cluster Setup

### Option A: Use Existing Cluster

```bash
# Configure kubectl
aws eks update-kubeconfig --region <REGION> --name <CLUSTER_NAME>

# Verify
kubectl cluster-info
kubectl get nodes
```

### Option B: Create New Cluster with eksctl

```bash
eksctl create cluster \
  --name bookingcomp-cluster \
  --region us-east-1 \
  --nodegroup-name standard-workers \
  --node-type t3.medium \
  --nodes 2 \
  --nodes-min 1 \
  --nodes-max 4 \
  --managed
```

---

## Kubernetes Deployment Walkthrough

### Manifest Descriptions

| File | Kind | Description |
|------|------|-------------|
| `kubernetes/namespace.yaml` | Namespace | Isolates BookingComp resources in `bookingcomp` namespace |
| `kubernetes/deployment.yaml` | Deployment | Runs 2 replicas with liveness/readiness probes on `/actuator/health` |
| `kubernetes/service.yaml` | Service (ClusterIP) | Internal service exposing port 80 → container port 8080 |
| `kubernetes/ingress.yaml` | Ingress (ALB) | Internet-facing AWS ALB routing traffic to the service |

### Environment Variable Placeholders in deployment.yaml

| Placeholder | Description | Example Value |
|-------------|-------------|---------------|
| `{{IMAGE_URI}}` | Full Docker image URI with tag | `123456789.dkr.ecr.us-east-1.amazonaws.com/bookingcomp:latest` |
| `{{REDIS_HOST}}` | Redis / ElastiCache endpoint | `my-cluster.abc123.ng.0001.use1.cache.amazonaws.com` |
| `{{REDIS_PORT}}` | Redis port | `6379` |
| `{{REDIS_PASSWORD}}` | Redis auth token | (from Secrets Manager) |
| `{{REPORT_BASE_PATH}}` | Report file base path | `/var/reports` |
| `{{BACKUP_PATH}}` | Backup directory path | `/var/backups/nightly` |
| `{{PAYMENT_API_URL}}` | Payment service URL | `http://payment-service.payments.svc.cluster.local/payments/charge` |

### Manual Deployment Steps

```bash
# 1. Apply namespace
kubectl apply -f kubernetes/namespace.yaml

# 2. Replace placeholders and apply deployment
sed -i 's|{{IMAGE_URI}}|<YOUR_IMAGE_URI>|g' kubernetes/deployment.yaml
sed -i 's|{{REDIS_HOST}}|<YOUR_REDIS_HOST>|g' kubernetes/deployment.yaml
sed -i 's|{{REDIS_PORT}}|6379|g' kubernetes/deployment.yaml
sed -i 's|{{REDIS_PASSWORD}}|<YOUR_REDIS_PASSWORD>|g' kubernetes/deployment.yaml
sed -i 's|{{REPORT_BASE_PATH}}|/var/reports|g' kubernetes/deployment.yaml
sed -i 's|{{BACKUP_PATH}}|/var/backups/nightly|g' kubernetes/deployment.yaml
sed -i 's|{{PAYMENT_API_URL}}|http://payment-service/payments/charge|g' kubernetes/deployment.yaml
kubectl apply -f kubernetes/deployment.yaml

# 3. Apply service
kubectl apply -f kubernetes/service.yaml

# 4. Apply ingress
kubectl apply -f kubernetes/ingress.yaml

# 5. Wait for rollout
kubectl rollout status deployment/bookingcomp -n bookingcomp

# 6. Verify
kubectl get pods,svc,ingress -n bookingcomp
```

---

## Deploy with Script

### Linux / macOS

```bash
chmod +x scripts/deploy-image.sh
./scripts/deploy-image.sh
```

### Windows

```cmd
scripts\deploy-image.bat
```

The script will prompt for:
- AWS region
- EKS cluster name
- Docker image URI
- Redis connection details (REDIS_HOST, REDIS_PORT, REDIS_PASSWORD)
- Path configuration (REPORT_BASE_PATH, BACKUP_PATH)
- Payment API URL (PAYMENT_API_URL)

---

## Configuration Management

### Using Kubernetes ConfigMap (Recommended)

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: bookingcomp-config
  namespace: bookingcomp
data:
  REDIS_HOST: "my-cluster.abc123.ng.0001.use1.cache.amazonaws.com"
  REDIS_PORT: "6379"
  REPORT_BASE_PATH: "/var/reports"
  BACKUP_PATH: "/var/backups/nightly"
  PAYMENT_API_URL: "http://payment-service.payments.svc.cluster.local/payments/charge"
```

### Using Kubernetes Secret (for sensitive values)

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: bookingcomp-secrets
  namespace: bookingcomp
type: Opaque
stringData:
  REDIS_PASSWORD: "your-redis-auth-token"
```

### Using AWS Secrets Manager with External Secrets Operator

For production, use the [External Secrets Operator](https://external-secrets.io/) to sync AWS Secrets Manager values into Kubernetes Secrets automatically.

---

## Scaling and Management

### Horizontal Pod Autoscaler

```bash
kubectl autoscale deployment bookingcomp \
  --namespace bookingcomp \
  --cpu-percent=70 \
  --min=2 \
  --max=10
```

### Manual Scaling

```bash
kubectl scale deployment bookingcomp --replicas=4 -n bookingcomp
```

### Rolling Update

```bash
# Update image
kubectl set image deployment/bookingcomp \
  bookingcomp=<NEW_IMAGE_URI> \
  -n bookingcomp

# Monitor rollout
kubectl rollout status deployment/bookingcomp -n bookingcomp
```

### Rollback

```bash
# Rollback to previous version
kubectl rollout undo deployment/bookingcomp -n bookingcomp

# Rollback to specific revision
kubectl rollout history deployment/bookingcomp -n bookingcomp
kubectl rollout undo deployment/bookingcomp --to-revision=2 -n bookingcomp
```

---

## Troubleshooting

### Pod Not Starting

```bash
# Check pod status
kubectl get pods -n bookingcomp

# Describe pod for events
kubectl describe pod <POD_NAME> -n bookingcomp

# View pod logs
kubectl logs <POD_NAME> -n bookingcomp

# View previous container logs (if crashed)
kubectl logs <POD_NAME> -n bookingcomp --previous
```

### Common Issues

#### CrashLoopBackOff
- **Cause**: Application failing to start (Redis unreachable, missing env vars)
- **Fix**: Check logs with `kubectl logs <POD_NAME> -n bookingcomp`
- Verify `REDIS_HOST` is reachable from within the cluster

#### ImagePullBackOff
- **Cause**: Cannot pull Docker image from registry
- **Fix**: Verify ECR permissions and that the image URI is correct
- Check: `kubectl describe pod <POD_NAME> -n bookingcomp`

#### Readiness Probe Failing
- **Cause**: `/actuator/health` returning non-200 or Redis health check failing
- **Fix**: Verify Redis connectivity; check `management.health.redis.enabled` setting
- Temporarily disable Redis health: set `management.health.redis.enabled=false`

#### Ingress Not Getting ALB Hostname
- **Cause**: AWS Load Balancer Controller not installed or IAM permissions missing
- **Fix**: Verify controller is running: `kubectl get pods -n kube-system | grep aws-load-balancer`

### Service Connectivity

```bash
# Test service from within cluster
kubectl run test-pod --image=busybox --rm -it --restart=Never -n bookingcomp -- \
  wget -qO- http://bookingcomp-service/actuator/health

# Port-forward for local testing
kubectl port-forward svc/bookingcomp-service 8080:80 -n bookingcomp
curl http://localhost:8080/actuator/health
```

---

## Security Considerations

1. **Non-root container**: The application runs as `appuser` (UID 1000) — never as root
2. **Secrets management**: Use Kubernetes Secrets or AWS Secrets Manager for Redis passwords and API keys — never hardcode credentials
3. **Network policies**: Consider adding Kubernetes NetworkPolicy to restrict pod-to-pod communication
4. **Image scanning**: Enable ECR image scanning to detect vulnerabilities in the container image
5. **RBAC**: Apply least-privilege IAM roles for EKS node groups and service accounts
6. **TLS**: Configure HTTPS on the ALB Ingress using AWS Certificate Manager (ACM):
   ```yaml
   annotations:
     alb.ingress.kubernetes.io/certificate-arn: arn:aws:acm:<REGION>:<ACCOUNT>:certificate/<CERT_ID>
     alb.ingress.kubernetes.io/listen-ports: '[{"HTTPS": 443}]'
   ```
7. **Dependency vulnerabilities**: The pom.xml includes `log4j-core:2.14.1` (CVE-2021-44228) and `commons-collections:3.2.1` (CVE-2015-6420) — **upgrade these before production deployment**

---

## Java-Specific Notes

### JVM Memory Configuration

The container is configured with:
```
-Xms256m -Xmx512m
-XX:+UseContainerSupport
-XX:MaxRAMPercentage=75.0
```

With a memory limit of `1Gi`, the JVM will use up to ~768 MB of heap. Adjust `JAVA_OPTS` in the deployment if needed.

### Spring Boot Actuator

Health endpoints are exposed at:
- **Liveness**: `GET /actuator/health` → used by Kubernetes liveness probe
- **Readiness**: `GET /actuator/health` → used by Kubernetes readiness probe

The health endpoint includes Redis health by default (`management.health.redis.enabled=true`). If Redis is unavailable, the pod will be marked as not ready.

### Spring Session with Redis

The application uses Spring Session backed by Redis (ElastiCache) for distributed session management. Ensure:
- ElastiCache Redis cluster is accessible from EKS worker nodes
- Security groups allow inbound traffic on port 6379 from EKS node security group
- `REDIS_HOST`, `REDIS_PORT`, and `REDIS_PASSWORD` are correctly configured

### H2 In-Memory Database

The application uses H2 in-memory database for local development. For production on EKS:
- Replace H2 with a persistent database (Amazon RDS)
- Update `spring.datasource.url`, `spring.datasource.username`, `spring.datasource.password`
- Add the appropriate JDBC driver dependency to `pom.xml`

### Startup Time

Java 8 Spring Boot applications typically take 15–30 seconds to start. The Kubernetes probes are configured with:
- `initialDelaySeconds: 60` for liveness (allows JVM warm-up)
- `initialDelaySeconds: 30` for readiness

Adjust these values based on observed startup times in your environment.
