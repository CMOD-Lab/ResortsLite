# ResortsLite — Deployment Guide

## Overview

This guide covers building, containerizing, and deploying the **ResortsLite** Spring Boot application to **Azure Kubernetes Service (AKS)**.

- **Application**: ResortsLite Resort Booking API
- **Framework**: Spring Boot 2.7.18
- **Java Version**: Java 8
- **Build Tool**: Maven
- **Port**: 8080
- **Health Endpoint**: `/actuator/health`

---

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Project Structure](#project-structure)
3. [Local Development with Docker Compose](#local-development-with-docker-compose)
4. [Build and Push Docker Image](#build-and-push-docker-image)
5. [Azure AKS Prerequisites](#azure-aks-prerequisites)
6. [AKS Cluster Setup](#aks-cluster-setup)
7. [Kubernetes Deployment](#kubernetes-deployment)
8. [Environment Variables Reference](#environment-variables-reference)
9. [Troubleshooting](#troubleshooting)
10. [Scaling and Management](#scaling-and-management)
11. [Security Considerations](#security-considerations)

---

## Prerequisites

### Local Development
- Docker Desktop 24.x or later
- Docker Compose v2.x or later
- Java 8 JDK (for local builds)
- Maven 3.8.x or later

### Azure AKS Deployment
- Azure CLI (`az`) 2.50.0 or later
- `kubectl` 1.27 or later
- Azure subscription with AKS cluster provisioned
- Azure Container Registry (ACR) or Docker Hub account

### Install Azure CLI
```bash
# macOS
brew install azure-cli

# Ubuntu/Debian
curl -sL https://aka.ms/InstallAzureCLIDeb | sudo bash

# Windows
winget install Microsoft.AzureCLI
```

### Install kubectl
```bash
# macOS
brew install kubectl

# Ubuntu/Debian
sudo az aks install-cli

# Windows
az aks install-cli
```

---

## Project Structure

```
AzureBackend/
├── Dockerfile                    # Multi-stage Docker build
├── docker-compose.yml            # Local development compose file
├── .dockerignore                 # Docker build exclusions
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
├── kubernetes/
│   ├── namespace.yaml            # Kubernetes namespace
│   ├── deployment.yaml           # Application deployment
│   ├── service.yaml              # ClusterIP service
│   └── ingress.yaml              # Azure Application Gateway ingress
├── scripts/
│   ├── build-push.sh             # Linux/macOS build & push script
│   ├── build-push.bat            # Windows build & push script
│   ├── deploy-image.sh           # Linux/macOS AKS deploy script
│   └── deploy-image.bat          # Windows AKS deploy script
└── docs/
    └── DEPLOYMENT.md             # This file
```

---

## Local Development with Docker Compose

### 1. Configure Environment Variables

Create a `.env` file in the project root:

```env
# Redis (provide your Azure Cache for Redis or local Redis details)
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=

# Application paths
REPORT_BASE_PATH=/reports
BACKUP_PATH=/backups/nightly

# External service endpoints
PAYMENT_API_URL=http://payment-service/payments/charge
APP_PAYMENT_ENDPOINT=http://payment-svc.internal:9090/charge
APP_INVENTORY_ENDPOINT=http://inventory-svc.internal:8081/rooms
APP_NOTIFICATION_ENDPOINT=http://notify.internal:7070/send
```

> **Note**: The application requires a Redis instance for Spring Session and distributed caching. Provide a Redis connection via environment variables. Redis itself is NOT included in docker-compose.yml — supply it separately.

### 2. Build and Start the Application

```bash
# Build and start
docker-compose up --build

# Start in background
docker-compose up -d --build

# View logs
docker-compose logs -f resortsLite

# Stop
docker-compose down
```

### 3. Verify the Application

```bash
# Health check
curl http://localhost:8080/actuator/health

# Test booking endpoint
curl -X POST "http://localhost:8080/api/bookings/create?guestName=John&roomType=SUITE&checkIn=2024-06-01&checkOut=2024-06-05"

# Check availability
curl "http://localhost:8080/api/bookings/availability?roomType=SUITE"
```

---

## Build and Push Docker Image

### Linux / macOS

```bash
# Make script executable
chmod +x scripts/build-push.sh

# Run from repository root
./scripts/build-push.sh
```

The script will prompt you to:
1. Enter an image tag (default: `latest`)
2. Select registry type (ACR or Docker Hub)
3. Provide registry credentials

### Windows

```cmd
scripts\build-push.bat
```

### Manual Build (without script)

```bash
# Build image
docker build -t resortsLite:latest .

# Tag for ACR
docker tag resortsLite:latest <ACR_NAME>.azurecr.io/resortsLite:latest

# Push to ACR
az acr login --name <ACR_NAME>
docker push <ACR_NAME>.azurecr.io/resortsLite:latest
```

---

## Azure AKS Prerequisites

### 1. Login to Azure

```bash
az login
az account set --subscription "<YOUR_SUBSCRIPTION_ID>"
```

### 2. Create Azure Container Registry (if not existing)

```bash
az acr create \
  --resource-group <RESOURCE_GROUP> \
  --name <ACR_NAME> \
  --sku Basic

# Attach ACR to AKS cluster
az aks update \
  --resource-group <RESOURCE_GROUP> \
  --name <CLUSTER_NAME> \
  --attach-acr <ACR_NAME>
```

### 3. Create AKS Cluster (if not existing)

```bash
az aks create \
  --resource-group <RESOURCE_GROUP> \
  --name <CLUSTER_NAME> \
  --node-count 2 \
  --node-vm-size Standard_DS2_v2 \
  --enable-addons monitoring \
  --generate-ssh-keys

# Enable Application Gateway Ingress Controller (AGIC)
az aks enable-addons \
  --resource-group <RESOURCE_GROUP> \
  --name <CLUSTER_NAME> \
  --addons ingress-appgw \
  --appgw-name resortsLite-agw \
  --appgw-subnet-cidr "10.225.0.0/16"
```

---

## AKS Cluster Setup

### Configure kubectl

```bash
az aks get-credentials \
  --resource-group <RESOURCE_GROUP> \
  --name <CLUSTER_NAME> \
  --overwrite-existing

# Verify connection
kubectl cluster-info
kubectl get nodes
```

---

## Kubernetes Deployment

### Automated Deployment (Recommended)

#### Linux / macOS

```bash
chmod +x scripts/deploy-image.sh
./scripts/deploy-image.sh
```

#### Windows

```cmd
scripts\deploy-image.bat
```

The script will prompt for:
- Azure Resource Group and AKS Cluster name
- Full Docker image URI (e.g., `myregistry.azurecr.io/resortsLite:1.0.0`)
- All required environment variables (Redis, service endpoints, paths)

### Manual Deployment

#### 1. Apply Namespace

```bash
kubectl apply -f kubernetes/namespace.yaml
```

#### 2. Update Deployment Manifest

Edit `kubernetes/deployment.yaml` and replace all `{{PLACEHOLDER}}` values:

```bash
# Replace image URI
sed -i 's|{{IMAGE_URI}}|myregistry.azurecr.io/resortsLite:latest|g' kubernetes/deployment.yaml

# Replace Redis config
sed -i 's|{{REDIS_HOST}}|my-redis.redis.cache.windows.net|g' kubernetes/deployment.yaml
sed -i 's|{{REDIS_PORT}}|6379|g' kubernetes/deployment.yaml
sed -i 's|{{REDIS_PASSWORD}}|your-redis-password|g' kubernetes/deployment.yaml

# Replace other env vars
sed -i 's|{{REPORT_BASE_PATH}}|/reports|g' kubernetes/deployment.yaml
sed -i 's|{{BACKUP_PATH}}|/backups/nightly|g' kubernetes/deployment.yaml
sed -i 's|{{PAYMENT_API_URL}}|http://payment-service/payments/charge|g' kubernetes/deployment.yaml
```

#### 3. Apply All Manifests

```bash
kubectl apply -f kubernetes/namespace.yaml
kubectl apply -f kubernetes/deployment.yaml
kubectl apply -f kubernetes/service.yaml
kubectl apply -f kubernetes/ingress.yaml
```

#### 4. Verify Deployment

```bash
# Watch pod status
kubectl get pods -n resortsLite -w

# Check deployment rollout
kubectl rollout status deployment/resortsLite -n resortsLite

# View all resources
kubectl get pods,svc,ingress -n resortsLite
```

#### 5. Access the Application

```bash
# Get ingress IP/hostname
kubectl get ingress resortsLite-ingress -n resortsLite

# Test health endpoint
curl http://<INGRESS_HOST>/actuator/health
```

---

## Environment Variables Reference

| Variable | Description | Default |
|---|---|---|
| `REDIS_HOST` | Redis server hostname | `localhost` |
| `REDIS_PORT` | Redis server port | `6379` |
| `REDIS_PASSWORD` | Redis authentication password | _(empty)_ |
| `REPORT_BASE_PATH` | Base path for report files | `/reports` |
| `BACKUP_PATH` | Base path for backup files | `/backups/nightly` |
| `PAYMENT_API_URL` | Payment service API URL | `http://payment-service/payments/charge` |
| `APP_PAYMENT_ENDPOINT` | Payment endpoint | `http://payment-svc.internal:9090/charge` |
| `APP_INVENTORY_ENDPOINT` | Inventory service endpoint | `http://inventory-svc.internal:8081/rooms` |
| `APP_NOTIFICATION_ENDPOINT` | Notification service endpoint | `http://notify.internal:7070/send` |
| `SPRING_PROFILES_ACTIVE` | Spring active profile | `docker` |
| `JAVA_OPTS` | JVM options | `-Xms256m -Xmx512m ...` |
| `TZ` | Timezone | `UTC` |

### Azure Cache for Redis Setup

```bash
# Create Azure Cache for Redis
az redis create \
  --resource-group <RESOURCE_GROUP> \
  --name <REDIS_NAME> \
  --location <LOCATION> \
  --sku Basic \
  --vm-size c0

# Get connection details
az redis show --resource-group <RESOURCE_GROUP> --name <REDIS_NAME> --query hostName
az redis list-keys --resource-group <RESOURCE_GROUP> --name <REDIS_NAME> --query primaryKey
```

---

## Troubleshooting

### Pod Not Starting

```bash
# Check pod status
kubectl describe pod -l app=resortsLite -n resortsLite

# Check logs
kubectl logs -l app=resortsLite -n resortsLite --tail=100

# Check events
kubectl get events -n resortsLite --sort-by='.lastTimestamp'
```

### Common Issues

#### Redis Connection Failure
```
Error: Unable to connect to Redis at localhost:6379
```
**Fix**: Ensure `REDIS_HOST`, `REDIS_PORT`, and `REDIS_PASSWORD` are correctly set in the deployment manifest.

#### Image Pull Error
```
Error: ImagePullBackOff
```
**Fix**: Verify ACR is attached to AKS cluster:
```bash
az aks update --resource-group <RG> --name <CLUSTER> --attach-acr <ACR_NAME>
```

#### Health Check Failing
```
Liveness probe failed: HTTP probe failed with statuscode: 503
```
**Fix**: Check Redis connectivity — Spring Boot Actuator health includes Redis health by default. Verify Redis is reachable from the pod:
```bash
kubectl exec -it <POD_NAME> -n resortsLite -- sh -c "nc -zv $REDIS_HOST $REDIS_PORT"
```

#### OOMKilled (Out of Memory)
**Fix**: Increase memory limits in `kubernetes/deployment.yaml`:
```yaml
resources:
  limits:
    memory: "2Gi"
```
Also adjust JVM heap: `JAVA_OPTS: "-Xms512m -Xmx1g ..."`

### Ingress Not Accessible

```bash
# Check ingress status
kubectl describe ingress resortsLite-ingress -n resortsLite

# Verify AGIC is running
kubectl get pods -n kube-system | grep ingress-appgw

# Check Application Gateway in Azure Portal
az network application-gateway show --resource-group <RG> --name resortsLite-agw
```

---

## Scaling and Management

### Manual Scaling

```bash
# Scale to 3 replicas
kubectl scale deployment resortsLite --replicas=3 -n resortsLite

# Verify
kubectl get pods -n resortsLite
```

### Horizontal Pod Autoscaler (HPA)

```bash
kubectl autoscale deployment resortsLite \
  --cpu-percent=70 \
  --min=2 \
  --max=10 \
  -n resortsLite

# Check HPA status
kubectl get hpa -n resortsLite
```

### Rolling Updates

```bash
# Update image
kubectl set image deployment/resortsLite \
  resortsLite=myregistry.azurecr.io/resortsLite:2.0.0 \
  -n resortsLite

# Monitor rollout
kubectl rollout status deployment/resortsLite -n resortsLite
```

### Rollback

```bash
# Rollback to previous version
kubectl rollout undo deployment/resortsLite -n resortsLite

# Rollback to specific revision
kubectl rollout history deployment/resortsLite -n resortsLite
kubectl rollout undo deployment/resortsLite --to-revision=2 -n resortsLite
```

### View Application Logs

```bash
# Stream logs from all pods
kubectl logs -l app=resortsLite -n resortsLite -f --tail=100

# Logs from specific pod
kubectl logs <POD_NAME> -n resortsLite
```

---

## Security Considerations

1. **Non-root Container**: The application runs as a non-root user (`appuser`) inside the container.

2. **Redis Password**: Store Redis password in Azure Key Vault and inject via CSI Driver:
   ```bash
   az keyvault secret set --vault-name <VAULT_NAME> --name redis-password --value "<PASSWORD>"
   ```

3. **Secrets Management**: Use Kubernetes Secrets or Azure Key Vault CSI Driver for sensitive values (Redis password, API keys):
   ```yaml
   env:
     - name: REDIS_PASSWORD
       valueFrom:
         secretKeyRef:
           name: resortsLite-secrets
           key: redis-password
   ```

4. **Network Policies**: Restrict pod-to-pod communication using Kubernetes NetworkPolicy.

5. **Image Scanning**: Enable ACR vulnerability scanning:
   ```bash
   az acr task create --registry <ACR_NAME> --name scan-on-push \
     --image resortsLite:{{.Run.ID}} --context /dev/null \
     --file /dev/null --commit-trigger-enabled false
   ```

6. **TLS/HTTPS**: Configure TLS on the ingress using Azure Application Gateway with a certificate from Azure Key Vault.

7. **Resource Limits**: Always set CPU and memory limits to prevent resource exhaustion.

---

## Java-Specific Notes

### JVM Tuning for Containers

The application uses container-aware JVM flags:
```
-XX:+UseContainerSupport        # Respect container CPU/memory limits
-XX:MaxRAMPercentage=75.0       # Use 75% of container memory for heap
-Xms256m -Xmx512m               # Initial and max heap size
-Djava.security.egd=file:/dev/./urandom  # Faster random number generation
```

### Spring Boot Actuator Endpoints

| Endpoint | URL | Purpose |
|---|---|---|
| Health | `/actuator/health` | Liveness & readiness probe |
| Info | `/actuator/info` | Application metadata |

### Spring Session with Redis

The application uses Spring Session backed by Azure Cache for Redis for distributed session management. This ensures session state is preserved across pod restarts and horizontal scaling.

### H2 In-Memory Database

The application uses H2 in-memory database for development. For production AKS deployments, replace with Azure SQL Database or Azure Database for PostgreSQL and update `spring.datasource.*` environment variables accordingly.
