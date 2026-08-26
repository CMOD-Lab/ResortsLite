@echo off
setlocal enabledelayedexpansion

:: =============================================================================
:: deploy-image.bat – Deploy BookingComp to AWS ECS Fargate (Windows)
:: Usage: scripts\deploy-image.bat   (run from repository root)
:: =============================================================================

set "TASK_DEF_FILE=ecs\task-definition.json"
set "SERVICE_DEF_FILE=ecs\service-definition.json"
set "SERVICE_NAME=bookingcomp-service"
set "TASK_FAMILY=bookingcomp-task"
set "LOG_GROUP=/ecs/bookingcomp"
set "CONTAINER_NAME=bookingcomp"
set "APP_PORT=8080"

echo ==============================================
echo   ResortsLite BookingComp - ECS Fargate Deploy
echo ==============================================
echo.

:: ---------------------------------------------------------------------------
:: Collect inputs
:: ---------------------------------------------------------------------------
set /p "AWS_REGION=Enter AWS Region (e.g. us-east-1): "
set /p "CLUSTER_NAME=Enter ECS Cluster name (will be created if absent): "
set /p "IMAGE_URI=Enter full ECR image URI: "
set /p "SUBNET_1=Enter Subnet ID #1: "
set /p "SUBNET_2=Enter Subnet ID #2: "
set /p "SECURITY_GROUP=Enter Security Group ID: "

:: ---------------------------------------------------------------------------
:: Resolve AWS Account ID
:: ---------------------------------------------------------------------------
echo.
echo Resolving AWS Account ID...
for /f "tokens=*" %%i in ('aws sts get-caller-identity --query Account --output text') do set "ACCOUNT_ID=%%i"
echo Account ID: !ACCOUNT_ID!

:: ---------------------------------------------------------------------------
:: Ensure CloudWatch log group exists
:: ---------------------------------------------------------------------------
echo Ensuring CloudWatch log group exists...
aws logs create-log-group --log-group-name "%LOG_GROUP%" --region !AWS_REGION! 2>nul
echo Log group ready.

:: ---------------------------------------------------------------------------
:: Ensure ECS cluster exists
:: ---------------------------------------------------------------------------
echo Checking ECS cluster '!CLUSTER_NAME!'...
for /f "tokens=*" %%s in ('aws ecs describe-clusters --clusters !CLUSTER_NAME! --region !AWS_REGION! --query "clusters[0].status" --output text 2^>nul') do set "CLUSTER_STATUS=%%s"
if not "!CLUSTER_STATUS!"=="ACTIVE" (
    echo Creating ECS cluster '!CLUSTER_NAME!'...
    aws ecs create-cluster --cluster-name !CLUSTER_NAME! --region !AWS_REGION!
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create ECS cluster.
        exit /b 1
    )
)
echo Cluster ready.

:: ---------------------------------------------------------------------------
:: Load balancer (optional)
:: ---------------------------------------------------------------------------
echo.
set /p "NEED_LB=Do you need an Application Load Balancer for this service? (y/n): "
set "TARGET_GROUP_ARN="
set "LB_DNS="

if /i "!NEED_LB!"=="y" (
    set /p "VPC_ID=Enter VPC ID for the load balancer: "

    echo Creating Application Load Balancer...
    for /f "tokens=*" %%a in ('aws elbv2 create-load-balancer --name bookingcomp-alb --subnets !SUBNET_1! !SUBNET_2! --security-groups !SECURITY_GROUP! --scheme internet-facing --type application --region !AWS_REGION! --query "LoadBalancers[0].LoadBalancerArn" --output text') do set "LB_ARN=%%a"
    for /f "tokens=*" %%d in ('aws elbv2 describe-load-balancers --load-balancer-arns !LB_ARN! --region !AWS_REGION! --query "LoadBalancers[0].DNSName" --output text') do set "LB_DNS=%%d"
    echo ALB created: !LB_DNS!

    echo Creating Target Group...
    for /f "tokens=*" %%t in ('aws elbv2 create-target-group --name bookingcomp-tg --protocol HTTP --port %APP_PORT% --vpc-id !VPC_ID! --target-type ip --health-check-path /actuator/health --health-check-interval-seconds 30 --healthy-threshold-count 2 --unhealthy-threshold-count 3 --region !AWS_REGION! --query "TargetGroups[0].TargetGroupArn" --output text') do set "TARGET_GROUP_ARN=%%t"
    echo Target Group ARN: !TARGET_GROUP_ARN!

    echo Creating ALB Listener on port 80...
    aws elbv2 create-listener --load-balancer-arn !LB_ARN! --protocol HTTP --port 80 --default-actions "Type=forward,TargetGroupArn=!TARGET_GROUP_ARN!" --region !AWS_REGION! >nul
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create ALB listener.
        exit /b 1
    )
)

:: ---------------------------------------------------------------------------
:: Substitute placeholders in task definition (using PowerShell)
:: ---------------------------------------------------------------------------
echo.
echo Preparing task definition...
copy "%TASK_DEF_FILE%" "%TEMP%\task-definition-deploy.json" >nul
powershell -Command "(Get-Content '%TEMP%\task-definition-deploy.json') -replace '{{ACCOUNT_ID}}','!ACCOUNT_ID!' -replace '{{AWS_REGION}}','!AWS_REGION!' -replace '{{IMAGE_URI}}','!IMAGE_URI!' | Set-Content '%TEMP%\task-definition-deploy.json'"

:: ---------------------------------------------------------------------------
:: Register task definition
:: ---------------------------------------------------------------------------
echo Registering task definition '%TASK_FAMILY%'...
for /f "tokens=*" %%r in ('aws ecs register-task-definition --cli-input-json file://%TEMP%\task-definition-deploy.json --region !AWS_REGION! --query "taskDefinition.taskDefinitionArn" --output text') do set "TASK_DEF_ARN=%%r"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to register task definition.
    exit /b 1
)
echo Task Definition ARN: !TASK_DEF_ARN!

:: ---------------------------------------------------------------------------
:: Prepare service definition
:: ---------------------------------------------------------------------------
copy "%SERVICE_DEF_FILE%" "%TEMP%\service-definition-deploy.json" >nul
powershell -Command "(Get-Content '%TEMP%\service-definition-deploy.json') -replace '{{CLUSTER_NAME}}','!CLUSTER_NAME!' -replace '{{SUBNET_1}}','!SUBNET_1!' -replace '{{SUBNET_2}}','!SUBNET_2!' -replace '{{SECURITY_GROUP}}','!SECURITY_GROUP!' | Set-Content '%TEMP%\service-definition-deploy.json'"

:: Inject load balancer block if requested
if /i "!NEED_LB!"=="y" (
    powershell -Command "$svc = Get-Content '%TEMP%\service-definition-deploy.json' | ConvertFrom-Json; $svc | Add-Member -NotePropertyName 'loadBalancers' -NotePropertyValue @(@{targetGroupArn='!TARGET_GROUP_ARN!'; containerName='%CONTAINER_NAME%'; containerPort=%APP_PORT%}) -Force; $svc | Add-Member -NotePropertyName 'healthCheckGracePeriodSeconds' -NotePropertyValue 300 -Force; $svc | ConvertTo-Json -Depth 10 | Set-Content '%TEMP%\service-definition-deploy.json'"
)

:: ---------------------------------------------------------------------------
:: Create or update ECS service
:: ---------------------------------------------------------------------------
echo.
for /f "tokens=*" %%e in ('aws ecs describe-services --cluster !CLUSTER_NAME! --services %SERVICE_NAME% --region !AWS_REGION! --query "services[?status==''ACTIVE''].serviceName" --output text 2^>nul') do set "EXISTING_SERVICE=%%e"

if "!EXISTING_SERVICE!"=="" (
    echo Creating ECS service '%SERVICE_NAME%'...
    aws ecs create-service --cli-input-json file://%TEMP%\service-definition-deploy.json --region !AWS_REGION!
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create ECS service.
        exit /b 1
    )
) else (
    echo Updating existing ECS service '%SERVICE_NAME%'...
    aws ecs update-service --cluster !CLUSTER_NAME! --service %SERVICE_NAME% --task-definition !TASK_DEF_ARN! --region !AWS_REGION!
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to update ECS service.
        exit /b 1
    )
)

:: ---------------------------------------------------------------------------
:: Wait for stability
:: ---------------------------------------------------------------------------
echo.
echo Waiting for service to reach stable state (this may take a few minutes)...
aws ecs wait services-stable --cluster !CLUSTER_NAME! --services %SERVICE_NAME% --region !AWS_REGION!
if !ERRORLEVEL! neq 0 (
    echo WARNING: Service did not reach stable state within timeout.
)

:: ---------------------------------------------------------------------------
:: Verify
:: ---------------------------------------------------------------------------
echo.
echo Deployment complete. Service details:
aws ecs describe-services --cluster !CLUSTER_NAME! --services %SERVICE_NAME% --region !AWS_REGION! --query "services[0].{Status:status,Running:runningCount,Desired:desiredCount}" --output table

echo.
echo CloudWatch Logs: %LOG_GROUP%
if not "!LB_DNS!"=="" (
    echo Load Balancer DNS: http://!LB_DNS!
    echo Health Check URL : http://!LB_DNS!/actuator/health
)
echo.
echo Troubleshooting tips:
echo   - View stopped tasks : aws ecs list-tasks --cluster !CLUSTER_NAME! --desired-status STOPPED --region !AWS_REGION!
echo   - CloudWatch logs    : aws logs tail %LOG_GROUP% --follow --region !AWS_REGION!
echo ==============================================

endlocal
