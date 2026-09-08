#!/bin/bash
set -e

# ============================================================
# build-push.sh — Build and push Docker image for ResortsLite
# Usage: ./scripts/build-push.sh
# Run from repository root directory
# ============================================================

PROJECT_NAME="resortsLite"
DOCKERFILE_PATH="Dockerfile"

echo "=============================================="
echo "  ResortsLite — Docker Build & Push"
echo "=============================================="

# Sanitize image name: lowercase, replace non-alphanumeric with hyphens, trim hyphens
IMAGE_NAME=$(echo "$PROJECT_NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')

# Prompt for image tag
read -rp "Enter image tag [latest]: " IMAGE_TAG_INPUT
IMAGE_TAG=$(echo "${IMAGE_TAG_INPUT:-latest}" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9._-' '-' | sed 's/^-*//;s/-*$//')
if [ -z "$IMAGE_TAG" ]; then
  IMAGE_TAG="latest"
fi

echo ""
echo "Select container registry:"
echo "  1) Azure Container Registry (ACR)"
echo "  2) Docker Hub"
read -rp "Enter choice [1 or 2]: " REGISTRY_CHOICE

if [ "$REGISTRY_CHOICE" = "1" ]; then
  # ---- Azure ACR ----
  read -rp "Enter ACR name (e.g. myregistry): " ACR_NAME
  if [ -z "$ACR_NAME" ]; then
    echo "ERROR: ACR name cannot be empty."
    exit 1
  fi
  ACR_LOGIN_SERVER="${ACR_NAME}.azurecr.io"
  FULL_IMAGE_NAME="${ACR_LOGIN_SERVER}/${IMAGE_NAME}:${IMAGE_TAG}"

  echo ""
  echo "Logging in to Azure ACR: ${ACR_NAME} ..."
  az acr login --name "$ACR_NAME"

elif [ "$REGISTRY_CHOICE" = "2" ]; then
  # ---- Docker Hub ----
  read -rp "Enter Docker Hub username: " DOCKER_USERNAME
  if [ -z "$DOCKER_USERNAME" ]; then
    echo "ERROR: Docker Hub username cannot be empty."
    exit 1
  fi
  read -rsp "Enter Docker Hub password/token: " DOCKER_PASSWORD
  echo ""
  if [ -z "$DOCKER_PASSWORD" ]; then
    echo "ERROR: Docker Hub password cannot be empty."
    exit 1
  fi
  FULL_IMAGE_NAME="${DOCKER_USERNAME}/${IMAGE_NAME}:${IMAGE_TAG}"

  echo ""
  echo "Logging in to Docker Hub ..."
  echo "$DOCKER_PASSWORD" | docker login --username "$DOCKER_USERNAME" --password-stdin

else
  echo "ERROR: Invalid choice. Please enter 1 or 2."
  exit 1
fi

echo ""
echo "Building Docker image: ${FULL_IMAGE_NAME}"
echo "Build context: . (repository root)"
echo "Dockerfile: ${DOCKERFILE_PATH}"
echo "----------------------------------------------"

docker build -f "${DOCKERFILE_PATH}" -t "${FULL_IMAGE_NAME}" .

echo ""
echo "Pushing image: ${FULL_IMAGE_NAME} ..."
docker push "${FULL_IMAGE_NAME}"

echo ""
echo "=============================================="
echo "  Build & Push Complete!"
echo "  Image: ${FULL_IMAGE_NAME}"
echo "=============================================="
