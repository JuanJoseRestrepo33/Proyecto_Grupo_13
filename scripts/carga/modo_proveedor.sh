#!/usr/bin/env bash
# Pone el proveedor simulado de Open Finance del núcleo de staging en un modo (ARQ-05).
#
#   PROYECTO=… REGION=… scripts/carga/modo_proveedor.sh {sano|degradado|caido}
#
# Cambia solo PERFILAMIENTO_OPEN_FINANCE_MODO (`--update-env-vars`, sin tocar lo demás) y espera a
# que la revisión nueva esté lista. Si ya está en ese modo, no crea otra revisión.
set -euo pipefail

modo="${1:-}"
case "$modo" in
  sano | degradado | caido) ;;
  *)
    echo "::error::Modo no válido: usa sano, degradado o caido" >&2
    exit 2
    ;;
esac
: "${PROYECTO:?Falta PROYECTO}" "${REGION:?Falta REGION}"

actual=$(
  gcloud run services describe solventa-nucleo --region="$REGION" --project="$PROYECTO" \
    --format=json | python3 -c '
import json, sys
env = json.load(sys.stdin)["spec"]["template"]["spec"]["containers"][0].get("env", [])
print(next((e.get("value", "") for e in env if e["name"] == "PERFILAMIENTO_OPEN_FINANCE_MODO"), "sano"))
'
)

if [[ "$actual" == "$modo" ]]; then
  echo "El proveedor de Open Finance ya está en «$modo»: no se crea otra revisión"
  exit 0
fi

gcloud run services update solventa-nucleo --region="$REGION" --project="$PROYECTO" --quiet \
  --update-env-vars="PERFILAMIENTO_OPEN_FINANCE_MODO=$modo"
echo "Proveedor de Open Finance: «$actual» → «$modo»"
