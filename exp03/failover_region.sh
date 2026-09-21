#!/usr/bin/env bash
# ============================================================================
# EXP-03 | Caida de region completa: ejecuta y CRONOMETRA el procedimiento de
# recuperacion en la region de respaldo. Requiere la Topologia B desplegada.
#
# El tiempo de recuperacion ante caida de region es, en la practica, el tiempo
# que toma ejecutar este procedimiento. Cada paso queda cronometrado para ver
# cual domina (la proyeccion espera que sea la promocion de la replica).
#
# ADVERTENCIA: promover la replica es IRREVERSIBLE. Ejecutar solo al final de
# las pruebas y hacer terraform destroy despues.
#
#   PROJECT=mi-proyecto ./failover_region.sh
# ============================================================================
set -euo pipefail
: "${PROJECT:?define PROJECT}"
REGION_P="${REGION_PRIMARIA:-southamerica-east1}"
REGION_R="${REGION_RESPALDO:-us-east1}"
ZONA_R="${ZONA_RESPALDO:-${REGION_R}-a}"
RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$RAIZ/exp03/resultados"

marca() { date +%s.%N; }
dur()   { python3 -c "print(round($2-$1,1))"; }

read -rp "Esto promueve la replica de forma IRREVERSIBLE. Escribe SI para continuar: " ok
[[ "$ok" == "SI" ]] || { echo "cancelado"; exit 1; }

T0=$(marca)
echo ">>> [1/4] Simulando perdida de la region primaria"
for pool in $(gcloud container node-pools list --cluster solventa-primario --region "$REGION_P" \
               --project "$PROJECT" --format='value(name)'); do
  gcloud container clusters resize solventa-primario --node-pool "$pool" --num-nodes 0 \
    --region "$REGION_P" --project "$PROJECT" --quiet --async
done
gcloud sql instances patch solventa-db-principal --activation-policy NEVER --project "$PROJECT" --quiet
T1=$(marca)

echo ">>> [2/4] Promoviendo la replica de la region de respaldo"
gcloud sql instances promote-replica solventa-db-replica --project "$PROJECT" --quiet
T2=$(marca)

echo ">>> [3/4] Desplegando los servicios en el cluster de respaldo"
DB_HOST=$(gcloud sql instances describe solventa-db-replica --project "$PROJECT" \
          --format='value(ipAddresses[0].ipAddress)')
gcloud container clusters get-credentials solventa-respaldo --zone "$ZONA_R" --project "$PROJECT"
CLUSTER_DESTINO=respaldo DB_HOST_OVERRIDE="$DB_HOST" "$RAIZ/scripts/desplegar_gke.sh"
T3=$(marca)

echo ">>> [4/4] Esperando que el balanceador de respaldo atienda"
IP=""
until [[ -n "$IP" ]]; do
  IP=$(kubectl get svc cotizacion -n solventa -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || true)
  sleep 5
done
until curl -sf -m 3 -X POST "http://$IP/cotizar" -H 'Content-Type: application/json' -d '{"cliente":"failover"}' >/dev/null; do
  sleep 2
done
T4=$(marca)

cat > "$RAIZ/exp03/resultados/exp03-region-$(date +%s).json" << JSON
{
  "experimento": "EXP-03", "medicion": "caida de region",
  "simular_perdida_s": $(dur "$T0" "$T1"),
  "promocion_replica_s": $(dur "$T1" "$T2"),
  "despliegue_respaldo_s": $(dur "$T2" "$T3"),
  "balanceador_listo_s": $(dur "$T3" "$T4"),
  "rto_total_s": $(dur "$T0" "$T4"),
  "meta_rto_s": 300,
  "cumple_rto": $(python3 -c "print(str(($T4-$T0)<=300).lower())"),
  "endpoint_respaldo": "http://$IP"
}
JSON
cat "$RAIZ"/exp03/resultados/exp03-region-*.json | tail -12
echo "Medir el RPO sobre la replica promovida con verificar_rpo.py (ver README)."
