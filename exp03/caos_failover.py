"""
EXP-03 | Prueba de caos: derriba una zona y mide la interrupcion real.
Corre desde tu computador (necesita gcloud autenticado) y sondea el
balanceador publico. Con 3 replicas repartidas en 3 zonas, lo esperado es
que la caida de una zona NO interrumpa el servicio.

  python caos_failover.py --url http://IP_DEL_BALANCEADOR --zona southamerica-east1-a \
      --region southamerica-east1 --project MI_PROYECTO [--falla-bd] [--restaurar]

  --dry-run   imprime los comandos de gcloud sin ejecutarlos (para probar el script)
"""
import argparse, json, os, subprocess, threading, time
import requests

def sondear(url, parar, muestras):
    while not parar.is_set():
        t0 = time.perf_counter()
        try:
            ok = requests.post(f"{url}/cotizar", json={"cliente": "caos"}, timeout=3).status_code == 200
        except Exception:
            ok = False
        muestras.append((time.time(), ok, (time.perf_counter() - t0) * 1000))
        time.sleep(0.25)

def gcloud(args, dry):
    cmd = ["gcloud"] + args + ["--quiet"]
    print("$", " ".join(cmd))
    if not dry:
        subprocess.run(cmd, check=False)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", required=True)
    ap.add_argument("--zona", required=True)
    ap.add_argument("--region", required=True)
    ap.add_argument("--project", default=os.getenv("GCP_PROJECT_ID"))
    ap.add_argument("--cluster", default="solventa-primario")
    ap.add_argument("--falla-bd", action="store_true", help="fuerza tambien el failover de Cloud SQL")
    ap.add_argument("--restaurar", action="store_true", help="devuelve la zona al terminar")
    ap.add_argument("--nodos", type=int, default=1, help="nodos a restaurar en la zona")
    ap.add_argument("--estabilizar", type=int, default=30)
    ap.add_argument("--observacion", type=int, default=600)
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()

    parar, muestras = threading.Event(), []
    threading.Thread(target=sondear, args=(a.url, parar, muestras), daemon=True).start()
    print(f"estabilizando {a.estabilizar} s...")
    time.sleep(a.estabilizar)

    t_falla = time.time()
    print(f"\n>>> FALLA INYECTADA. ts_falla = {t_falla}  (usalo en verificar_rpo.py)\n")
    gcloud(["container", "clusters", "resize", a.cluster, "--node-pool", f"pool-{a.zona}",
            "--num-nodes", "0", "--region", a.region, "--project", a.project], a.dry_run)
    if a.falla_bd:
        gcloud(["sql", "instances", "failover", "solventa-db-principal", "--project", a.project], a.dry_run)

    time.sleep(a.observacion)
    parar.set(); time.sleep(0.5)

    if a.restaurar:
        gcloud(["container", "clusters", "resize", a.cluster, "--node-pool", f"pool-{a.zona}",
                "--num-nodes", str(a.nodos), "--region", a.region, "--project", a.project], a.dry_run)

    antes = [m for m in muestras if m[0] < t_falla]
    despues = [m for m in muestras if m[0] >= t_falla]
    fallos = [m for m in despues if not m[1]]
    if fallos:
        inicio = fallos[0][0]
        fin = fallos[-1][0]
        recuperado = next((m[0] for m in despues if m[0] > fin and m[1]), None)
        interrupcion = round((recuperado or despues[-1][0]) - inicio, 2)
    else:
        interrupcion = 0.0
    lat = sorted(m[2] for m in despues if m[1])
    res = {
        "experimento": "EXP-03", "medicion": "caida de zona",
        "zona_derribada": a.zona, "falla_bd": a.falla_bd, "ts_falla": t_falla,
        "muestras_antes": len(antes), "muestras_despues": len(despues),
        "solicitudes_fallidas": len(fallos),
        "interrupcion_s": interrupcion,
        "disponibilidad_durante_falla_pct": round(100 * (1 - len(fallos) / len(despues)), 3) if despues else None,
        "p95_ms_durante_falla": round(lat[int(len(lat) * .95)], 1) if lat else None,
        "meta_rto_s": 600,
        "dry_run": a.dry_run,
    }
    res["valido"] = len(antes) > 0 and len(despues) > 0 and not a.dry_run
    res["cumple_rto"] = res["valido"] and interrupcion <= 600
    print(json.dumps(res, indent=2, ensure_ascii=False))
    os.makedirs("resultados", exist_ok=True)
    json.dump(res, open(f"resultados/exp03-caos-{int(t_falla)}.json", "w"), indent=2)
    print(f"\nSiguiente paso (RPO), dentro del cluster:\n"
          f"  kubectl exec -n solventa deploy/herramientas -- "
          f"python /app/exp03/verificar_rpo.py --ts-falla {t_falla}")

if __name__ == "__main__":
    main()
