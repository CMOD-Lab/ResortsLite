@echo off
setlocal enabledelayedexpansion

set "APP_NAME=bookingcomp"
set "NAMESPACE=bookingcomp"

echo ============================================
echo   BookingComp - Deploy to AWS EKS
echo ============================================
echo.

REM ── Collect deployment inputs ────────────────────────────────────────────────
set /p "AWS_REGION=Enter AWS region (e.g. us-east-1): "
if "!AWS_REGION!"=="" (
    echo ERROR: AWS region is required.
    exit /b 1
)

set /p "CLUSTER_NAME=Enter EKS cluster name: "
if "!CLUSTER_NAME!"=="" (
    echo ERROR: EKS cluster name is required.
    exit /b 1
)

set /p "IMAGE_URI=Enter full Docker image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/bookingcomp:latest): "
if "!IMAGE_URI!"=="" (
    echo ERROR: Docker image URI is required.
    exit /b 1
)

echo.
echo --- Optional: Application Environment Variables ---
echo (Press Enter to keep default / skip)

set /p "INPUT_REDIS_HOST=Enter REDIS_HOST (e.g. my-cluster.abc123.ng.0001.use1.cache.amazonaws.com): "
if "!INPUT_REDIS_HOST!"=="" (set "REDIS_HOST=localhost") else (set "REDIS_HOST=!INPUT_REDIS_HOST!")

set /p "INPUT_REDIS_PORT=Enter REDIS_PORT (default 6379): "
if "!INPUT_REDIS_PORT!"=="" (set "REDIS_PORT=6379") else (set "REDIS_PORT=!INPUT_REDIS_PORT!")

set /p "INPUT_REDIS_PASSWORD=Enter REDIS_PASSWORD (leave blank if none): "
set "REDIS_PASSWORD=!INPUT_REDIS_PASSWORD!"

set /p "INPUT_REPORT_BASE_PATH=Enter REPORT_BASE_PATH (default /var/reports): "
if "!INPUT_REPORT_BASE_PATH!"=="" (set "REPORT_BASE_PATH=/var/reports") else (set "REPORT_BASE_PATH=!INPUT_REPORT_BASE_PATH!")

set /p "INPUT_BACKUP_PATH=Enter BACKUP_PATH (default /var/backups/nightly): "
if "!INPUT_BACKUP_PATH!"=="" (set "BACKUP_PATH=/var/backups/nightly") else (set "BACKUP_PATH=!INPUT_BACKUP_PATH!")

set /p "INPUT_PAYMENT_API_URL=Enter PAYMENT_API_URL (default http://payment-service/payments/charge): "
if "!INPUT_PAYMENT_API_URL!"=="" (set "PAYMENT_API_URL=http://payment-service/payments/charge") else (set "PAYMENT_API_URL=!INPUT_PAYMENT_API_URL!")

REM ── Configure kubectl for EKS ────────────────────────────────────────────────
echo.
echo Configuring kubectl for EKS cluster: !CLUSTER_NAME! in !AWS_REGION! ...
aws eks update-kubeconfig --region !AWS_REGION! --name !CLUSTER_NAME!
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to configure kubectl.
    exit /b 1
)

echo Verifying cluster connectivity...
kubectl cluster-info
if !ERRORLEVEL! neq 0 (
    echo ERROR: Cannot connect to EKS cluster.
    exit /b 1
)

REM ── Patch Kubernetes manifests ───────────────────────────────────────────────
echo.
echo Updating Kubernetes manifests with deployment values...

copy /Y kubernetes\deployment.yaml %TEMP%\deployment-deploy.yaml >nul

powershell -NoProfile -Command ^
  "(Get-Content '%TEMP%\deployment-deploy.yaml') ^
   -replace '{{IMAGE_URI}}','!IMAGE_URI!' ^
   -replace '{{REDIS_HOST}}','!REDIS_HOST!' ^
   -replace '{{REDIS_PORT}}','!REDIS_PORT!' ^
   -replace '{{REDIS_PASSWORD}}','!REDIS_PASSWORD!' ^
   -replace '{{REPORT_BASE_PATH}}','!REPORT_BASE_PATH!' ^
   -replace '{{BACKUP_PATH}}','!BACKUP_PATH!' ^
   -replace '{{PAYMENT_API_URL}}','!PAYMENT_API_URL!' ^
   | Set-Content '%TEMP%\deployment-deploy.yaml'"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to update deployment manifest.
    exit /b 1
)

REM ── Apply manifests ──────────────────────────────────────────────────────────
echo.
echo Applying Kubernetes manifests...

echo   [1/4] Applying namespace...
kubectl apply -f kubernetes\namespace.yaml
if !ERRORLEVEL! neq 0 (echo ERROR: Failed to apply namespace. & exit /b 1)

echo   [2/4] Applying deployment...
kubectl apply -f %TEMP%\deployment-deploy.yaml
if !ERRORLEVEL! neq 0 (echo ERROR: Failed to apply deployment. & exit /b 1)

echo   [3/4] Applying service...
kubectl apply -f kubernetes\service.yaml
if !ERRORLEVEL! neq 0 (echo ERROR: Failed to apply service. & exit /b 1)

echo   [4/4] Applying ingress...
kubectl apply -f kubernetes\ingress.yaml
if !ERRORLEVEL! neq 0 (echo ERROR: Failed to apply ingress. & exit /b 1)

REM ── Wait for rollout ─────────────────────────────────────────────────────────
echo.
echo Waiting for deployment rollout to complete...
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
    echo ERROR: Deployment rollout timed out or failed.
    echo To rollback, run: kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
    exit /b 1
)

REM ── Verify resources ─────────────────────────────────────────────────────────
echo.
echo Verifying deployed resources...
kubectl get pods,svc,ingress -n !NAMESPACE!

echo.
echo ============================================
echo   Deployment Complete!
echo   Namespace : !NAMESPACE!
echo   Image     : !IMAGE_URI!
echo   Rollback  : kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
echo ============================================

endlocal
