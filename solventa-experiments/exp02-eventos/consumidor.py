"""
EXP-02 | Consumidor de eventos parametricos con verificacion de idempotencia.

Valida el punto de sensibilidad: particionamiento de la suscripcion y costo
de la verificacion de idempotencia bajo rafaga.

Requisitos de la semana 6 que este codigo corrige respecto al prototipo local:
  - Los consumidores corren en procesos/contenedores separados (no hilos),
    para eliminar el sesgo del bloqueo global del interprete de Python.
  - La idempotencia usa Redis con SET NX, que es atomico entre procesos.

Ejecutar:  python consumidor.py
Escalar :  aumentar replicas del Deployment (ver keda-scaledobject.yaml)
"""
import os
import json
import time
import logging
import signal

import redis
from google.cloud import pubsub_v1
from google.cloud import monitoring_v3

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("consumidor")

PROJECT_ID = os.environ["GCP_PROJECT_ID"]
SUBSCRIPTION = os.getenv("SUBSCRIPTION", "siniestros-parametricos-sub")
DLQ_TOPIC = os.getenv("DLQ_TOPIC", "siniestros-dlq")
REDIS_URL = os.getenv("REDIS_URL", "redis://localhost:6379")
IDEM_TTL_S = int(os.getenv("IDEM_TTL_S", "86400"))
MAX_MESSAGES = int(os.getenv("MAX_MESSAGES", "200"))

r = redis.from_url(REDIS_URL, decode_responses=True)
subscriber = pubsub_v1.SubscriberClient()
publisher = pubsub_v1.PublisherClient()
sub_path = subscriber.subscription_path(PROJECT_ID, SUBSCRIPTION)
dlq_path = publisher.topic_path(PROJECT_ID, DLQ_TOPIC)

METRICAS = {"procesados": 0, "duplicados": 0, "errores": 0}


def es_nuevo(id_evento: str) -> bool:
    """Idempotencia atomica entre procesos: SET NX devuelve None si ya existia."""
    return r.set(f"idem:{id_evento}", "1", nx=True, ex=IDEM_TTL_S) is True


def liquidar(evento: dict) -> dict:
    """Liquidacion y dispersion del pago parametrico.
    Sustituir por la integracion real con la pasarela de pagos."""
    monto = evento["cobertura"] * evento.get("factor", 1.0)
    return {"id_pago": f"pago-{evento['id']}", "monto": round(monto, 2)}


def callback(message: pubsub_v1.subscriber.message.Message):
    t0 = time.perf_counter()
    try:
        evento = json.loads(message.data.decode("utf-8"))
        id_evento = evento["id"]

        if not es_nuevo(id_evento):
            METRICAS["duplicados"] += 1
            message.ack()            # duplicado: se confirma sin volver a pagar
            return

        pago = liquidar(evento)
        METRICAS["procesados"] += 1
        log.debug("liquidado %s en %.1f ms", pago["id_pago"],
                  (time.perf_counter() - t0) * 1000)
        message.ack()

    except Exception as e:
        METRICAS["errores"] += 1
        log.exception("error procesando el evento; se envia a la DLQ")
        try:
            publisher.publish(dlq_path, message.data,
                              error=str(e)[:500]).result(timeout=10)
            message.ack()
        except Exception:
            message.nack()           # que Pub/Sub lo reintregue


def main():
    flow = pubsub_v1.types.FlowControl(max_messages=MAX_MESSAGES)
    future = subscriber.subscribe(sub_path, callback=callback, flow_control=flow)
    log.info("consumiendo de %s (max_messages=%d)", sub_path, MAX_MESSAGES)

    def apagar(*_):
        log.info("apagando; metricas finales: %s", METRICAS)
        future.cancel()

    signal.signal(signal.SIGTERM, apagar)
    signal.signal(signal.SIGINT, apagar)

    try:
        future.result()
    except Exception:
        log.info("consumidor detenido; metricas: %s", METRICAS)


if __name__ == "__main__":
    main()
