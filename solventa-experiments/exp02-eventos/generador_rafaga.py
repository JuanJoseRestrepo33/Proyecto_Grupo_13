"""
EXP-02 | Generador de la rafaga de eventos parametricos.

Publica 1.000.000 de eventos en una ventana de 10 minutos (~1.667 ev/s),
inyectando deliberadamente un 5% de duplicados para ejercitar la
verificacion de idempotencia.

Ejecutar:
  python generador_rafaga.py --eventos 1000000 --ventana 600 --duplicados 0.05
"""
import argparse, json, os, random, time, threading
from concurrent.futures import ThreadPoolExecutor
from google.cloud import pubsub_v1

PROJECT_ID = os.environ["GCP_PROJECT_ID"]
TOPIC = os.getenv("TOPIC", "siniestros-parametricos")

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--eventos", type=int, default=1_000_000)
    ap.add_argument("--ventana", type=int, default=600, help="segundos")
    ap.add_argument("--duplicados", type=float, default=0.05)
    a = ap.parse_args()

    # Agrupar mensajes reduce el costo: la unidad minima de facturacion es 1 KB
    settings = pubsub_v1.types.BatchSettings(max_messages=1000, max_latency=0.1)
    publisher = pubsub_v1.PublisherClient(batch_settings=settings)
    topic_path = publisher.topic_path(PROJECT_ID, TOPIC)

    emitidos, ids = 0, []
    t0 = time.time()
    intervalo = a.ventana / a.eventos

    for i in range(a.eventos):
        if ids and random.random() < a.duplicados:
            eid = random.choice(ids[-1000:])          # duplicado deliberado
        else:
            eid = f"ev-{int(t0)}-{i}"
            ids.append(eid)
            if len(ids) > 5000:
                ids = ids[-5000:]

        evento = {"id": eid, "tipo": "vuelo_retrasado",
                  "poliza": f"pol-{i % 100000}",
                  "cobertura": 500000, "factor": 1.0,
                  "ts": time.time()}
        publisher.publish(topic_path, json.dumps(evento).encode("utf-8"))
        emitidos += 1

        objetivo = t0 + (i + 1) * intervalo
        ahora = time.time()
        if ahora < objetivo:
            time.sleep(objetivo - ahora)

        if emitidos % 50000 == 0:
            tasa = emitidos / (time.time() - t0)
            print(f"{emitidos:,} publicados | {tasa:,.0f} ev/s")

    total = time.time() - t0
    print(json.dumps({
        "eventos_publicados": emitidos,
        "duracion_s": round(total, 2),
        "tasa_ev_s": round(emitidos / total),
        "duplicados_inyectados_pct": a.duplicados * 100,
    }, indent=2))

if __name__ == "__main__":
    main()
