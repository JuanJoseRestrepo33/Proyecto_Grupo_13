"""
EXP-02 | Consumidor de eventos parametricos.

Corrige el sesgo de la medicion local de la semana 6: cada consumidor es un
PROCESO independiente (un contenedor), no un hilo, de modo que escalar
replicas si agrega paralelismo real.

Idempotencia: SET NX en Redis es atomico entre procesos y replicas.
Todas las metricas se acumulan en Redis para que se puedan leer en conjunto,
sin importar cuantas replicas esten corriendo.
"""
import os
import json
import time
import signal
import hashlib
import logging

import redis

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("consumidor")

PROJECT = os.getenv("GCP_PROJECT_ID", "solventa-local")
SUBSCRIPTION = os.getenv("SUBSCRIPTION", "siniestros-parametricos-sub")
REDIS_URL = os.getenv("REDIS_URL", "redis://localhost:6379")
MAX_MESSAGES = int(os.getenv("MAX_MESSAGES", "200"))
IDEM_TTL_S = int(os.getenv("IDEM_TTL_S", "86400"))

r = redis.from_url(REDIS_URL, decode_responses=True)


def es_nuevo(id_evento: str) -> bool:
    """Atomico entre procesos: devuelve None si la clave ya existia."""
    return r.set(f"idem:{id_evento}", "1", nx=True, ex=IDEM_TTL_S) is True


def liquidar(evento: dict) -> str:
    """Liquidacion y dispersion del pago. Sustituir por la pasarela real."""
    monto = evento["cobertura"] * evento.get("factor", 1.0)
    return hashlib.sha256(f"{evento['id']}{monto}".encode()).hexdigest()[:16]


def procesar(evento: dict) -> str:
    """Logica pura, testeable sin Pub/Sub. Devuelve 'procesado' o 'duplicado'."""
    if not es_nuevo(evento["id"]):
        r.incr("exp02:duplicados")
        return "duplicado"
    id_pago = liquidar(evento)
    pipe = r.pipeline()
    pipe.incr("exp02:procesados")
    pipe.sadd("exp02:pagos", evento["id"])     # verifica que no hay doble pago
    pipe.set("exp02:ultimo_ts", time.time())
    pipe.execute()
    return "procesado"


def main():
    from google.cloud import pubsub_v1
    sub = pubsub_v1.SubscriberClient()
    path = sub.subscription_path(PROJECT, SUBSCRIPTION)

    def callback(msg):
        try:
            procesar(json.loads(msg.data.decode("utf-8")))
            msg.ack()
        except Exception:
            r.incr("exp02:errores")
            log.exception("error procesando evento")
            msg.nack()

    flow = pubsub_v1.types.FlowControl(max_messages=MAX_MESSAGES)
    future = sub.subscribe(path, callback=callback, flow_control=flow)
    log.info("consumiendo de %s", path)
    signal.signal(signal.SIGTERM, lambda *_: future.cancel())
    try:
        future.result()
    except Exception:
        log.info("consumidor detenido")


if __name__ == "__main__":
    main()
