#!/usr/bin/env bash
# ============================================================================
# Construye la imagen, la sube al registro y despliega los servicios en GKE.
# Lee IPs y nombres desde las salidas de Terraform.
#
#   export PROJECT=mi-proyecto
#   export DB_ADMIN_PASSWORD=...   # el mismo de TF_VAR_db_password_admin
#   export DB_APP_PASSWORD=...     # contrasena nueva para el rol de la aplicacion
#   ./scripts/desplegar_gke.sh                         # cluster primario
#   CLUSTER_DESTINO=respaldo ./scripts/desplegar_gke.sh # cluster de respaldo
# ============================================================================
set -euo pipefail
RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
: "${PROJECT:?define PROJECT}" "${DB_ADMIN_PASSWORD:?define DB_ADMIN_PASSWORD}" "${DB_APP_PASSWORD:?define DB_APP_PASSWORD}"
REGION_P="${REGION_PRIMARIA:-southamerica-east1}"
REGION_R="${REGION_RESPALDO:-us-east1}"
DESTINO="${CLUSTER_DESTINO:-primario}"

tf() { terraform -chdir="$RAIZ/terraform" output -raw "$1"; }
REGISTRO="$(tf registro_imagenes)"
IMAGEN="${IMAGEN:-$REGISTRO/servicios:latest}"

if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
  echo ">>> Construyendo y subiendo la imagen $IMAGEN"
  gcloud builds submit "$RAIZ" --tag "$IMAGEN" --project "$PROJECT"
fi

if [[ "$DESTINO" == "respaldo" ]]; then
  gcloud container clusters get-credentials solventa-respaldo --zone "${REGION_R}-a" --project "$PROJECT"
  DB_HOST="${DB_HOST_OVERRIDE:-$(tf db_ip_replica)}"
  REDIS_HOST="$(tf redis_host_respaldo)"
else
  gcloud container clusters get-credentials solventa-primario --region "$REGION_P" --project "$PROJECT"
  DB_HOST="$(tf db_ip_privada)"
  REDIS_HOST="$(tf redis_host)"
fi

kubectl create namespace solventa --dry-run=client -o yaml | kubectl apply -f -
kubectl -n solventa create secret generic solventa-db \
  --from-literal=admin_password="$DB_ADMIN_PASSWORD" \
  --from-literal=app_password="$DB_APP_PASSWORD" \
  --from-literal=app_dsn="postgresql://solventa_app:${DB_APP_PASSWORD}@${DB_HOST}:5432/solventa" \
  --from-literal=admin_dsn="postgresql://solventa_admin:${DB_ADMIN_PASSWORD}@${DB_HOST}:5432/solventa" \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n solventa create configmap solventa-sql --from-file=init.sql="$RAIZ/db/init.sql" \
  --dry-run=client -o yaml | kubectl apply -f -

export IMAGEN PROJECT_ID="$PROJECT" REDIS_HOST DB_HOST
kubectl -n solventa delete job init-db --ignore-not-found
envsubst '${IMAGEN} ${PROJECT_ID} ${REDIS_HOST} ${DB_HOST}' < "$RAIZ/k8s/servicios.yaml" | kubectl apply -f -

echo ">>> Esperando la inicializacion de la base y los despliegues"
kubectl -n solventa wait --for=condition=complete job/init-db --timeout=300s
for d in simulador cotizacion perfilamiento consentimiento consumidor escritor-rpo herramientas; do
  kubectl -n solventa rollout status "deploy/$d" --timeout=300s
done

echo ">>> Esperando la IP publica del balanceador"
IP=""
until [[ -n "$IP" ]]; do
  IP=$(kubectl -n solventa get svc cotizacion -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || true)
  sleep 5
done
echo "LISTO. Cotizacion disponible en: http://$IP"
