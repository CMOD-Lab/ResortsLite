#!/bin/bash
set -e
set -o pipefail

# ============================================================
# deploy-image.sh — Deploy ResortsLite to Azure AKS
# Usage: ./scripts/deploy-image.sh
# Run from repository root directory
# ============================================================

APP_NAME="resortsLite"
NAMESPACE="resortsLite"
K8S_DIR="kubernetes"

echo "=============================================="
echo "  ResortsLite — Deploy to Azure AKS"
echo "=============================================="

# ---- Azure / AKS credentials ----
read -rp "Enter Azure Resource Group name: " RESOURCE_GROUP
if [ -z "$RESOURCE_GROUP" ]; then
  echo "ERROR: Resource group cannot be empty."
  exit 1
fi

read -rp "Enter AKS Cluster name: " CLUSTER_NAME
if [ -z "$CLUSTER_NAME" ]; then
  echo "ERROR: AKS cluster name cannot be empty."
  exit 1
fi

# ---- Docker image URI ----
read -rp "Enter full Docker image URI (e.g. myregistry.azurecr.io/resortsLite:latest): " IMAGE_URI
if [ -z "$IMAGE_URI" ]; then
  echo "ERROR: Image URI cannot be empty."
  exit 1
fi

echo ""
echo "---- Application Environment Variables ----"
echo "Press Enter to keep placeholder (will use default in deployment.yaml)"

read -rp "Enter REDIS_HOST (e.g. my-redis.redis.cache.windows.net): " REDIS_HOST_VAL
REDIS_HOST_VAL="${REDIS_HOST_VAL:-localhost}"

read -rp "Enter REDIS_PORT [6379]: " REDIS_PORT_VAL
REDIS_PORT_VAL="${REDIS_PORT_VAL:-6379}"

read -rsp "Enter REDIS_PASSWORD (leave blank if none): " REDIS_PASSWORD_VAL
echo ""

read -rp "Enter REPORT_BASE_PATH [/reports]: " REPORT_BASE_PATH_VAL
REPORT_BASE_PATH_VAL="${REPORT_BASE_PATH_VAL:-/reports}"

read -rp "Enter BACKUP_PATH [/backups/nightly]: " BACKUP_PATH_VAL
BACKUP_PATH_VAL="${BACKUP_PATH_VAL:-/backups/nightly}"

read -rp "Enter PAYMENT_API_URL [http://payment-service/payments/charge]: " PAYMENT_API_URL_VAL
PAYMENT_API_URL_VAL="${PAYMENT_API_URL_VAL:-http://payment-service/payments/charge}"

read -rp "Enter APP_PAYMENT_ENDPOINT [http://payment-svc.internal:9090/charge]: " APP_PAYMENT_ENDPOINT_VAL
APP_PAYMENT_ENDPOINT_VAL="${APP_PAYMENT_ENDPOINT_VAL:-http://payment-svc.internal:9090/charge}"

read -rp "Enter APP_INVENTORY_ENDPOINT [http://inventory-svc.internal:8081/rooms]: " APP_INVENTORY_ENDPOINT_VAL
APP_INVENTORY_ENDPOINT_VAL="${APP_INVENTORY_ENDPOINT_VAL:-http://inventory-svc.internal:8081/rooms}"

read -rp "Enter APP_NOTIFICATION_ENDPOINT [http://notify.internal:7070/send]: " APP_NOTIFICATION_ENDPOINT_VAL
APP_NOTIFICATION_ENDPOINT_VAL="${APP_NOTIFICATION_ENDPOINT_VAL:-http://notify.internal:7070/send}"

echo ""
echo "---- Configuring kubectl for AKS ----"
az aks get-credentials --resource-group "$RESOURCE_GROUP" --name "$CLUSTER_NAME" --overwrite-existing
echo "kubectl configured for cluster: ${CLUSTER_NAME}"

echo ""
echo "Verifying cluster connectivity ..."
kubectl cluster-info || { echo "ERROR: Cannot connect to AKS cluster."; exit 1; }

# ---- Substitute placeholders in manifests ----
echo ""
echo "---- Updating Kubernetes manifests ----"

# Work on copies to avoid modifying originals
cp "${K8S_DIR}/deployment.yaml" "${K8S_DIR}/deployment.yaml.deploy"

sed -i "s|{{IMAGE_URI}}|${IMAGE_URI}|g"                                   "${K8S_DIR}/deployment.yaml.deploy"
sed -i "s|{{REDIS_HOST}}|${REDIS_HOST_VAL}|g"                             "${K8S_DIR}/deployment.yaml.deploy"
sed -i "s|{{REDIS_PORT}}|${REDIS_PORT_VAL}|g"                             "${K8S_DIR}/deployment.yaml.deploy"
sed -i "s|{{REDIS_PASSWORD}}|${REDIS_PASSWORD_VAL}|g"                     "${K8S_DIR}/deployment.yaml.deploy"
sed -i "s|{{REPORT_BASE_PATH}}|${REPORT_BASE_PATH_VAL}|g"                 "${K8S_DIR}/deployment.yaml.deploy"
sed -i "s|{{BACKUP_PATH}}|${BACKUP_PATH_VAL}|g"                           "${K8S_DIR}/deployment.yaml.deploy"
sed -i "s|{{PAYMENT_API_URL}}|${PAYMENT_API_URL_VAL}|g"                   "${K8S_DIR}/deployment.yaml.deploy"
sed -i "s|{{APP_PAYMENT_ENDPOINT}}|${APP_PAYMENT_ENDPOINT_VAL}|g"         "${K8S_DIR}/deployment.yaml.deploy"
sed -i "s|{{APP_INVENTORY_ENDPOINT}}|${APP_INVENTORY_ENDPOINT_VAL}|g"     "${K8S_DIR}/deployment.yaml.deploy"
sed -i "s|{{APP_NOTIFICATION_ENDPOINT}}|${APP_NOTIFICATION_ENDPOINT_VAL}|g" "${K8S_DIR}/deployment.yaml.deploy"

echo "Manifests updated."

# ---- Apply manifests ----
echo ""
echo "---- Applying Kubernetes manifests ----"

echo "[1/4] Applying namespace ..."
kubectl apply -f "${K8S_DIR}/namespace.yaml"

echo "[2/4] Applying deployment ..."
kubectl apply -f "${K8S_DIR}/deployment.yaml.deploy"

echo "[3/4] Applying service ..."
kubectl apply -f "${K8S_DIR}/service.yaml"

echo "[4/4] Applying ingress ..."
kubectl apply -f "${K8S_DIR}/ingress.yaml"

# Clean up temp file
rm -f "${K8S_DIR}/deployment.yaml.deploy"

# ---- Wait for rollout ----
echo ""
echo "---- Waiting for deployment rollout ----"
kubectl rollout status deployment/"${APP_NAME}" -n "${NAMESPACE}" --timeout=300s || {
  echo ""
  echo "ERROR: Deployment rollout failed or timed out."
  echo "To rollback, run:"
  echo "  kubectl rollout undo deployment/${APP_NAME} -n ${NAMESPACE}"
  exit 1
}

# ---- Verify resources ----
echo ""
echo "---- Verifying deployed resources ----"
kubectl get pods,svc,ingress -n "${NAMESPACE}"

# ---- Display access URL ----
echo ""
INGRESS_HOST=$(kubectl get ingress "${APP_NAME}-ingress" -n "${NAMESPACE}" -o jsonpath='{.spec.rules[0].host}' 2>/dev/null || echo "resortsLite.example.com")
echo "=============================================="
echo "  Deployment Complete!"
echo "  Application URL: http://${INGRESS_HOST}"
echo "  Health Check:    http://${INGRESS_HOST}/actuator/health"
echo ""
echo "  Useful commands:"
echo "    kubectl get pods -n ${NAMESPACE}"
echo "    kubectl logs -l app=${APP_NAME} -n ${NAMESPACE} --tail=100"
echo "    kubectl rollout undo deployment/${APP_NAME} -n ${NAMESPACE}  # rollback"
echo "=============================================="
