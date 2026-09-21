#!/usr/bin/env bash
# Levanta los servicios sin Docker (requiere Redis y Postgres ya corriendo).
# Usa el bus en modo Redis. Para Pub/Sub, usar docker compose.
set -euo pipefail
cd "$(dirname "$0")/../servicios"
export BUS_BACKEND="${BUS_BACKEND:-redis}" REDIS_URL="${REDIS_URL:-redis://localhost:6379}"
export OPEN_FINANCE_URL="${OPEN_FINANCE_URL:-http://localhost:8090/perfil}"
export PG_DSN="${PG_DSN:-postgresql://solventa_app:CAMBIAR_EN_PRODUCCION@localhost:5432/solventa}"
mkdir -p ../logs
setsid nohup uvicorn simulador_openfinance:app --port 8090 --log-level warning > ../logs/simulador.log 2>&1 < /dev/null &
AUDIT_MODE="${AUDIT_MODE:-async}" setsid nohup uvicorn cotizacion_service:app --port 8080 --log-level warning > ../logs/cotizacion.log 2>&1 < /dev/null &
setsid nohup uvicorn perfilamiento_service:app --port 8083 --log-level warning > ../logs/perfilamiento.log 2>&1 < /dev/null &
setsid nohup uvicorn consentimiento_service:app --port 8082 --log-level warning > ../logs/consentimiento.log 2>&1 < /dev/null &
# Reintenta hasta 40 s: la libreria de Pub/Sub hace lento el primer arranque.
for p in 8090 8080 8083 8082; do
  ok=0
  for _ in $(seq 1 40); do
    curl -sf -m 2 "localhost:$p/health" >/dev/null && { ok=1; break; }
    sleep 1
  done
  [[ $ok == 1 ]] && echo "puerto $p: OK" || echo "puerto $p: FALLO (ver logs/)"
done
