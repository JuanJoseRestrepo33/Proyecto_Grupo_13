"""
Bus de eventos con dos backends intercambiables por variable de entorno:
  BUS_BACKEND=pubsub  -> Google Cloud Pub/Sub (real o emulador via PUBSUB_EMULATOR_HOST)
  BUS_BACKEND=redis   -> canales Pub/Sub de Redis (desarrollo liviano sin emulador)
La medicion definitiva de los experimentos debe hacerse con BUS_BACKEND=pubsub.
"""
import os
import json
import threading
import logging

log = logging.getLogger("bus")
BACKEND = os.getenv("BUS_BACKEND", "pubsub")
PROJECT = os.getenv("GCP_PROJECT_ID", "solventa-local")
REDIS_URL = os.getenv("REDIS_URL", "redis://localhost:6379")

_publisher = None
_lock = threading.Lock()


def _pub():
    global _publisher
    with _lock:
        if _publisher is None:
            from google.cloud import pubsub_v1
            _publisher = pubsub_v1.PublisherClient()
        return _publisher


def publicar(topic: str, payload: dict) -> None:
    data = json.dumps(payload)
    if BACKEND == "redis":
        import redis
        redis.from_url(REDIS_URL).publish(topic, data)
        return
    p = _pub()
    p.publish(p.topic_path(PROJECT, topic), data.encode("utf-8")).result(timeout=10)


def suscribir(topic: str, subscription: str, handler) -> None:
    """Arranca un hilo en segundo plano que invoca handler(dict) por cada evento."""
    if BACKEND == "redis":
        import redis

        def _loop():
            ps = redis.from_url(REDIS_URL).pubsub(ignore_subscribe_messages=True)
            ps.subscribe(topic)
            for m in ps.listen():
                try:
                    handler(json.loads(m["data"]))
                except Exception:
                    log.exception("error manejando evento de %s", topic)

        threading.Thread(target=_loop, daemon=True).start()
        return

    from google.cloud import pubsub_v1
    sub = pubsub_v1.SubscriberClient()
    path = sub.subscription_path(PROJECT, subscription)

    def _cb(msg):
        try:
            handler(json.loads(msg.data.decode("utf-8")))
            msg.ack()
        except Exception:
            log.exception("error manejando evento de %s", subscription)
            msg.nack()

    sub.subscribe(path, callback=_cb)
