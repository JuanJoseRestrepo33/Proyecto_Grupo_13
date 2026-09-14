#!/usr/bin/env bash
# EXP-02 | Recolecta las metricas del experimento desde Cloud Monitoring.
set -euo pipefail
PROJECT="${GCP_PROJECT_ID:?define GCP_PROJECT_ID}"
SUB="${SUBSCRIPTION:-siniestros-parametricos-sub}"
DESDE="${1:-10m}"

echo "== Mensajes pendientes (profundidad de la suscripcion) =="
gcloud monitoring time-series list \
  --project="$PROJECT" \
  --filter="metric.type=\"pubsub.googleapis.com/subscription/num_undelivered_messages\" AND resource.labels.subscription_id=\"$SUB\"" \
  --format="table(points[].value.int64Value)" || true

echo "== Antiguedad del mensaje mas viejo sin confirmar =="
gcloud monitoring time-series list \
  --project="$PROJECT" \
  --filter="metric.type=\"pubsub.googleapis.com/subscription/oldest_unacked_message_age\" AND resource.labels.subscription_id=\"$SUB\"" \
  --format="table(points[].value.int64Value)" || true

echo "== Mensajes en la cola de mensajes fallidos =="
gcloud pubsub subscriptions describe "${SUB}-dlq" --project="$PROJECT" --format=json || true

echo "== Replicas activas del consumidor =="
kubectl get hpa -n solventa || true
