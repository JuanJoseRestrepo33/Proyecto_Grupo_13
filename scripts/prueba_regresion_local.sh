#!/usr/bin/env bash
# ============================================================================
# Regresion local SIN Docker: ejecuta los experimentos que no requieren nube
# y resume si cada uno fue valido y si cumple su ASR.
# Requiere Redis y Postgres corriendo, y los servicios levantados con
# scripts/levantar_sin_docker.sh
# ============================================================================
set -uo pipefail
RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
export REDIS_URL="${REDIS_URL:-redis://localhost:6379}"
export PG_DSN="${PG_DSN:-postgresql://solventa_app:CAMBIAR_EN_PRODUCCION@localhost:5432/solventa}"
DUR="${DURACION:-30s}"

echo "######## EXP-01 ########"; DURACION="$DUR" "$RAIZ/exp01/run.sh" > /tmp/reg-exp01.log 2>&1
echo "######## EXP-04 ########"; DURACION="$DUR" "$RAIZ/exp04/run.sh" > /tmp/reg-exp04.log 2>&1
echo "######## EXP-02 (correccion multiproceso) ########"
( cd "$RAIZ/exp02" && python3 prueba_multiproceso.py --eventos 30000 --procesos 1 4 > /tmp/reg-exp02.log 2>&1 )

python3 - "$RAIZ" << 'PY'
import json, glob, sys, os
raiz = sys.argv[1]
def cargar(p):
    try: return json.load(open(p))
    except Exception: return None
print(f"\n{'prueba':42s} {'valido':>7s} {'cumple':>7s}  detalle")
print("-"*100)
for f in sorted(glob.glob(f"{raiz}/exp01/resultados/exp01-*.json")):
    d = cargar(f)
    print(f"EXP-01 {d['escenario']:14s} {d['rpm']:>5} rpm {'':12s}{str(d['valido']):>7s} {str(d['cumple_p95']):>7s}  p95={d['p95_ms']:.1f} ms  cache={d['pct_cache']:.0f}%")
for modo in ("off","sync","async"):
    d = cargar(f"{raiz}/exp04/resultados/exp04-parteA-{modo}.json")
    if d: print(f"EXP-04 parte A  auditoria {modo:6s} {'':12s}{str(d['valido']):>7s} {'-':>7s}  p95={d['p95_ms']:.1f} ms")
d = cargar(f"{raiz}/exp04/resultados/exp04-inmutabilidad.json")
if d: print(f"EXP-04 parte B  inmutabilidad {'':15s}{str(d['valido']):>7s} {str(d['cumple_rc08']):>7s}  reconstruccion={d['pct_reconstruccion']}%  cadena_integra={d['cadena_integra']} ({d['filas_en_cadena']} filas)")
d = cargar(f"{raiz}/exp04/resultados/exp04-revocacion.json")
if d: print(f"EXP-04 parte C  revocacion {'':18s}{str(d['valido']):>7s} {str(d['cumple_rc08']):>7s}  p95={d['propagacion_p95_ms']} ms  propagadas={d['propagadas']}/{d['revocaciones']}")
for d in (cargar(f"{raiz}/exp02/resultados/exp02-multiproceso-local.json") or []):
    ok = d["doble_pago"] == 0 and d["perdida"] == 0
    print(f"EXP-02 correccion {d['procesos']} proceso(s) {'':15s}{'True':>7s} {str(ok):>7s}  doble_pago={d['doble_pago']} perdida={d['perdida']}")
PY
