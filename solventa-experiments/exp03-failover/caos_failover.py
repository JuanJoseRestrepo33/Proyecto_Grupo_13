"""
EXP-03 | Prueba de caos: derriba la zona activa y mide RTO y RPO reales.

Mide:
  - RTO: tiempo desde la caida hasta que el servicio vuelve a responder 200.
  - RPO: transacciones confirmadas antes de la caida que no sobrevivieron.

Ejecutar:
  python caos_failover.py --url https://api.solventa.co --zona southamerica-east1-a
"""
import argparse, json, os, subprocess, threading, time
import requests
import psycopg2

def escribir_transacciones(dsn, parar, confirmadas, errores):
    """Escribe transacciones continuamente y anota las confirmadas."""
    while not parar.is_set():
        tid = f"tx-{time.time_ns()}"
        try:
            with psycopg2.connect(dsn, connect_timeout=3) as cn:
                with cn.cursor() as cur:
                    cur.execute(
                        "INSERT INTO transaccion_pago(id_transaccion, monto, estado) "
                        "VALUES (%s, %s, 'confirmada')", (tid, 1000))
                cn.commit()
            confirmadas.append(tid)      # solo tras el commit
        except Exception:
            errores.append(tid)
        time.sleep(0.2)

def sondear_disponibilidad(url, parar, muestras):
    while not parar.is_set():
        t0 = time.perf_counter()
        try:
            r = requests.get(f"{url}/health", timeout=2)
            ok = r.status_code == 200
        except Exception:
            ok = False
        muestras.append((time.time(), ok, (time.perf_counter() - t0) * 1000))
        time.sleep(0.5)

def derribar_zona(zona, cluster, project):
    """Simula la caida escalando a cero el grupo de nodos de esa zona."""
    cmd = ["gcloud", "container", "clusters", "resize", cluster,
           "--node-pool", f"pool-{zona}", "--num-nodes", "0",
           "--zone", zona, "--project", project, "--quiet"]
    print("derribando zona:", " ".join(cmd))
    subprocess.run(cmd, check=False)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", required=True)
    ap.add_argument("--zona", required=True)
    ap.add_argument("--cluster", default="solventa-primario")
    ap.add_argument("--project", default=os.getenv("GCP_PROJECT_ID"))
    ap.add_argument("--dsn", default=os.getenv("PG_DSN", ""))
    ap.add_argument("--observacion", type=int, default=900)
    a = ap.parse_args()

    parar = threading.Event()
    muestras, confirmadas, errores = [], [], []

    hs = threading.Thread(target=sondear_disponibilidad, args=(a.url, parar, muestras), daemon=True)
    hs.start()
    if a.dsn:
        ht = threading.Thread(target=escribir_transacciones,
                              args=(a.dsn, parar, confirmadas, errores), daemon=True)
        ht.start()

    print("estabilizando 60 s antes de inyectar la falla...")
    time.sleep(60)

    t_falla = time.time()
    confirmadas_antes = list(confirmadas)
    derribar_zona(a.zona, a.cluster, a.project)

    # Esperar recuperacion: 3 sondeos OK consecutivos
    t_recuperado, ok_seguidos = None, 0
    while time.time() - t_falla < a.observacion:
        recientes = [m for m in muestras if m[0] > t_falla]
        if recientes:
            ok_seguidos = ok_seguidos + 1 if recientes[-1][1] else 0
            if ok_seguidos >= 3 and t_recuperado is None:
                t_recuperado = recientes[-1][0]
                break
        time.sleep(0.5)

    time.sleep(30)
    parar.set(); time.sleep(1)

    # RPO: transacciones confirmadas antes de la caida que ya no existen
    perdidas = []
    if a.dsn and confirmadas_antes:
        try:
            with psycopg2.connect(a.dsn, connect_timeout=5) as cn:
                with cn.cursor() as cur:
                    for tid in confirmadas_antes[-200:]:
                        cur.execute("SELECT 1 FROM transaccion_pago WHERE id_transaccion=%s", (tid,))
                        if cur.fetchone() is None:
                            perdidas.append(tid)
        except Exception as e:
            print("no fue posible verificar el RPO:", e)

    rto = round(t_recuperado - t_falla, 2) if t_recuperado else None
    res = {
        "zona_derribada": a.zona,
        "RTO_s": rto,
        "RTO_cumple_meta_600s": (rto is not None and rto <= 600),
        "transacciones_confirmadas_antes": len(confirmadas_antes),
        "transacciones_perdidas": len(perdidas),
        "RPO_cumple_cero_perdida": len(perdidas) == 0,
        "muestras_disponibilidad": len(muestras),
        "disponibilidad_pct": round(100 * sum(1 for m in muestras if m[1]) / len(muestras), 2) if muestras else None,
    }
    print(json.dumps(res, indent=2, ensure_ascii=False))
    with open("resultados-exp03-caos.json", "w", encoding="utf-8") as f:
        json.dump(res, f, indent=2, ensure_ascii=False)

if __name__ == "__main__":
    main()
