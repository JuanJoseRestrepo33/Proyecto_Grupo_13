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

pub, sub = pubsub_v1.PublisherClient(), pubsub_v1.SubscriberClient()
for topico, subs in TOPICOS.items():
    tp = pub.topic_path(PROJECT, topico)
    try:
        pub.create_topic(request={"name": tp}); print("topico creado:", topico)
    except AlreadyExists:
        print("topico ya existe:", topico)
    for s in subs:
        sp = sub.subscription_path(PROJECT, s)
        try:
            sub.create_subscription(request={"name": sp, "topic": tp, "ack_deadline_seconds": 60})
            print("  suscripcion creada:", s)
        except AlreadyExists:
            print("  suscripcion ya existe:", s)
print("emulador:" if os.getenv("PUBSUB_EMULATOR_HOST") else "GCP real:", PROJECT)
