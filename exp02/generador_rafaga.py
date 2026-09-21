"""
EXP-02 | Publica la rafaga de eventos parametricos con duplicados deliberados.
Registra en Redis cuantos publico y cuantos duplicados inyecto, para que
medir_exp02.py pueda verificar que no se perdio ni se pago dos veces nada.

  python generador_rafaga.py --eventos 1000000 --ventana 600 --duplicados 0.05
"""
import argparse, json, os, random, time
import redis
from google.cloud import pubsub_v1

PROJECT = os.getenv("GCP_PROJECT_ID", "solventa-local")
TOPIC = os.getenv("TOPIC", "siniestros-parametricos")
r = redis.from_url(os.getenv("REDIS_URL", "redis://localhost:6379"), decode_responses=True)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--eventos", type=int, default=1_000_000)
    ap.add_argument("--ventana", type=float, default=600, help="segundos")
    ap.add_argument("--duplicados", type=float, default=0.05)
    a = ap.parse_args()

    for k in ("exp02:procesados", "exp02:duplicados", "exp02:errores", "exp02:pagos",
              "exp02:publicados", "exp02:dup_inyectados", "exp02:ultimo_ts"):
        r.delete(k)
    for k in r.scan_iter("idem:*"):
        r.delete(k)

    batch = pubsub_v1.types.BatchSettings(max_messages=1000, max_latency=0.05)
    pub = pubsub_v1.PublisherClient(batch_settings=batch)
    tp = pub.topic_path(PROJECT, TOPIC)

    t0 = time.time(); r.set("exp02:t_inicio", t0)
    ids, dup, futuros = [], 0, []
    for i in range(a.eventos):
        if ids and random.random() < a.duplicados:
            eid = random.choice(ids[-2000:]); dup += 1
        else:
            eid = f"ev-{int(t0)}-{i}"; ids.append(eid)
            if len(ids) > 10000: ids = ids[-5000:]
        payload = {"id": eid, "tipo": "vuelo_retrasado", "cobertura": 500000, "factor": 1.0}
        futuros.append(pub.publish(tp, json.dumps(payload).encode()))
        objetivo = t0 + (i + 1) * a.ventana / a.eventos
        if time.time() < objetivo:
            time.sleep(objetivo - time.time())
        if (i + 1) % 50000 == 0:
            print(f"{i+1:,} publicados | {(i+1)/(time.time()-t0):,.0f} ev/s")
    for f in futuros: f.result(timeout=60)

    r.set("exp02:publicados", a.eventos); r.set("exp02:dup_inyectados", dup)
    print(json.dumps({"publicados": a.eventos, "duplicados_inyectados": dup,
                      "duracion_publicacion_s": round(time.time() - t0, 2)}, indent=2))

if __name__ == "__main__":
    main()
