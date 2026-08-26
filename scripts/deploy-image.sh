#!/usr/bin/env bash
# =============================================================================
# deploy-image.sh – Deploy BookingComp to AWS ECS Fargate
# Usage: bash scripts/deploy-image.sh   (run from repository root)
# =============================================================================
set -e
set -o pipefail

TASK_DEF_FILE="ecs/task-definition.json"
SERVICE_DEF_FILE="ecs/service-definition.json"
SERVICE_NAME="bookingcomp-service"
TASK_FAMILY="bookingcomp-task"
LOG_GROUP="/ecs/bookingcomp"
CONTAINER_NAME="bookingcomp"
APP_PORT="8080"

echo "=============================================="
echo "  ResortsLite BookingComp – ECS Fargate Deploy"
echo "=============================================="
echo ""

# ---------------------------------------------------------------------------
# Collect inputs
# ---------------------------------------------------------------------------
read -rp "Enter AWS Region (e.g. us-east-1): " AWS_REGION
read -rp "Enter ECS Cluster name (will be created if absent): " CLUSTER_NAME
read -rp "Enter full ECR image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/bookingcomp:latest): " IMAGE_URI
read -rp "Enter Subnet ID #1: " SUBNET_1
read -rp "Enter Subnet ID #2: " SUBNET_2
read -rp "Enter Security Group ID (must allow inbound on port ${APP_PORT}): " SECURITY_GROUP

# ---------------------------------------------------------------------------
# Resolve AWS Account ID
# ---------------------------------------------------------------------------
echo ""
echo "Resolving AWS Account ID..."
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
echo "Account ID: ${ACCOUNT_ID}"

# ---------------------------------------------------------------------------
# Ensure CloudWatch log group exists
# ---------------------------------------------------------------------------
echo "Ensuring CloudWatch log group '${LOG_GROUP}' exists..."
aws logs create-log-group --log-group-name "${LOG_GROUP}" --region "${AWS_REGION}" 2>/dev/null || true
echo "Log group ready."

# ---------------------------------------------------------------------------
# Ensure ECS cluster exists
# ---------------------------------------------------------------------------
echo "Checking ECS cluster '${CLUSTER_NAME}'..."
CLUSTER_STATUS=$(aws ecs describe-clusters --clusters "${CLUSTER_NAME}" \
  --region "${AWS_REGION}" --query "clusters[0].status" --output text 2>/dev/null || echo "MISSING")
if [[ "$CLUSTER_STATUS" != "ACTIVE" ]]; then
  echo "Creating ECS cluster '${CLUSTER_NAME}'..."
  aws ecs create-cluster --cluster-name "${CLUSTER_NAME}" --region "${AWS_REGION}"
fi
echo "Cluster ready."

# ---------------------------------------------------------------------------
# Load balancer (optional)
# ---------------------------------------------------------------------------
echo ""
read -rp "Do you need an Application Load Balancer for this service? (y/n): " NEED_LB

TARGET_GROUP_ARN=""
LB_DNS=""
if [[ "$NEED_LB" =~ ^[Yy]$ ]]; then
  read -rp "Enter VPC ID for the load balancer: " VPC_ID

  echo "Creating Application Load Balancer..."
  LB_ARN=$(aws elbv2 create-load-balancer \
    --name "bookingcomp-alb" \
    --subnets "${SUBNET_1}" "${SUBNET_2}" \
    --security-groups "${SECURITY_GROUP}" \
    --scheme internet-facing \
    --type application \
    --region "${AWS_REGION}" \
    --query "LoadBalancers[0].LoadBalancerArn" --output text)
  LB_DNS=$(aws elbv2 describe-load-balancers \
    --load-balancer-arns "${LB_ARN}" \
    --region "${AWS_REGION}" \
    --query "LoadBalancers[0].DNSName" --output text)
  echo "ALB created: ${LB_DNS}"

  echo "Creating Target Group (type=ip for Fargate awsvpc)..."
  TARGET_GROUP_ARN=$(aws elbv2 create-target-group \
    --name "bookingcomp-tg" \
    --protocol HTTP \
    --port "${APP_PORT}" \
    --vpc-id "${VPC_ID}" \
    --target-type ip \
    --health-check-path "/actuator/health" \
    --health-check-interval-seconds 30 \
    --healthy-threshold-count 2 \
    --unhealthy-threshold-count 3 \
    --region "${AWS_REGION}" \
    --query "TargetGroups[0].TargetGroupArn" --output text)
  echo "Target Group ARN: ${TARGET_GROUP_ARN}"

  echo "Creating ALB Listener on port 80..."
  aws elbv2 create-listener \
    --load-balancer-arn "${LB_ARN}" \
    --protocol HTTP \
    --port 80 \
    --default-actions "Type=forward,TargetGroupArn=${TARGET_GROUP_ARN}" \
    --region "${AWS_REGION}" >/dev/null
fi

# ---------------------------------------------------------------------------
# Substitute placeholders in task definition
# ---------------------------------------------------------------------------
echo ""
echo "Preparing task definition..."
cp "${TASK_DEF_FILE}" /tmp/task-definition-deploy.json
sed -i "s|{{ACCOUNT_ID}}|${ACCOUNT_ID}|g"   /tmp/task-definition-deploy.json
sed -i "s|{{AWS_REGION}}|${AWS_REGION}|g"   /tmp/task-definition-deploy.json
sed -i "s|{{IMAGE_URI}}|${IMAGE_URI}|g"     /tmp/task-definition-deploy.json

# ---------------------------------------------------------------------------
# Register task definition
# ---------------------------------------------------------------------------
echo "Registering task definition '${TASK_FAMILY}'..."
TASK_DEF_ARN=$(aws ecs register-task-definition \
  --cli-input-json file:///tmp/task-definition-deploy.json \
  --region "${AWS_REGION}" \
  --query "taskDefinition.taskDefinitionArn" --output text)
echo "Task Definition ARN: ${TASK_DEF_ARN}"

# ---------------------------------------------------------------------------
# Prepare service definition
# ---------------------------------------------------------------------------
cp "${SERVICE_DEF_FILE}" /tmp/service-definition-deploy.json
sed -i "s|{{CLUSTER_NAME}}|${CLUSTER_NAME}|g"     /tmp/service-definition-deploy.json
sed -i "s|{{SUBNET_1}}|${SUBNET_1}|g"             /tmp/service-definition-deploy.json
sed -i "s|{{SUBNET_2}}|${SUBNET_2}|g"             /tmp/service-definition-deploy.json
sed -i "s|{{SECURITY_GROUP}}|${SECURITY_GROUP}|g" /tmp/service-definition-deploy.json

# Inject load balancer block if requested
if [[ "$NEED_LB" =~ ^[Yy]$ ]]; then
  python3 - <<PYEOF
import json, sys
with open('/tmp/service-definition-deploy.json') as f:
    svc = json.load(f)
svc['loadBalancers'] = [{
    'targetGroupArn': '${TARGET_GROUP_ARN}',
    'containerName': '${CONTAINER_NAME}',
    'containerPort': ${APP_PORT}
}]
svc['healthCheckGracePeriodSeconds'] = 300
with open('/tmp/service-definition-deploy.json', 'w') as f:
    json.dump(svc, f, indent=2)
PYEOF
fi

# ---------------------------------------------------------------------------
# Create or update ECS service
# ---------------------------------------------------------------------------
echo ""
EXISTING_SERVICE=$(aws ecs describe-services \
  --cluster "${CLUSTER_NAME}" \
  --services "${SERVICE_NAME}" \
  --region "${AWS_REGION}" \
  --query "services[?status=='ACTIVE'].serviceName" \
  --output text 2>/dev/null || echo "")

if [[ -z "$EXISTING_SERVICE" || "$EXISTING_SERVICE" == "None" ]]; then
  echo "Creating ECS service '${SERVICE_NAME}'..."
  aws ecs create-service \
    --cli-input-json file:///tmp/service-definition-deploy.json \
    --region "${AWS_REGION}"
else
  echo "Updating existing ECS service '${SERVICE_NAME}'..."
  aws ecs update-service \
    --cluster "${CLUSTER_NAME}" \
    --service "${SERVICE_NAME}" \
    --task-definition "${TASK_DEF_ARN}" \
    --region "${AWS_REGION}"
fi

# ---------------------------------------------------------------------------
# Wait for stability
# ---------------------------------------------------------------------------
echo ""
echo "Waiting for service to reach stable state (this may take a few minutes)..."
aws ecs wait services-stable \
  --cluster "${CLUSTER_NAME}" \
  --services "${SERVICE_NAME}" \
  --region "${AWS_REGION}"

# ---------------------------------------------------------------------------
# Verify
# ---------------------------------------------------------------------------
echo ""
echo "Deployment complete. Service details:"
aws ecs describe-services \
  --cluster "${CLUSTER_NAME}" \
  --services "${SERVICE_NAME}" \
  --region "${AWS_REGION}" \
  --query "services[0].{Status:status,Running:runningCount,Desired:desiredCount,TaskDef:taskDefinition}" \
  --output table

echo ""
echo "CloudWatch Logs: ${LOG_GROUP}"
if [[ -n "$LB_DNS" ]]; then
  echo "Load Balancer DNS: http://${LB_DNS}"
  echo "Health Check URL : http://${LB_DNS}/actuator/health"
fi
echo ""
echo "Troubleshooting tips:"
echo "  - View stopped tasks : aws ecs list-tasks --cluster ${CLUSTER_NAME} --desired-status STOPPED --region ${AWS_REGION}"
echo "  - Task failure reason: aws ecs describe-tasks --cluster ${CLUSTER_NAME} --tasks <TASK_ARN> --region ${AWS_REGION}"
echo "  - CloudWatch logs    : aws logs tail ${LOG_GROUP} --follow --region ${AWS_REGION}"
echo "=============================================="
