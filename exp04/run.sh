#!/usr/bin/env bash
# EXP-04 | Ejecuta las tres partes del experimento.
#   Parte A: costo en latencia de auditar (off / sync / async)
#   Parte B: reconstruccion, inmutabilidad y cadena de hashes
#   Parte C: propagacion de la revocacion a todos los consumidores
set -uo pipefail
cd "$(dirname "$0")"
COTIZACION="${COTIZACION_URL:-http://localhost:8080}"
SIMULADOR="${SIMULADOR_URL:-http://localhost:8090}"
DURACION="${DURACION:-2m}"
mkdir -p resultados
PY="$(command -v python3 || command -v python || true)"
[[ -n "$PY" ]] || { echo "ERROR: no se encontro python ni python3"; exit 1; }
command -v k6 >/dev/null || { echo "ERROR: k6 no esta instalado o no esta en el PATH"; exit 1; }
for url in "$COTIZACION" "$SIMULADOR"; do
  curl -sf -m 5 "$url/health" >/dev/null || { echo "ERROR: $url no responde. Revisa: docker compose ps"; exit 1; }
done

curl -sf -m 10 -X POST "$SIMULADOR/admin/escenario" -H 'Content-Type: application/json' -d '{"nombre":"a_sano"}' >/dev/null
echo "========== PARTE A: costo en latencia de auditar =========="
for MODO in off sync async; do
  curl -sf -m 10 -X POST "$COTIZACION/admin/audit_mode" -H 'Content-Type: application/json' -d "{\"modo\":\"$MODO\"}" >/dev/null
  curl -sf -m 10 -X POST "$COTIZACION/admin/reset" >/dev/null
  echo "--- modo $MODO ---"
  k6 run -q -e BASE_URL="$COTIZACION" -e RPM=1000 -e ESCENARIO="audit_$MODO" -e DURACION="$DURACION" \
     ../exp01/carga.js 2>&1 | grep -E '"p95_ms"|"p99_ms"|"valido"'
  mv -f "resultados/exp01-audit_${MODO}-1000rpm.json" "resultados/exp04-parteA-${MODO}.json" 2>/dev/null \
   || mv -f "../exp01/resultados/exp01-audit_${MODO}-1000rpm.json" "resultados/exp04-parteA-${MODO}.json" 2>/dev/null
done
curl -sf -m 10 -X POST "$COTIZACION/admin/audit_mode" -H 'Content-Type: application/json' -d '{"modo":"async"}' >/dev/null
sleep 3   # deja vaciar la cola de auditoria asincrona

echo "========== PARTE B: inmutabilidad =========="
"$PY" verificar_inmutabilidad.py
echo "========== PARTE C: revocacion =========="
"$PY" medir_revocacion.py --revocaciones 20
