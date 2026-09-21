"""
EXP-03 | Escritor continuo de transacciones para medir el RPO real.
Corre DENTRO del cluster (la base tiene IP privada). Despues de cada COMMIT
anota la transaccion en Redis con su marca de tiempo; esas son las
"transacciones confirmadas" que no deben perderse tras una falla.
Los intentos fallidos tambien se registran, para medir la ventana de interrupcion.
"""
import os, time, uuid, logging
import psycopg2, redis

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("escritor_rpo")
r = redis.from_url(os.getenv("REDIS_URL", "redis://localhost:6379"), decode_responses=True)
DSN = os.environ["PG_DSN"]
INTERVALO = float(os.getenv("INTERVALO_S", "0.2"))

def main():
    cn = None
    while True:
        tid = f"tx-{uuid.uuid4().hex[:12]}"
        try:
            if cn is None or cn.closed:
                cn = psycopg2.connect(DSN, connect_timeout=3)
            with cn.cursor() as cur:
                cur.execute("INSERT INTO transaccion_pago(id_transaccion, monto, estado) "
                            "VALUES (%s, 1000, 'confirmada')", (tid,))
            cn.commit()
            r.zadd("rpo:confirmadas", {tid: time.time()})   # solo tras el commit
        except Exception as e:
            r.zadd("rpo:errores", {f"{time.time()}": time.time()})
            log.warning("escritura fallida: %s", str(e).splitlines()[0][:100])
            try:
                cn.close()
            except Exception:
                pass
            cn = None
            time.sleep(1)
        time.sleep(INTERVALO)

if __name__ == "__main__":
    main()
