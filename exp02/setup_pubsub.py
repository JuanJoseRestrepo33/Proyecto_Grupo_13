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

# Autodiagnostico: muestra la configuracion que realmente ve el contenedor
print("PUBSUB_EMULATOR_HOST =", os.getenv("PUBSUB_EMULATOR_HOST") or "(NO DEFINIDA: se usaria GCP real)", flush=True)
proxies = {k: v for k, v in os.environ.items() if "proxy" in k.lower()}
if proxies:
    print("variables de proxy presentes:", proxies, flush=True)

# Espera a que el emulador acepte conexiones (arranca despues del contenedor)
ultimo_error = ""
for intento in range(30):
    try:
        list(pub.list_topics(request={"project": f"projects/{PROJECT}"}, timeout=5, retry=None))
        break
    except Exception as e:
        # El detalle del error distingue proxy, DNS o conexion rechazada
        ultimo_error = f"{type(e).__name__}: {str(e).strip().splitlines()[0][:200] if str(e).strip() else ''}"
        print(f"esperando Pub/Sub ({intento + 1}/30): {ultimo_error}", flush=True)
        time.sleep(2)
else:
    raise SystemExit("ERROR: Pub/Sub no responde.\n  Ultimo error: " + ultimo_error +
                     "\n  Si el detalle menciona 'proxy' o 'DNS', revisa la seccion 8 del README.")
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
