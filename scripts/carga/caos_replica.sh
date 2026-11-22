#!/usr/bin/env bash
# Caos de ARQ-05 (#216): quita una réplica del núcleo a mitad de la carga y después la devuelve.
#
#   PROYECTO=… REGION=… scripts/carga/caos_replica.sh <segundos antes> <segundos con una réplica>
#
# Deja `solventa-nucleo` con mínimo y máximo 1 (una revisión nueva con una sola instancia: Cloud Run
# apaga las dos de antes al mover el tráfico) y, pasado el tiempo, lo vuelve a 2 y 2, como lo deja
# `aprovisionar`. Anota en caos.log la hora UTC de cada cambio para cruzarla con k6.
set -euo pipefail

: "${PROYECTO:?Falta PROYECTO}" "${REGION:?Falta REGION}"
antes="${1:?Falta los segundos antes del caos}"
durante="${2:?Falta los segundos con una réplica}"

escalar() {
  gcloud run services update solventa-nucleo --region="$REGION" --project="$PROYECTO" --quiet \
    --min-instances="$1" --max-instances="$1" > /dev/null
}

sleep "$antes"
echo "$(date -u +%FT%TZ) inicio: el núcleo pasa a 1 réplica" | tee -a caos.log
escalar 1
echo "$(date -u +%FT%TZ) listo: 1 réplica sirviendo" | tee -a caos.log
sleep "$durante"
echo "$(date -u +%FT%TZ) inicio: el núcleo vuelve a 2 réplicas" | tee -a caos.log
escalar 2
echo "$(date -u +%FT%TZ) listo: 2 réplicas sirviendo" | tee -a caos.log
