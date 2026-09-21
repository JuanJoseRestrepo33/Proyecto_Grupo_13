"""
EXP-04 Parte C | Mide cuanto tarda la revocacion en alcanzar a TODOS los
consumidores, incluido uno con copia propia del dato (perfilamiento).

Criterio correcto (corrige la version anterior): tras revocar, cada consumidor
debe responder origen_perfil = 'sin_consentimiento'. No basta con que deje de
usar la cache: si vuelve a consultar al proveedor, sigue usando el dato.

  python medir_revocacion.py --revocaciones 20
"""
import argparse, json, os, statistics, time
import requests

def origen_cotizacion(url, cliente):
    return requests.post(f"{url}/cotizar", json={"cliente": cliente}, timeout=10).json()["origen_perfil"]

def origen_perfilamiento(url, cliente):
    return requests.get(f"{url}/perfil/{cliente}", timeout=10).json()["origen_perfil"]

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--consentimiento", default=os.getenv("CONSENTIMIENTO_URL", "http://localhost:8082"))
    ap.add_argument("--cotizacion", default=os.getenv("COTIZACION_URL", "http://localhost:8080"))
    ap.add_argument("--perfilamiento", default=os.getenv("PERFILAMIENTO_URL", "http://localhost:8083"))
    ap.add_argument("--simulador", default=os.getenv("SIMULADOR_URL", "http://localhost:8090"))
    ap.add_argument("--revocaciones", type=int, default=20)
    ap.add_argument("--limite-s", type=float, default=300.0)
    a = ap.parse_args()

    # El proveedor debe estar sano: si no, los consumidores no cachean el dato
    # y la medicion de la revocacion no prueba nada.
    try:
        requests.post(f"{a.simulador}/admin/escenario", json={"nombre": "a_sano"}, timeout=5)
        requests.post(f"{a.cotizacion}/admin/reset", timeout=5)
    except Exception:
        print("aviso: no fue posible fijar el simulador en a_sano")

    tiempos, fallas, precondicion_fallida = [], [], 0
    pref = f"rev-{int(time.time())}"
    for i in range(a.revocaciones):
        cliente = f"{pref}-{i}"
        # Precondicion: ambos consumidores deben tener el dato ANTES de revocar
        origen_cotizacion(a.cotizacion, cliente)
        origen_perfilamiento(a.perfilamiento, cliente)
        if origen_perfilamiento(a.perfilamiento, cliente) != "cache_local":
            precondicion_fallida += 1

        t0 = time.perf_counter()
        requests.post(f"{a.consentimiento}/consentimiento/revocar",
                      json={"cliente": cliente, "consentimiento_id": f"cons-{cliente}"}, timeout=10)
        ok = False
        while time.perf_counter() - t0 < a.limite_s:
            if (origen_cotizacion(a.cotizacion, cliente) == "sin_consentimiento" and
                    origen_perfilamiento(a.perfilamiento, cliente) == "sin_consentimiento"):
                ok = True
                break
            time.sleep(0.01)
        dt = (time.perf_counter() - t0) * 1000
        (tiempos if ok else fallas).append(dt if ok else cliente)

    tiempos.sort()
    res = {
        "experimento": "EXP-04", "parte": "C",
        "revocaciones": a.revocaciones,
        "precondicion_fallida": precondicion_fallida,
        "propagadas": len(tiempos),
        "no_propagadas_en_limite": len(fallas),
        "propagacion_p50_ms": round(statistics.median(tiempos), 2) if tiempos else None,
        "propagacion_p95_ms": round(tiempos[min(int(len(tiempos) * .95), len(tiempos) - 1)], 2) if tiempos else None,
        "propagacion_max_ms": round(tiempos[-1], 2) if tiempos else None,
        "meta_ms": 300_000,
    }
    res["valido"] = precondicion_fallida == 0 and len(tiempos) > 0
    res["cumple_rc08"] = res["valido"] and not fallas and res["propagacion_max_ms"] < res["meta_ms"]
    print(json.dumps(res, indent=2, ensure_ascii=False))
    os.makedirs("resultados", exist_ok=True)
    json.dump(res, open("resultados/exp04-revocacion.json", "w"), indent=2, ensure_ascii=False)

if __name__ == "__main__":
    main()
