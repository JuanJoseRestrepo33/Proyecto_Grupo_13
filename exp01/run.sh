#!/usr/bin/env bash
# EXP-01 | Ejecuta los 3 escenarios x 2 niveles de carga (6 corridas).
#   ./run.sh                     # 5 min por corrida
#   DURACION=1m ./run.sh         # corrida rapida de verificacion
# Funciona en Linux, macOS y Git Bash de Windows.
set -uo pipefail
cd "$(dirname "$0")"
COTIZACION="${COTIZACION_URL:-http://localhost:8080}"
SIMULADOR="${SIMULADOR_URL:-http://localhost:8090}"
DURACION="${DURACION:-5m}"
mkdir -p resultados

# En Windows suele existir "python" y no "python3"
PY="$(command -v python3 || command -v python || true)"
command -v k6 >/dev/null || { echo "ERROR: k6 no esta instalado o no esta en el PATH"; exit 1; }

# Falla rapido y con un mensaje claro si los servicios no responden
for url in "$COTIZACION" "$SIMULADOR"; do
  if ! curl -sf -m 5 "$url/health" >/dev/null; then
    echo "ERROR: $url no responde."
    echo "  - Revisa: docker compose ps   (todo debe estar Up / healthy)"
    echo "  - Revisa: docker compose logs cotizacion simulador"
    exit 1
  fi
done
echo "Servicios OK. Duracion por corrida: $DURACION"

escenario() {
  curl -sf -m 10 -X POST "$SIMULADOR/admin/escenario" \
       -H 'Content-Type: application/json' -d "{\"nombre\":\"$1\"}" >/dev/null \
    || { echo "ERROR: no se pudo cambiar el escenario a $1"; exit 1; }
}
reset() { curl -sf -m 10 -X POST "$COTIZACION/admin/reset" >/dev/null; }

precalentar() {
  echo "  precalentando cache (500 clientes)..."
  escenario a_sano; reset
  k6 run -q -e BASE_URL="$COTIZACION" precalentar.js >/dev/null 2>&1 \
    || echo "  aviso: el precalentamiento termino con errores"
}

for ESC in a_sano b_degradado c_caido; do
  for RPM in 100 1000; do
    echo "================ EXP-01 | $ESC | $RPM rpm ================"
    precalentar
    escenario "$ESC"; reset
    echo "  ejecutando carga durante $DURACION..."
    k6 run -q -e BASE_URL="$COTIZACION" -e RPM="$RPM" -e ESCENARIO="$ESC" \
       -e DURACION="$DURACION" carga.js 2>&1 | grep -E '"(p95_ms|p99_ms|pct_cache|valido|cumple_p95)"'
    estado="$(curl -sf -m 5 "$COTIZACION/health" || echo '{}')"
    if [[ -n "$PY" ]]; then
      echo "  circuito al terminar: $(echo "$estado" | "$PY" -c 'import sys,json;print(json.load(sys.stdin).get("circuito","?"))')"
    fi
  done
done
escenario a_sano; reset
echo "Simulador restaurado a a_sano. Resultados en exp01/resultados/"
