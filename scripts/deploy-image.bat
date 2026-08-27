@echo off
setlocal enabledelayedexpansion

:: =============================================================================
:: deploy-image.bat – Deploy ResortsLite to AWS ECS Fargate (Windows)
:: Usage: scripts\deploy-image.bat
:: Prerequisites: aws-cli v2 configured with appropriate IAM permissions
:: =============================================================================

set "SERVICE_NAME=resortslite-service"
set "TASK_FAMILY=resortslite-task"
set "CONTAINER_NAME=resortslite"
set "APP_PORT=8080"
set "LOG_GROUP=/ecs/resortslite"

:: Determine project root (parent of scripts folder)
set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "PROJECT_ROOT=%%~fI"

echo ==============================================
echo   ResortsLite - ECS Fargate Deployment
echo ==============================================
echo.

:: ---------------------------------------------------------------------------
:: Collect configuration
:: ---------------------------------------------------------------------------
set /p "AWS_REGION=Enter AWS Region [us-east-1]: "
if "!AWS_REGION!"=="" set "AWS_REGION=us-east-1"

set /p "CLUSTER_NAME=Enter ECS Cluster name [resortslite-cluster]: "
if "!CLUSTER_NAME!"=="" set "CLUSTER_NAME=resortslite-cluster"

set /p "IMAGE_URI=Enter ECR Image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): "
if "!IMAGE_URI!"=="" (
    echo ERROR: Image URI is required.
    exit /b 1
)

set /p "SUBNET_1=Enter Subnet 1 ID (e.g. subnet-xxxxxxxx): "
set /p "SUBNET_2=Enter Subnet 2 ID (e.g. subnet-yyyyyyyy): "
set /p "SECURITY_GROUP=Enter Security Group ID (e.g. sg-xxxxxxxx): "

:: ---------------------------------------------------------------------------
:: Derive Account ID
:: ---------------------------------------------------------------------------
echo.
echo Fetching AWS Account ID...
for /f "delims=" %%A in ('aws sts get-caller-identity --query Account --output text') do set "ACCOUNT_ID=%%A"
if "!ACCOUNT_ID!"=="" (
    echo ERROR: Could not retrieve AWS Account ID. Check your AWS CLI configuration.
    exit /b 1
)
echo Account ID: !ACCOUNT_ID!

:: ---------------------------------------------------------------------------
:: Ensure CloudWatch log group exists
:: ---------------------------------------------------------------------------
echo.
echo Ensuring CloudWatch log group !LOG_GROUP! exists...
aws logs create-log-group --log-group-name "!LOG_GROUP!" --region "!AWS_REGION!" >nul 2>&1

:: ---------------------------------------------------------------------------
:: Ensure ECS cluster exists
:: ---------------------------------------------------------------------------
echo Checking ECS cluster: !CLUSTER_NAME!...
for /f "delims=" %%S in ('aws ecs describe-clusters --clusters "!CLUSTER_NAME!" --region "!AWS_REGION!" --query "clusters[0].status" --output text 2^>nul') do set "CLUSTER_STATUS=%%S"
if not "!CLUSTER_STATUS!"=="ACTIVE" (
    echo Creating ECS cluster: !CLUSTER_NAME!...
    aws ecs create-cluster --cluster-name "!CLUSTER_NAME!" --region "!AWS_REGION!"
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create ECS cluster.
        exit /b 1
    )
)

:: ---------------------------------------------------------------------------
:: Load balancer prompt
:: ---------------------------------------------------------------------------
echo.
set /p "NEED_LB=Do you need an Application Load Balancer for this service? (y/n) [n]: "
if "!NEED_LB!"=="" set "NEED_LB=n"

set "TARGET_GROUP_ARN="
set "ALB_DNS="

if /i "!NEED_LB!"=="y" (
    set /p "VPC_ID=Enter VPC ID for the ALB (e.g. vpc-xxxxxxxx): "

    echo Creating Application Load Balancer...
    for /f "delims=" %%L in ('aws elbv2 create-load-balancer --name "resortslite-alb" --subnets "!SUBNET_1!" "!SUBNET_2!" --security-groups "!SECURITY_GROUP!" --scheme internet-facing --type application --region "!AWS_REGION!" --query "LoadBalancers[0].LoadBalancerArn" --output text') do set "ALB_ARN=%%L"

    for /f "delims=" %%D in ('aws elbv2 describe-load-balancers --load-balancer-arns "!ALB_ARN!" --region "!AWS_REGION!" --query "LoadBalancers[0].DNSName" --output text') do set "ALB_DNS=%%D"

    echo Creating Target Group...
    for /f "delims=" %%T in ('aws elbv2 create-target-group --name "resortslite-tg" --protocol HTTP --port !APP_PORT! --vpc-id "!VPC_ID!" --target-type ip --health-check-path "/actuator/health" --health-check-interval-seconds 30 --healthy-threshold-count 2 --unhealthy-threshold-count 3 --region "!AWS_REGION!" --query "TargetGroups[0].TargetGroupArn" --output text') do set "TARGET_GROUP_ARN=%%T"

    echo Creating ALB Listener...
    aws elbv2 create-listener --load-balancer-arn "!ALB_ARN!" --protocol HTTP --port 80 --default-actions "Type=forward,TargetGroupArn=!TARGET_GROUP_ARN!" --region "!AWS_REGION!" >nul
)

:: ---------------------------------------------------------------------------
:: Prepare task definition (replace placeholders via PowerShell)
:: ---------------------------------------------------------------------------
echo.
echo Preparing task definition...
set "TASK_DEF_FILE=!PROJECT_ROOT!\ecs\task-definition.json"
set "TASK_DEF_TMP=%TEMP%\resortslite-task-def.json"

powershell -NoProfile -Command ^
  "(Get-Content '!TASK_DEF_FILE!' -Raw)" ^
  " -replace '{{ACCOUNT_ID}}','!ACCOUNT_ID!'" ^
  " -replace '{{AWS_REGION}}','!AWS_REGION!'" ^
  " -replace '{{IMAGE_URI}}','!IMAGE_URI!'" ^
  " -replace '{{REDIS_HOST}}','localhost'" ^
  " -replace '{{PAYMENT_API_URL}}','http://payment-service:9090/payments/charge'" ^
  " -replace '{{APP_PAYMENT_ENDPOINT}}','http://payment-svc.internal:9090/charge'" ^
  " -replace '{{APP_INVENTORY_ENDPOINT}}','http://inventory-svc.internal:8081/rooms'" ^
  " -replace '{{APP_NOTIFICATION_ENDPOINT}}','http://notify.internal:7070/send'" ^
  " | Set-Content '!TASK_DEF_TMP!'"

:: ---------------------------------------------------------------------------
:: Register task definition
:: ---------------------------------------------------------------------------
echo Registering task definition...
for /f "delims=" %%R in ('aws ecs register-task-definition --cli-input-json "file://!TASK_DEF_TMP!" --region "!AWS_REGION!" --query "taskDefinition.taskDefinitionArn" --output text') do set "TASK_DEF_ARN=%%R"
if "!TASK_DEF_ARN!"=="" (
    echo ERROR: Failed to register task definition.
    exit /b 1
)
echo Registered: !TASK_DEF_ARN!

:: ---------------------------------------------------------------------------
:: Prepare service definition (replace placeholders via PowerShell)
:: ---------------------------------------------------------------------------
set "SVC_DEF_FILE=!PROJECT_ROOT!\ecs\service-definition.json"
set "SVC_DEF_TMP=%TEMP%\resortslite-svc-def.json"

powershell -NoProfile -Command ^
  "(Get-Content '!SVC_DEF_FILE!' -Raw)" ^
  " -replace '{{CLUSTER_NAME}}','!CLUSTER_NAME!'" ^
  " -replace '{{SUBNET_1}}','!SUBNET_1!'" ^
  " -replace '{{SUBNET_2}}','!SUBNET_2!'" ^
  " -replace '{{SECURITY_GROUP}}','!SECURITY_GROUP!'" ^
  " | Set-Content '!SVC_DEF_TMP!'"

:: Inject load balancer section if requested
if /i "!NEED_LB!"=="y" (
    powershell -NoProfile -Command ^
      "$svc = Get-Content '!SVC_DEF_TMP!' | ConvertFrom-Json;" ^
      "$lb = @{ targetGroupArn='!TARGET_GROUP_ARN!'; containerName='!CONTAINER_NAME!'; containerPort=!APP_PORT! };" ^
      "$svc | Add-Member -NotePropertyName loadBalancers -NotePropertyValue @($lb) -Force;" ^
      "$svc | Add-Member -NotePropertyName healthCheckGracePeriodSeconds -NotePropertyValue 300 -Force;" ^
      "$svc | ConvertTo-Json -Depth 10 | Set-Content '!SVC_DEF_TMP!'"
)

:: Inject task definition ARN
powershell -NoProfile -Command ^
  "$svc = Get-Content '!SVC_DEF_TMP!' | ConvertFrom-Json;" ^
  "$svc.taskDefinition = '!TASK_DEF_ARN!';" ^
  "$svc | ConvertTo-Json -Depth 10 | Set-Content '!SVC_DEF_TMP!'"

:: ---------------------------------------------------------------------------
:: Create or update ECS service
:: ---------------------------------------------------------------------------
echo.
for /f "delims=" %%E in ('aws ecs describe-services --cluster "!CLUSTER_NAME!" --services "!SERVICE_NAME!" --region "!AWS_REGION!" --query "services[?status!='INACTIVE'].serviceName" --output text 2^>nul') do set "EXISTING_SERVICE=%%E"

if "!EXISTING_SERVICE!"=="" (
    echo Creating ECS service: !SERVICE_NAME!...
    aws ecs create-service --cli-input-json "file://!SVC_DEF_TMP!" --region "!AWS_REGION!"
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create ECS service.
        exit /b 1
    )
) else (
    echo Updating existing ECS service: !SERVICE_NAME!...
    aws ecs update-service --cluster "!CLUSTER_NAME!" --service "!SERVICE_NAME!" --task-definition "!TASK_DEF_ARN!" --region "!AWS_REGION!"
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to update ECS service.
        exit /b 1
    )
)

:: ---------------------------------------------------------------------------
:: Wait for stability
:: ---------------------------------------------------------------------------
echo.
echo Waiting for service to stabilise (this may take a few minutes)...
aws ecs wait services-stable --cluster "!CLUSTER_NAME!" --services "!SERVICE_NAME!" --region "!AWS_REGION!"
if !ERRORLEVEL! neq 0 (
    echo WARNING: Service did not stabilise within the expected time. Check ECS console.
)

:: ---------------------------------------------------------------------------
:: Summary
:: ---------------------------------------------------------------------------
echo.
echo ==============================================
echo   Deployment complete!
echo ==============================================
aws ecs describe-services --cluster "!CLUSTER_NAME!" --services "!SERVICE_NAME!" --region "!AWS_REGION!" --query "services[0].{Status:status,Running:runningCount,Desired:desiredCount}" --output table

echo.
echo CloudWatch Log Group : !LOG_GROUP!
if not "!ALB_DNS!"=="" (
    echo Load Balancer DNS    : http://!ALB_DNS!
    echo Health Check URL     : http://!ALB_DNS!/actuator/health
)
echo.
echo Troubleshooting tips:
echo   View logs  : aws logs tail !LOG_GROUP! --follow --region !AWS_REGION!
echo   List tasks : aws ecs list-tasks --cluster !CLUSTER_NAME! --region !AWS_REGION!

:: Cleanup
del /f /q "!TASK_DEF_TMP!" >nul 2>&1
del /f /q "!SVC_DEF_TMP!" >nul 2>&1

endlocal
exit /b 0
