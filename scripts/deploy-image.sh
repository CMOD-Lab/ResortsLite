#!/usr/bin/env bash
# =============================================================================
# deploy-image.sh – Deploy ResortsLite to AWS ECS Fargate
# Usage: ./scripts/deploy-image.sh
# Prerequisites: aws-cli v2 configured with appropriate IAM permissions
# =============================================================================
set -e
set -o pipefail

SERVICE_NAME="resortslite-service"
TASK_FAMILY="resortslite-task"
CONTAINER_NAME="resortslite"
APP_PORT=8080
LOG_GROUP="/ecs/resortslite"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

echo "=============================================="
echo "  ResortsLite – ECS Fargate Deployment"
echo "=============================================="
echo ""

# ---------------------------------------------------------------------------
# Collect configuration
# ---------------------------------------------------------------------------
read -rp "Enter AWS Region [us-east-1]: " AWS_REGION
AWS_REGION="${AWS_REGION:-us-east-1}"

read -rp "Enter ECS Cluster name [resortslite-cluster]: " CLUSTER_NAME
CLUSTER_NAME="${CLUSTER_NAME:-resortslite-cluster}"

read -rp "Enter ECR Image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): " IMAGE_URI
if [ -z "$IMAGE_URI" ]; then
  echo "ERROR: Image URI is required."
  exit 1
fi

read -rp "Enter Subnet 1 ID (e.g. subnet-xxxxxxxx): " SUBNET_1
read -rp "Enter Subnet 2 ID (e.g. subnet-yyyyyyyy): " SUBNET_2
read -rp "Enter Security Group ID (e.g. sg-xxxxxxxx): " SECURITY_GROUP

# ---------------------------------------------------------------------------
# Derive Account ID
# ---------------------------------------------------------------------------
echo ""
echo "Fetching AWS Account ID..."
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
echo "Account ID: $ACCOUNT_ID"

# ---------------------------------------------------------------------------
# Ensure CloudWatch log group exists
# ---------------------------------------------------------------------------
echo ""
echo "Ensuring CloudWatch log group $LOG_GROUP exists..."
aws logs create-log-group --log-group-name "$LOG_GROUP" --region "$AWS_REGION" 2>/dev/null || true

# ---------------------------------------------------------------------------
# Ensure ECS cluster exists
# ---------------------------------------------------------------------------
echo "Checking ECS cluster: $CLUSTER_NAME..."
CLUSTER_STATUS=$(aws ecs describe-clusters --clusters "$CLUSTER_NAME" --region "$AWS_REGION" \
  --query "clusters[0].status" --output text 2>/dev/null || echo "MISSING")

if [ "$CLUSTER_STATUS" != "ACTIVE" ]; then
  echo "Creating ECS cluster: $CLUSTER_NAME..."
  aws ecs create-cluster --cluster-name "$CLUSTER_NAME" --region "$AWS_REGION"
fi

# ---------------------------------------------------------------------------
# Load balancer prompt
# ---------------------------------------------------------------------------
echo ""
read -rp "Do you need an Application Load Balancer for this service? (y/n) [n]: " NEED_LB
NEED_LB="${NEED_LB:-n}"

TARGET_GROUP_ARN=""
ALB_DNS=""

if [[ "$NEED_LB" =~ ^[Yy]$ ]]; then
  read -rp "Enter VPC ID for the ALB (e.g. vpc-xxxxxxxx): " VPC_ID

  echo "Creating Application Load Balancer..."
  ALB_ARN=$(aws elbv2 create-load-balancer \
    --name "resortslite-alb" \
    --subnets "$SUBNET_1" "$SUBNET_2" \
    --security-groups "$SECURITY_GROUP" \
    --scheme internet-facing \
    --type application \
    --region "$AWS_REGION" \
    --query "LoadBalancers[0].LoadBalancerArn" --output text)

  ALB_DNS=$(aws elbv2 describe-load-balancers \
    --load-balancer-arns "$ALB_ARN" \
    --region "$AWS_REGION" \
    --query "LoadBalancers[0].DNSName" --output text)

  echo "Creating Target Group (type: ip for Fargate awsvpc)..."
  TARGET_GROUP_ARN=$(aws elbv2 create-target-group \
    --name "resortslite-tg" \
    --protocol HTTP \
    --port "$APP_PORT" \
    --vpc-id "$VPC_ID" \
    --target-type ip \
    --health-check-path "/actuator/health" \
    --health-check-interval-seconds 30 \
    --healthy-threshold-count 2 \
    --unhealthy-threshold-count 3 \
    --region "$AWS_REGION" \
    --query "TargetGroups[0].TargetGroupArn" --output text)

  echo "Creating ALB Listener..."
  aws elbv2 create-listener \
    --load-balancer-arn "$ALB_ARN" \
    --protocol HTTP \
    --port 80 \
    --default-actions "Type=forward,TargetGroupArn=$TARGET_GROUP_ARN" \
    --region "$AWS_REGION" >/dev/null
fi

# ---------------------------------------------------------------------------
# Prepare task definition JSON (replace placeholders)
# ---------------------------------------------------------------------------
echo ""
echo "Preparing task definition..."
TASK_DEF_FILE="$PROJECT_ROOT/ecs/task-definition.json"
TASK_DEF_TMP="/tmp/resortslite-task-def-$$.json"

sed \
  -e "s|{{ACCOUNT_ID}}|$ACCOUNT_ID|g" \
  -e "s|{{AWS_REGION}}|$AWS_REGION|g" \
  -e "s|{{IMAGE_URI}}|$IMAGE_URI|g" \
  -e "s|{{REDIS_HOST}}|${REDIS_HOST:-localhost}|g" \
  -e "s|{{PAYMENT_API_URL}}|${PAYMENT_API_URL:-http://payment-service:9090/payments/charge}|g" \
  -e "s|{{APP_PAYMENT_ENDPOINT}}|${APP_PAYMENT_ENDPOINT:-http://payment-svc.internal:9090/charge}|g" \
  -e "s|{{APP_INVENTORY_ENDPOINT}}|${APP_INVENTORY_ENDPOINT:-http://inventory-svc.internal:8081/rooms}|g" \
  -e "s|{{APP_NOTIFICATION_ENDPOINT}}|${APP_NOTIFICATION_ENDPOINT:-http://notify.internal:7070/send}|g" \
  "$TASK_DEF_FILE" > "$TASK_DEF_TMP"

# ---------------------------------------------------------------------------
# Register task definition
# ---------------------------------------------------------------------------
echo "Registering task definition..."
TASK_DEF_ARN=$(aws ecs register-task-definition \
  --cli-input-json "file://$TASK_DEF_TMP" \
  --region "$AWS_REGION" \
  --query "taskDefinition.taskDefinitionArn" --output text)
echo "Registered: $TASK_DEF_ARN"

# ---------------------------------------------------------------------------
# Prepare service definition JSON (replace placeholders)
# ---------------------------------------------------------------------------
SVC_DEF_FILE="$PROJECT_ROOT/ecs/service-definition.json"
SVC_DEF_TMP="/tmp/resortslite-svc-def-$$.json"

sed \
  -e "s|{{CLUSTER_NAME}}|$CLUSTER_NAME|g" \
  -e "s|{{SUBNET_1}}|$SUBNET_1|g" \
  -e "s|{{SUBNET_2}}|$SUBNET_2|g" \
  -e "s|{{SECURITY_GROUP}}|$SECURITY_GROUP|g" \
  "$SVC_DEF_FILE" > "$SVC_DEF_TMP"

# Inject load balancer section if requested
if [[ "$NEED_LB" =~ ^[Yy]$ ]]; then
  python3 - <<PYEOF
import json, sys

with open("$SVC_DEF_TMP") as f:
    svc = json.load(f)

svc["loadBalancers"] = [{
    "targetGroupArn": "$TARGET_GROUP_ARN",
    "containerName": "$CONTAINER_NAME",
    "containerPort": $APP_PORT
}]
svc["healthCheckGracePeriodSeconds"] = 300

with open("$SVC_DEF_TMP", "w") as f:
    json.dump(svc, f, indent=2)
PYEOF
fi

# ---------------------------------------------------------------------------
# Create or update ECS service
# ---------------------------------------------------------------------------
echo ""
EXISTING_SERVICE=$(aws ecs describe-services \
  --cluster "$CLUSTER_NAME" \
  --services "$SERVICE_NAME" \
  --region "$AWS_REGION" \
  --query "services[?status!='INACTIVE'].serviceName" \
  --output text 2>/dev/null || echo "")

if [ -z "$EXISTING_SERVICE" ] || [ "$EXISTING_SERVICE" = "None" ]; then
  echo "Creating ECS service: $SERVICE_NAME..."
  # Inject task definition ARN into service definition
  python3 - <<PYEOF
import json
with open("$SVC_DEF_TMP") as f:
    svc = json.load(f)
svc["taskDefinition"] = "$TASK_DEF_ARN"
with open("$SVC_DEF_TMP", "w") as f:
    json.dump(svc, f, indent=2)
PYEOF
  aws ecs create-service \
    --cli-input-json "file://$SVC_DEF_TMP" \
    --region "$AWS_REGION"
else
  echo "Updating existing ECS service: $SERVICE_NAME..."
  aws ecs update-service \
    --cluster "$CLUSTER_NAME" \
    --service "$SERVICE_NAME" \
    --task-definition "$TASK_DEF_ARN" \
    --region "$AWS_REGION"
fi

# ---------------------------------------------------------------------------
# Wait for stability
# ---------------------------------------------------------------------------
echo ""
echo "Waiting for service to stabilise (this may take a few minutes)..."
aws ecs wait services-stable \
  --cluster "$CLUSTER_NAME" \
  --services "$SERVICE_NAME" \
  --region "$AWS_REGION"

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo ""
echo "=============================================="
echo "  Deployment complete!"
echo "=============================================="
aws ecs describe-services \
  --cluster "$CLUSTER_NAME" \
  --services "$SERVICE_NAME" \
  --region "$AWS_REGION" \
  --query "services[0].{Status:status,Running:runningCount,Desired:desiredCount,TaskDef:taskDefinition}" \
  --output table

echo ""
echo "CloudWatch Log Group : $LOG_GROUP"
if [ -n "$ALB_DNS" ]; then
  echo "Load Balancer DNS    : http://$ALB_DNS"
  echo "Health Check URL     : http://$ALB_DNS/actuator/health"
fi
echo ""
echo "Troubleshooting tips:"
echo "  - View logs  : aws logs tail $LOG_GROUP --follow --region $AWS_REGION"
echo "  - List tasks : aws ecs list-tasks --cluster $CLUSTER_NAME --region $AWS_REGION"
echo "  - Task detail: aws ecs describe-tasks --cluster $CLUSTER_NAME --tasks <TASK_ARN> --region $AWS_REGION"

# Cleanup temp files
rm -f "$TASK_DEF_TMP" "$SVC_DEF_TMP"
