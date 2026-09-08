@echo off
setlocal enabledelayedexpansion

:: ============================================================
:: deploy-image.bat — Deploy ResortsLite to Azure AKS
:: Usage: scripts\deploy-image.bat
:: Run from repository root directory
:: ============================================================

set "APP_NAME=resortsLite"
set "NAMESPACE=resortsLite"
set "K8S_DIR=kubernetes"

echo ==============================================
echo   ResortsLite - Deploy to Azure AKS
echo ==============================================

:: ---- Azure / AKS credentials ----
set /p "RESOURCE_GROUP=Enter Azure Resource Group name: "
if "!RESOURCE_GROUP!"=="" (
    echo ERROR: Resource group cannot be empty.
    exit /b 1
)

set /p "CLUSTER_NAME=Enter AKS Cluster name: "
if "!CLUSTER_NAME!"=="" (
    echo ERROR: AKS cluster name cannot be empty.
    exit /b 1
)

:: ---- Docker image URI ----
set /p "IMAGE_URI=Enter full Docker image URI (e.g. myregistry.azurecr.io/resortsLite:latest): "
if "!IMAGE_URI!"=="" (
    echo ERROR: Image URI cannot be empty.
    exit /b 1
)

echo.
echo ---- Application Environment Variables ----
echo Press Enter to use default values

set /p "REDIS_HOST_VAL=Enter REDIS_HOST [localhost]: "
if "!REDIS_HOST_VAL!"=="" set "REDIS_HOST_VAL=localhost"

set /p "REDIS_PORT_VAL=Enter REDIS_PORT [6379]: "
if "!REDIS_PORT_VAL!"=="" set "REDIS_PORT_VAL=6379"

set /p "REDIS_PASSWORD_VAL=Enter REDIS_PASSWORD (leave blank if none): "

set /p "REPORT_BASE_PATH_VAL=Enter REPORT_BASE_PATH [/reports]: "
if "!REPORT_BASE_PATH_VAL!"=="" set "REPORT_BASE_PATH_VAL=/reports"

set /p "BACKUP_PATH_VAL=Enter BACKUP_PATH [/backups/nightly]: "
if "!BACKUP_PATH_VAL!"=="" set "BACKUP_PATH_VAL=/backups/nightly"

set /p "PAYMENT_API_URL_VAL=Enter PAYMENT_API_URL [http://payment-service/payments/charge]: "
if "!PAYMENT_API_URL_VAL!"=="" set "PAYMENT_API_URL_VAL=http://payment-service/payments/charge"

set /p "APP_PAYMENT_ENDPOINT_VAL=Enter APP_PAYMENT_ENDPOINT [http://payment-svc.internal:9090/charge]: "
if "!APP_PAYMENT_ENDPOINT_VAL!"=="" set "APP_PAYMENT_ENDPOINT_VAL=http://payment-svc.internal:9090/charge"

set /p "APP_INVENTORY_ENDPOINT_VAL=Enter APP_INVENTORY_ENDPOINT [http://inventory-svc.internal:8081/rooms]: "
if "!APP_INVENTORY_ENDPOINT_VAL!"=="" set "APP_INVENTORY_ENDPOINT_VAL=http://inventory-svc.internal:8081/rooms"

set /p "APP_NOTIFICATION_ENDPOINT_VAL=Enter APP_NOTIFICATION_ENDPOINT [http://notify.internal:7070/send]: "
if "!APP_NOTIFICATION_ENDPOINT_VAL!"=="" set "APP_NOTIFICATION_ENDPOINT_VAL=http://notify.internal:7070/send"

:: ---- Configure kubectl ----
echo.
echo ---- Configuring kubectl for AKS ----
az aks get-credentials --resource-group !RESOURCE_GROUP! --name !CLUSTER_NAME! --overwrite-existing
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to get AKS credentials.
    exit /b 1
)
echo kubectl configured for cluster: !CLUSTER_NAME!

echo.
echo Verifying cluster connectivity ...
kubectl cluster-info
if !ERRORLEVEL! neq 0 (
    echo ERROR: Cannot connect to AKS cluster.
    exit /b 1
)

:: ---- Substitute placeholders using PowerShell ----
echo.
echo ---- Updating Kubernetes manifests ----

copy /Y "!K8S_DIR!\deployment.yaml" "!K8S_DIR!\deployment.yaml.deploy" >nul

powershell -NoProfile -Command ^
  "(Get-Content '!K8S_DIR!\deployment.yaml.deploy') ^
   -replace '\{\{IMAGE_URI\}\}','!IMAGE_URI!' ^
   -replace '\{\{REDIS_HOST\}\}','!REDIS_HOST_VAL!' ^
   -replace '\{\{REDIS_PORT\}\}','!REDIS_PORT_VAL!' ^
   -replace '\{\{REDIS_PASSWORD\}\}','!REDIS_PASSWORD_VAL!' ^
   -replace '\{\{REPORT_BASE_PATH\}\}','!REPORT_BASE_PATH_VAL!' ^
   -replace '\{\{BACKUP_PATH\}\}','!BACKUP_PATH_VAL!' ^
   -replace '\{\{PAYMENT_API_URL\}\}','!PAYMENT_API_URL_VAL!' ^
   -replace '\{\{APP_PAYMENT_ENDPOINT\}\}','!APP_PAYMENT_ENDPOINT_VAL!' ^
   -replace '\{\{APP_INVENTORY_ENDPOINT\}\}','!APP_INVENTORY_ENDPOINT_VAL!' ^
   -replace '\{\{APP_NOTIFICATION_ENDPOINT\}\}','!APP_NOTIFICATION_ENDPOINT_VAL!' ^
   | Set-Content '!K8S_DIR!\deployment.yaml.deploy'"

if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to update deployment manifest.
    exit /b 1
)
echo Manifests updated.

:: ---- Apply manifests ----
echo.
echo ---- Applying Kubernetes manifests ----

echo [1/4] Applying namespace ...
kubectl apply -f "!K8S_DIR!\namespace.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply namespace. & exit /b 1 )

echo [2/4] Applying deployment ...
kubectl apply -f "!K8S_DIR!\deployment.yaml.deploy"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply deployment. & exit /b 1 )

echo [3/4] Applying service ...
kubectl apply -f "!K8S_DIR!\service.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply service. & exit /b 1 )

echo [4/4] Applying ingress ...
kubectl apply -f "!K8S_DIR!\ingress.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply ingress. & exit /b 1 )

:: Clean up temp file
del /f /q "!K8S_DIR!\deployment.yaml.deploy" >nul 2>&1

:: ---- Wait for rollout ----
echo.
echo ---- Waiting for deployment rollout ----
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
    echo.
    echo ERROR: Deployment rollout failed or timed out.
    echo To rollback, run:
    echo   kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
    exit /b 1
)

:: ---- Verify resources ----
echo.
echo ---- Verifying deployed resources ----
kubectl get pods,svc,ingress -n !NAMESPACE!

echo.
echo ==============================================
echo   Deployment Complete!
echo   Application URL: http://resortsLite.example.com
echo   Health Check:    http://resortsLite.example.com/actuator/health
echo.
echo   Useful commands:
echo     kubectl get pods -n !NAMESPACE!
echo     kubectl logs -l app=!APP_NAME! -n !NAMESPACE! --tail=100
echo     kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
echo ==============================================

endlocal
