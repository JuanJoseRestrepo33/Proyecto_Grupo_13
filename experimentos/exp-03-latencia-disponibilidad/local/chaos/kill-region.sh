#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."

DURACION_SEGUNDOS="${1:-60}"
mkdir -p chaos
SALIDA="chaos/log-region-$(date +%s).jsonl"

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

echo "Contando transacciones confirmadas en la region primaria antes de la falla..."
CONTEO_ANTES=$(podman compose exec -T postgres-primary psql -U solventa -d solventa -t -c "SELECT count(*) FROM cotizaciones;" | tr -d '[:space:]')
echo "{\"evento\":\"conteo_antes_de_falla\",\"total\":$CONTEO_ANTES}" | tee -a "$SALIDA"

echo "Baseline (10s)..."
poll "baseline" 10

echo "Simulando caida de la region primaria (zonas + base de datos primaria)..."
podman compose stop zone-a zone-b zone-c postgres-primary
apagado_en=$(date +%s%3N)
echo "{\"evento\":\"region_primaria_apagada\",\"timestamp_ms\":$apagado_en}" | tee -a "$SALIDA"

echo "Midiendo intentos hasta que alguien promueva el respaldo (15s)..."
poll "region_caida_sin_promover" 15

echo "Promoviendo la replica (region de respaldo) a lectura-escritura..."
podman compose exec -T postgres-replica psql -U solventa -d solventa -c "SELECT pg_promote();"
promovido_en=$(date +%s%3N)
echo "{\"evento\":\"replica_promovida\",\"timestamp_ms\":$promovido_en}" | tee -a "$SALIDA"

echo "Midiendo recuperacion tras la promocion (${DURACION_SEGUNDOS}s)..."
poll "post_promocion" "$DURACION_SEGUNDOS"

echo "Contando transacciones en la region de respaldo tras la promocion..."
CONTEO_DESPUES=$(podman compose exec -T postgres-replica psql -U solventa -d solventa -t -c "SELECT count(*) FROM cotizaciones;" | tr -d '[:space:]')
echo "{\"evento\":\"conteo_tras_promocion\",\"total\":$CONTEO_DESPUES,\"transacciones_perdidas\":$((CONTEO_ANTES - CONTEO_DESPUES))}" | tee -a "$SALIDA"

echo "Log completo en $SALIDA"
echo "NOTA: la replica ya quedo promovida (no es un standby valido). Para reiniciar el ambiente: podman compose down -v && podman compose up -d --build"
