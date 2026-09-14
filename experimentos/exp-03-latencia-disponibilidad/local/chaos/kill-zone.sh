#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."

ZONA="${1:-zone-a}"
DURACION_SEGUNDOS="${2:-30}"
mkdir -p chaos
SALIDA="chaos/log-zona-$(date +%s).jsonl"

poll() {
  local etiqueta="$1"
  local segundos="$2"
  local fin=$((SECONDS + segundos))
  while [ "$SECONDS" -lt "$fin" ]; do
    inicio_ms=$(date +%s%3N)
    codigo=$(curl -s -o /tmp/resp_exp03.json -w "%{http_code}" --max-time 2 "http://localhost:8080/cotizacion" || echo "000")
    fin_ms=$(date +%s%3N)
    zona=$(python3 -c "import json; print(json.load(open('/tmp/resp_exp03.json')).get('zona_atendida','-'))" 2>/dev/null || echo "-")
    echo "{\"fase\":\"$etiqueta\",\"timestamp_ms\":$inicio_ms,\"http_status\":$codigo,\"latencia_ms\":$((fin_ms - inicio_ms)),\"zona_atendida\":\"$zona\"}" | tee -a "$SALIDA"
    sleep 0.3
  done
}

echo "Baseline (10s) contra HAProxy..."
poll "baseline" 10

echo "Apagando $ZONA..."
podman compose stop "$ZONA"
apagado_en=$(date +%s%3N)
echo "{\"evento\":\"zona_apagada\",\"zona\":\"$ZONA\",\"timestamp_ms\":$apagado_en}" | tee -a "$SALIDA"

echo "Midiendo recuperacion (${DURACION_SEGUNDOS}s)..."
poll "post_falla" "$DURACION_SEGUNDOS"

echo "Restaurando $ZONA..."
podman compose start "$ZONA"

echo "Log completo en $SALIDA"
