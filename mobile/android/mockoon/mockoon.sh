#!/usr/bin/env bash
# BFF móvil simulado con Mockoon: registro, OTP, sesión e identidad (WEB-F13, #154).
#
#   mobile/android/mockoon/mockoon.sh            # escucha en http://localhost:3001/api/v1/movil/
#   mobile/android/mockoon/mockoon.sh prueba     # arranca con tiempos cortos y corre la prueba de humo
#
# Variables (todas opcionales): PUERTO (3001), MOCKOON_OTP_FIJO (123456, o "aleatorio" para leerlo
# del buzón), MOCKOON_TTL_ACCESO (900 s), MOCKOON_VIGENCIA_OTP (600 s), MOCKOON_ESPERA_REENVIO (30 s).
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
PUERTO="${PUERTO:-3001}"
MOCKOON=(npx --yes @mockoon/cli@9.9.0 start --data "$DIR/bff-movil.json" --port "$PUERTO")

if [[ "${1:-}" != "prueba" ]]; then
    exec "${MOCKOON[@]}"
fi

export MOCKOON_ESPERA_REENVIO=1 MOCKOON_VIGENCIA_OTP=3 MOCKOON_TTL_ACCESO=2 MOCKOON_OTP_FIJO=aleatorio
REGISTRO="$(mktemp)"
"${MOCKOON[@]}" > "$REGISTRO" 2>&1 &
PID=$!
trap 'kill "$PID" 2>/dev/null || true; rm -f "$REGISTRO"' EXIT
for _ in $(seq 120); do
    grep -q "Server started" "$REGISTRO" && break
    kill -0 "$PID" 2>/dev/null || { cat "$REGISTRO"; exit 1; }
    sleep 0.5
done
grep -q "Server started" "$REGISTRO" || { cat "$REGISTRO"; exit 1; }
python3 -I "$DIR/probar_mockoon.py" "http://localhost:$PUERTO/api/v1/movil/"
