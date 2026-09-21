#!/usr/bin/env bash
# ============================================================================
# EXP-02 | Barrido de replicas del consumidor frente a la rafaga.
#
#   MODO=local ./run.sh     # docker compose + emulador de Pub/Sub
#   MODO=gke   ./run.sh     # cluster real en GKE
#
# Local valida la CORRECCION (cero perdida, cero doble pago). El emulador de
# Pub/Sub no esta hecho para rendimiento, asi que el ESCALAMIENTO solo es
# concluyente en GKE.
# ============================================================================
set -uo pipefail
cd "$(dirname "$0")"
MODO="${MODO:-local}"
REPLICAS="${REPLICAS:-2 4 8 12}"
EVENTOS="${EVENTOS:-1000000}"
VENTANA="${VENTANA:-600}"
[[ "$MODO" == "local" ]] && EVENTOS="${EVENTOS_LOCAL:-50000}" && VENTANA="${VENTANA_LOCAL:-60}"
mkdir -p resultados

ejecutar() {   # corre un script de exp02 donde tenga acceso a Redis y Pub/Sub
  if [[ "$MODO" == "gke" ]]; then
    kubectl exec -n solventa deploy/herramientas -- python "/app/exp02/$1" "${@:2}"
  else
    docker compose -f ../docker-compose.yml run --rm -w /app/exp02 consumidor python "$1" "${@:2}"
  fi
}

for N in $REPLICAS; do
  echo "================ EXP-02 | $N replicas | $EVENTOS eventos en ${VENTANA}s ================"
  if [[ "$MODO" == "gke" ]]; then
    kubectl scale deploy/consumidor -n solventa --replicas="$N"
    kubectl rollout status deploy/consumidor -n solventa --timeout=300s
  else
    docker compose -f ../docker-compose.yml up -d --scale consumidor="$N" consumidor
    sleep 5
  fi
  ejecutar generador_rafaga.py --eventos "$EVENTOS" --ventana "$VENTANA" --duplicados 0.05
  ejecutar medir_exp02.py --meta-s "$VENTANA" | tee "resultados/exp02-${MODO}-${N}rep.json"
done
