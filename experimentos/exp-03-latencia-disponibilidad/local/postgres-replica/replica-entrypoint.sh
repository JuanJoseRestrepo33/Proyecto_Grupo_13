#!/bin/bash
set -e

PGDATA_DIR="${PGDATA:-/var/lib/postgresql/data}"

if [ -z "$(ls -A "$PGDATA_DIR" 2>/dev/null)" ]; then
  until pg_basebackup -h postgres-primary -D "$PGDATA_DIR" -U replicator -Fp -Xs -P -R; do
    echo "esperando a que postgres-primary este listo..."
    sleep 2
  done
  chmod 700 "$PGDATA_DIR"
fi

exec docker-entrypoint.sh postgres
