"""
EXP-03 | Verifica cuantas transacciones confirmadas antes de la falla
sobrevivieron, y mide la ventana de interrupcion de escritura.
Corre dentro del cluster (kubectl exec en el pod de herramientas).

  python verificar_rpo.py --ts-falla 1726000000.0 --ventana-s 120
"""
import argparse, json, os, time
import psycopg2, redis

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ts-falla", type=float, required=True, help="epoch de la falla (lo imprime caos_failover.py)")
    ap.add_argument("--ventana-s", type=float, default=120, help="segundos antes de la falla a verificar")
    ap.add_argument("--dsn", default=os.environ.get("PG_DSN"))
    a = ap.parse_args()

    r = redis.from_url(os.getenv("REDIS_URL", "redis://localhost:6379"), decode_responses=True)
    confirmadas = r.zrangebyscore("rpo:confirmadas", a.ts_falla - a.ventana_s, a.ts_falla, withscores=True)

    cn = psycopg2.connect(a.dsn, connect_timeout=10)
    cur = cn.cursor()
    perdidas = []
    for tid, ts in confirmadas:
        cur.execute("SELECT 1 FROM transaccion_pago WHERE id_transaccion = %s", (tid,))
        if cur.fetchone() is None:
            perdidas.append((tid, ts))

    errores = [ts for _, ts in r.zrangebyscore("rpo:errores", a.ts_falla - 5, "+inf", withscores=True)]
    ok_tras = r.zrangebyscore("rpo:confirmadas", max(errores) if errores else a.ts_falla, "+inf",
                              start=0, num=1, withscores=True)
    ventana = round(ok_tras[0][1] - min(errores), 2) if errores and ok_tras else 0.0

    rpo_s = round(a.ts_falla - min(ts for _, ts in perdidas), 2) if perdidas else 0.0
    res = {
        "experimento": "EXP-03", "medicion": "RPO",
        "confirmadas_verificadas": len(confirmadas),
        "transacciones_perdidas": len(perdidas),
        "rpo_s": rpo_s,
        "ventana_sin_escritura_s": ventana,
        "intentos_fallidos": len(errores),
        "meta_rpo_s": 30,
    }
    res["valido"] = len(confirmadas) > 0
    res["cumple_rpo"] = res["valido"] and rpo_s <= 30
    print(json.dumps(res, indent=2, ensure_ascii=False))
    os.makedirs("resultados", exist_ok=True)
    json.dump(res, open(f"resultados/exp03-rpo-{int(time.time())}.json", "w"), indent=2)

if __name__ == "__main__":
    main()
