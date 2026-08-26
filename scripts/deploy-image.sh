#!/bin/bash
set -e
set -o pipefail

APP_NAME="bookingcomp"
NAMESPACE="bookingcomp"

echo "============================================"
echo "  BookingComp - Deploy to AWS EKS"
echo "============================================"
echo ""

# ── Collect deployment inputs ────────────────────────────────────────────────
read -rp "Enter AWS region (e.g. us-east-1): " AWS_REGION
if [ -z "$AWS_REGION" ]; then
  echo "ERROR: AWS region is required."
  exit 1
fi

read -rp "Enter EKS cluster name: " CLUSTER_NAME
if [ -z "$CLUSTER_NAME" ]; then
  echo "ERROR: EKS cluster name is required."
  exit 1
fi

read -rp "Enter full Docker image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/bookingcomp:latest): " IMAGE_URI
if [ -z "$IMAGE_URI" ]; then
  echo "ERROR: Docker image URI is required."
  exit 1
fi

echo ""
echo "--- Optional: Application Environment Variables ---"
echo "(Press Enter to keep placeholder / skip)"

read -rp "Enter REDIS_HOST (e.g. my-cluster.abc123.ng.0001.use1.cache.amazonaws.com): " INPUT_REDIS_HOST
REDIS_HOST="${INPUT_REDIS_HOST:-localhost}"

read -rp "Enter REDIS_PORT (default 6379): " INPUT_REDIS_PORT
REDIS_PORT="${INPUT_REDIS_PORT:-6379}"

read -rsp "Enter REDIS_PASSWORD (leave blank if none): " INPUT_REDIS_PASSWORD
echo ""
REDIS_PASSWORD="${INPUT_REDIS_PASSWORD:-}"

read -rp "Enter REPORT_BASE_PATH (default /var/reports): " INPUT_REPORT_BASE_PATH
REPORT_BASE_PATH="${INPUT_REPORT_BASE_PATH:-/var/reports}"

read -rp "Enter BACKUP_PATH (default /var/backups/nightly): " INPUT_BACKUP_PATH
BACKUP_PATH="${INPUT_BACKUP_PATH:-/var/backups/nightly}"

read -rp "Enter PAYMENT_API_URL (default http://payment-service/payments/charge): " INPUT_PAYMENT_API_URL
PAYMENT_API_URL="${INPUT_PAYMENT_API_URL:-http://payment-service/payments/charge}"

# ── Configure kubectl for EKS ────────────────────────────────────────────────
echo ""
echo "Configuring kubectl for EKS cluster: $CLUSTER_NAME in $AWS_REGION ..."
aws eks update-kubeconfig --region "$AWS_REGION" --name "$CLUSTER_NAME"

echo "Verifying cluster connectivity..."
kubectl cluster-info || { echo "ERROR: Cannot connect to EKS cluster."; exit 1; }

# ── Patch Kubernetes manifests ───────────────────────────────────────────────
echo ""
echo "Updating Kubernetes manifests with deployment values..."

# Work on copies so originals keep placeholders for future runs
cp kubernetes/deployment.yaml /tmp/deployment-deploy.yaml

sed -i "s|{{IMAGE_URI}}|${IMAGE_URI}|g"                   /tmp/deployment-deploy.yaml
sed -i "s|{{REDIS_HOST}}|${REDIS_HOST}|g"                 /tmp/deployment-deploy.yaml
sed -i "s|{{REDIS_PORT}}|${REDIS_PORT}|g"                 /tmp/deployment-deploy.yaml
sed -i "s|{{REDIS_PASSWORD}}|${REDIS_PASSWORD}|g"         /tmp/deployment-deploy.yaml
sed -i "s|{{REPORT_BASE_PATH}}|${REPORT_BASE_PATH}|g"     /tmp/deployment-deploy.yaml
sed -i "s|{{BACKUP_PATH}}|${BACKUP_PATH}|g"               /tmp/deployment-deploy.yaml
sed -i "s|{{PAYMENT_API_URL}}|${PAYMENT_API_URL}|g"       /tmp/deployment-deploy.yaml

# ── Apply manifests ──────────────────────────────────────────────────────────
echo ""
echo "Applying Kubernetes manifests..."

echo "  [1/4] Applying namespace..."
kubectl apply -f kubernetes/namespace.yaml

echo "  [2/4] Applying deployment..."
kubectl apply -f /tmp/deployment-deploy.yaml

echo "  [3/4] Applying service..."
kubectl apply -f kubernetes/service.yaml

echo "  [4/4] Applying ingress..."
kubectl apply -f kubernetes/ingress.yaml

# ── Wait for rollout ─────────────────────────────────────────────────────────
echo ""
echo "Waiting for deployment rollout to complete..."
kubectl rollout status deployment/"$APP_NAME" -n "$NAMESPACE" --timeout=300s || {
  echo ""
  echo "ERROR: Deployment rollout timed out or failed."
  echo "To rollback, run: kubectl rollout undo deployment/$APP_NAME -n $NAMESPACE"
  exit 1
}

# ── Verify resources ─────────────────────────────────────────────────────────
echo ""
echo "Verifying deployed resources..."
kubectl get pods,svc,ingress -n "$NAMESPACE"

# ── Display access URL ───────────────────────────────────────────────────────
echo ""
INGRESS_HOST=$(kubectl get ingress "${APP_NAME}-ingress" -n "$NAMESPACE" \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || echo "pending")

echo "============================================"
echo "  Deployment Complete!"
echo "  Namespace : $NAMESPACE"
echo "  Image     : $IMAGE_URI"
if [ "$INGRESS_HOST" != "pending" ] && [ -n "$INGRESS_HOST" ]; then
  echo "  App URL   : http://$INGRESS_HOST"
else
  echo "  App URL   : (ALB hostname pending — check 'kubectl get ingress -n $NAMESPACE')"
fi
echo ""
echo "  Rollback  : kubectl rollout undo deployment/$APP_NAME -n $NAMESPACE"
echo "============================================"
