"""
EXP-04 Parte B | Verifica POR PRUEBA que la auditoria es inmutable y reconstruible.
  1. Reconstruccion completa de una muestra aleatoria de decisiones.
  2. Intentos deliberados de UPDATE, DELETE y TRUNCATE con el rol de la aplicacion.
  3. Recalculo de la cadena de hashes completa: detecta alteraciones incluso
     si un administrador desactivara los disparadores.

  python verificar_inmutabilidad.py
"""
import os, json, hashlib
import psycopg2

DSN_APP = os.getenv("PG_DSN", "postgresql://solventa_app:CAMBIAR_EN_PRODUCCION@localhost:5432/solventa")
res = {"experimento": "EXP-04", "parte": "B"}

cn = psycopg2.connect(DSN_APP)
cn.autocommit = True
cur = cn.cursor()

# 1) Reconstruccion
cur.execute("""SELECT id_decision, cliente, version_regla, variables_entrada, consentimiento_id
               FROM auditoria_decisiones ORDER BY random() LIMIT 50""")
muestra = cur.fetchall()
completas = sum(1 for f in muestra if all(f[1:]))
res.update(muestra=len(muestra), reconstruidas=completas,
           pct_reconstruccion=round(100 * completas / len(muestra), 1) if muestra else 0.0)

# 2) Intentos de modificacion
for nombre, sql in [("update", "UPDATE auditoria_decisiones SET prima = 0"),
                    ("delete", "DELETE FROM auditoria_decisiones"),
                    ("truncate", "TRUNCATE auditoria_decisiones")]:
    try:
        cur.execute(sql)
        res[f"{nombre}_bloqueado"] = False
    except Exception as e:
        res[f"{nombre}_bloqueado"] = True
        res[f"{nombre}_motivo"] = str(e).strip().splitlines()[0][:120]

# 3) Cadena de hashes
cur.execute("""SELECT hash_anterior, seq::text AS seq_txt, id_decision, cliente, version_regla,
                      variables_entrada::text, consentimiento_id, prima::text,
                      origen_perfil, hash_propio
               FROM auditoria_decisiones ORDER BY seq""")
previo, rotos, total = "GENESIS", 0, 0
for fila in cur.fetchall():
    total += 1
    ha, *campos, hp = fila
    esperado = hashlib.sha256("|".join([ha, *campos]).encode()).hexdigest()
    if ha != previo or hp != esperado:
        rotos += 1
    previo = hp
res.update(filas_en_cadena=total, eslabones_rotos=rotos, cadena_integra=(rotos == 0 and total > 0))

res["valido"] = total > 0 and len(muestra) > 0
res["cumple_rc08"] = (res["valido"] and res["pct_reconstruccion"] == 100.0
                      and res["update_bloqueado"] and res["delete_bloqueado"]
                      and res["truncate_bloqueado"] and res["cadena_integra"])
print(json.dumps(res, indent=2, ensure_ascii=False))
os.makedirs("resultados", exist_ok=True)
json.dump(res, open("resultados/exp04-inmutabilidad.json", "w"), indent=2, ensure_ascii=False)
