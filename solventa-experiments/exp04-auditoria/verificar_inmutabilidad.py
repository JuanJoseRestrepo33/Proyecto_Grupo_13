"""
EXP-04 | Verifica por prueba (no por convencion) que el registro de auditoria
es append-only, e intenta deliberadamente modificarlo y borrarlo.

Ejecutar: python verificar_inmutabilidad.py
"""
import os, json, random
from google.cloud import bigquery

PROJECT = os.environ["GCP_PROJECT_ID"]
DATASET = os.getenv("AUDIT_DATASET", "solventa_auditoria")
TABLA   = os.getenv("AUDIT_TABLE", "decisiones")
FQN     = f"`{PROJECT}.{DATASET}.{TABLA}`"

bq = bigquery.Client(project=PROJECT)
res = {}

# 1) Reconstruccion de una muestra aleatoria
muestra = list(bq.query(f"""
  SELECT id_decision, cliente, version_regla, variables_entrada, consentimiento_id
  FROM {FQN} ORDER BY RAND() LIMIT 50
""").result())
completas = sum(1 for f in muestra
                if all([f["cliente"], f["version_regla"], f["variables_entrada"], f["consentimiento_id"]]))
res["muestra"] = len(muestra)
res["reconstruidas_completas"] = completas
res["pct_reconstruccion"] = round(100*completas/len(muestra), 1) if muestra else 0

# 2) Intento de UPDATE: debe fallar
try:
    bq.query(f"UPDATE {FQN} SET cliente='ALTERADO' WHERE TRUE").result()
    res["update_bloqueado"] = False
except Exception as e:
    res["update_bloqueado"] = True
    res["update_error"] = str(e)[:200]

# 3) Intento de DELETE: debe fallar
try:
    bq.query(f"DELETE FROM {FQN} WHERE TRUE").result()
    res["delete_bloqueado"] = False
except Exception as e:
    res["delete_bloqueado"] = True
    res["delete_error"] = str(e)[:200]

res["cumple_rc08"] = (res["pct_reconstruccion"] == 100.0
                      and res["update_bloqueado"] and res["delete_bloqueado"])
print(json.dumps(res, indent=2, ensure_ascii=False))
with open("resultados-exp04-inmutabilidad.json","w",encoding="utf-8") as f:
    json.dump(res,f,indent=2,ensure_ascii=False)
