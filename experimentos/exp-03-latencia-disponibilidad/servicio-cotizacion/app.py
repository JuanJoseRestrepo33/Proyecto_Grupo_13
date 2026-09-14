import os
import socket
import time
from datetime import datetime, timezone

import psycopg
from fastapi import FastAPI, HTTPException
from psycopg.rows import dict_row

ZONE = os.environ.get("ZONE", "unknown")
DATABASE_URL = os.environ.get("DATABASE_URL", "postgresql://solventa:solventa@localhost:5432/solventa")

app = FastAPI(title="Servicio de Cotizacion - EXP-03")


def get_connection():
    return psycopg.connect(DATABASE_URL, connect_timeout=2, row_factory=dict_row)


@app.on_event("startup")
def ensure_schema():
    for attempt in range(10):
        try:
            with get_connection() as conn:
                conn.execute(
                    """
                    CREATE TABLE IF NOT EXISTS cotizaciones (
                        id SERIAL PRIMARY KEY,
                        cliente TEXT NOT NULL,
                        ramo TEXT NOT NULL,
                        prima NUMERIC NOT NULL,
                        zona_atendida TEXT NOT NULL,
                        creado_en TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """
                )
            return
        except psycopg.OperationalError:
            time.sleep(2 * (attempt + 1))
    raise RuntimeError("No se pudo conectar a la base de datos primaria en el arranque")


@app.get("/health")
def health():
    return {"status": "ok", "zone": ZONE, "hostname": socket.gethostname()}


@app.get("/cotizacion")
def cotizar(cliente: str = "cliente-demo", ramo: str = "hogar"):
    prima = 45000 + (hash(cliente + ramo) % 15000)
    try:
        with get_connection() as conn:
            row = conn.execute(
                """
                INSERT INTO cotizaciones (cliente, ramo, prima, zona_atendida)
                VALUES (%s, %s, %s, %s)
                RETURNING id, creado_en
                """,
                (cliente, ramo, prima, ZONE),
            ).fetchone()
    except psycopg.OperationalError as exc:
        raise HTTPException(status_code=503, detail=f"base de datos no disponible: {exc}") from exc

    return {
        "id": row["id"],
        "cliente": cliente,
        "ramo": ramo,
        "prima": prima,
        "zona_atendida": ZONE,
        "servido_en": datetime.now(timezone.utc).isoformat(),
    }


@app.get("/cotizaciones/count")
def contar():
    with get_connection() as conn:
        row = conn.execute("SELECT count(*) AS total FROM cotizaciones").fetchone()
    return {"total": row["total"], "zona_atendida": ZONE}
