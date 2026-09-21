"""
Crea topicos y suscripciones. Funciona contra el emulador (si esta definido
PUBSUB_EMULATOR_HOST) y contra GCP real; si ya existen, no hace nada.

  python setup_pubsub.py
"""
import os
from google.api_core.exceptions import AlreadyExists
from google.cloud import pubsub_v1

PROJECT = os.getenv("GCP_PROJECT_ID", "solventa-local")
TOPICOS = {
    "siniestros-parametricos": ["siniestros-parametricos-sub"],
    "siniestros-dlq": [],
    "consentimiento-revocado": ["consentimiento-revocado-perfilamiento"],
}

import time
from google.api_core.exceptions import GoogleAPICallError

pub, sub = pubsub_v1.PublisherClient(), pubsub_v1.SubscriberClient()
T = 30   # segundos maximos por operacion: nunca quedarse colgado en silencio

# Espera a que el emulador acepte conexiones (arranca despues del contenedor)
for intento in range(30):
    try:
        list(pub.list_topics(request={"project": f"projects/{PROJECT}"}, timeout=5, retry=None))
        break
    except Exception as e:
        print(f"esperando Pub/Sub ({intento + 1}/30): {type(e).__name__}", flush=True)
        time.sleep(2)
else:
    raise SystemExit("ERROR: Pub/Sub no responde. Revisa PUBSUB_EMULATOR_HOST y: docker compose logs pubsub")
for topico, subs in TOPICOS.items():
    tp = pub.topic_path(PROJECT, topico)
    try:
        pub.create_topic(request={"name": tp}, timeout=T); print("topico creado:", topico)
    except AlreadyExists:
        print("topico ya existe:", topico)
    for s in subs:
        sp = sub.subscription_path(PROJECT, s)
        try:
            sub.create_subscription(request={"name": sp, "topic": tp, "ack_deadline_seconds": 60}, timeout=T)
            print("  suscripcion creada:", s)
        except AlreadyExists:
            print("  suscripcion ya existe:", s)
print("emulador:" if os.getenv("PUBSUB_EMULATOR_HOST") else "GCP real:", PROJECT)
