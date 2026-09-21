"""
EXP-02 | Prueba local SIN Pub/Sub, con PROCESOS reales (no hilos).

Resuelve lo que la semana 6 dejo sin concluir (R-01): con hilos de Python el
caudal no escalaba por el bloqueo global del interprete. Aqui cada consumidor
es un proceso independiente que llama a la misma funcion procesar() del
consumidor real, y la cola se sustituye por una lista de Redis.

Valida dos cosas:
  1. La idempotencia es atomica ENTRE procesos: cero pagos duplicados.
  2. El caudal escala al agregar procesos.
No reemplaza la medicion sobre Pub/Sub, que agrega su propia latencia de entrega.

  python prueba_multiproceso.py --eventos 100000 --procesos 1 2 4 8
"""
import argparse, json, os, random, sys, time
from multiprocessing import Process
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "servicios"))
import redis

COLA = "exp02:cola_local"

def trabajador():
    import consumidor_parametricos as c
    rr = redis.from_url(c.REDIS_URL, decode_responses=True)
    while True:
        item = rr.brpop(COLA, timeout=2)
        if item is None:
            return
        c.procesar(json.loads(item[1]))

def corrida(n_eventos, n_proc, pct_dup):
    import consumidor_parametricos as c
    r = c.r
    for k in ("exp02:procesados", "exp02:duplicados", "exp02:pagos", COLA):
        r.delete(k)
    for k in r.scan_iter("idem:*", count=10000):
        r.delete(k)

    ids, dup, pipe = [], 0, r.pipeline()
    for i in range(n_eventos):
        if ids and random.random() < pct_dup:
            eid = random.choice(ids[-2000:]); dup += 1
        else:
            eid = f"ev-{i}"; ids.append(eid)
        pipe.lpush(COLA, json.dumps({"id": eid, "cobertura": 500000, "factor": 1.0}))
        if i % 5000 == 0: pipe.execute()
    pipe.execute()

    t0 = time.time()
    procs = [Process(target=trabajador) for _ in range(n_proc)]
    for p in procs: p.start()
    while r.llen(COLA) > 0:
        time.sleep(0.05)
    t_vaciado = time.time() - t0
    for p in procs: p.join()

    proc, dupd, pagos = int(r.get("exp02:procesados") or 0), int(r.get("exp02:duplicados") or 0), r.scard("exp02:pagos")
    return {"procesos": n_proc, "eventos": n_eventos, "duplicados_inyectados": dup,
            "procesados": proc, "duplicados_detectados": dupd, "pagos_unicos": pagos,
            "doble_pago": proc - pagos, "perdida": (n_eventos - dup) - pagos,
            "t_vaciado_s": round(t_vaciado, 2), "caudal_ev_s": round(n_eventos / t_vaciado)}

if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--eventos", type=int, default=100_000)
    ap.add_argument("--procesos", type=int, nargs="+", default=[1, 2, 4, 8])
    ap.add_argument("--duplicados", type=float, default=0.05)
    a = ap.parse_args()
    random.seed(7)
    res = [corrida(a.eventos, n, a.duplicados) for n in a.procesos]
    base = res[0]["caudal_ev_s"]
    for x in res:
        x["aceleracion_vs_1"] = round(x["caudal_ev_s"] / base, 2)
        print(json.dumps(x, ensure_ascii=False))
    os.makedirs("resultados", exist_ok=True)
    json.dump(res, open("resultados/exp02-multiproceso-local.json", "w"), indent=2)
