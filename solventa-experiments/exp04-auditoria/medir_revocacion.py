"""
EXP-04 | Mide el tiempo real de propagacion de la revocacion hasta que
TODOS los consumidores dejan de servir el dato (meta RC-08: <= 5 minutos).

Ejecutar: python medir_revocacion.py --revocaciones 20
"""
import argparse, json, time, statistics
import requests

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--consentimiento-url", default="http://localhost:8082")
    ap.add_argument("--consumidores", nargs="+",
                    default=["http://localhost:8080"],
                    help="servicios que cachean el perfil (cotizacion, perfilamiento, ...)")
    ap.add_argument("--revocaciones", type=int, default=20)
    a = ap.parse_args()

    tiempos, fallas = [], []

    for i in range(a.revocaciones):
        cliente = f"c-revoca-{i}"

        # 1) Poblar la cache de cada consumidor
        for url in a.consumidores:
            requests.post(f"{url}/cotizar",
                          json={"cliente": cliente, "monto": 100000000}, timeout=10)

        # 2) Revocar y medir hasta que ninguno sirva el dato
        t0 = time.perf_counter()
        requests.post(f"{a.consentimiento_url}/consentimiento/revocar",
                      json={"cliente": cliente, "consentimiento_id": f"cons-{i}"}, timeout=10)

        propagado, limite = False, 300      # 5 minutos
        while time.perf_counter() - t0 < limite:
            usando_cache = False
            for url in a.consumidores:
                r = requests.post(f"{url}/cotizar",
                                  json={"cliente": cliente, "monto": 100000000}, timeout=10)
                if r.json().get("origen_perfil") == "cache":
                    usando_cache = True
            if not usando_cache:
                propagado = True
                break
            time.sleep(0.2)

        dt = time.perf_counter() - t0
        if propagado:
            tiempos.append(dt)
        else:
            fallas.append(cliente)

    tiempos.sort()
    res = {
        "revocaciones": a.revocaciones,
        "propagadas": len(tiempos),
        "no_propagadas_en_5min": len(fallas),
        "propagacion_p50_s": round(statistics.median(tiempos), 3) if tiempos else None,
        "propagacion_p95_s": round(tiempos[int(len(tiempos)*0.95)], 3) if tiempos else None,
        "propagacion_max_s": round(tiempos[-1], 3) if tiempos else None,
        "cumple_rc08": len(fallas) == 0,
    }
    print(json.dumps(res, indent=2, ensure_ascii=False))
    with open("resultados-exp04-revocacion.json","w",encoding="utf-8") as f:
        json.dump(res,f,indent=2,ensure_ascii=False)

if __name__ == "__main__":
    main()
