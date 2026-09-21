"""
EXP-02 | Espera a que los consumidores terminen y calcula los resultados
a partir de los contadores en Redis (validos para cualquier numero de replicas).

  python medir_exp02.py --meta-s 600
"""
import argparse, json, os, time
import redis
r = redis.from_url(os.getenv("REDIS_URL", "redis://localhost:6379"), decode_responses=True)

def n(k): return int(r.get(k) or 0)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--meta-s", type=float, default=600)
    ap.add_argument("--espera-max-s", type=float, default=1800)
    a = ap.parse_args()

    t_fin_espera = time.time() + a.espera_max_s
    while time.time() < t_fin_espera:
        pub = n("exp02:publicados")
        if pub and n("exp02:procesados") + n("exp02:duplicados") + n("exp02:errores") >= pub:
            break
        time.sleep(2)

    t0 = float(r.get("exp02:t_inicio") or 0); t_ult = float(r.get("exp02:ultimo_ts") or 0)
    pub, proc, dup = n("exp02:publicados"), n("exp02:procesados"), n("exp02:duplicados")
    inyect, err, pagos = n("exp02:dup_inyectados"), n("exp02:errores"), r.scard("exp02:pagos")
    vaciado = round(t_ult - t0, 2) if t0 and t_ult else None
    res = {
        "experimento": "EXP-02", "publicados": pub, "procesados": proc,
        "duplicados_detectados": dup, "duplicados_inyectados": inyect, "errores": err,
        "pagos_unicos": pagos,
        # IDs unicos publicados que nunca se pagaron. No usar pub-(proc+dup):
        # con reentregas de Pub/Sub eso da negativo aunque no se pierda nada.
        "perdida": (pub - inyect) - pagos,
        "doble_pago": proc - pagos,
        "t_vaciado_s": vaciado,
        "caudal_ev_s": round(pub / vaciado) if vaciado else None,
        "meta_s": a.meta_s,
    }
    # Pub/Sub garantiza "al menos una vez": puede reentregar, asi que los duplicados
    # detectados pueden SUPERAR a los inyectados. Lo que no puede pasar es pagar dos veces.
    res["valido"] = pub > 0
    res["cumple_rc02"] = (res["valido"] and res["perdida"] == 0 and res["doble_pago"] == 0
                          and dup >= inyect and vaciado is not None and vaciado <= a.meta_s)
    print(json.dumps(res, indent=2, ensure_ascii=False))
    os.makedirs("resultados", exist_ok=True)
    json.dump(res, open(f"resultados/exp02-{int(time.time())}.json", "w"), indent=2)

if __name__ == "__main__":
    main()
