"""
EXP-04 | Demuestra que la cadena de hashes DETECTA una manipulacion hecha por
un administrador que desactiva los disparadores (el peor caso de amenaza interna).

Todo ocurre dentro de una transaccion que se REVIERTE al final: la base
queda intacta. Requiere un usuario administrador (propietario de la tabla).

  PG_ADMIN_DSN=postgresql://postgres@localhost:5432/solventa python demostrar_deteccion.py
"""
import os, json, hashlib
import psycopg2

DSN = os.getenv("PG_ADMIN_DSN", "postgresql://postgres@localhost:5432/solventa")

def verificar_cadena(cur):
    cur.execute("""SELECT id, hash_anterior, seq::text AS seq_txt, id_decision, cliente, version_regla,
                          variables_entrada::text, consentimiento_id, prima::text,
                          origen_perfil, hash_propio
                   FROM auditoria_decisiones ORDER BY seq""")
    previo, rotos = "GENESIS", []
    for fila in cur.fetchall():
        fid, ha, *campos, hp = fila
        if ha != previo or hp != hashlib.sha256("|".join([ha, *campos]).encode()).hexdigest():
            rotos.append(fid)
        previo = hp
    return rotos

cn = psycopg2.connect(DSN)
cur = cn.cursor()
res = {}
try:
    res["eslabones_rotos_antes"] = len(verificar_cadena(cur))
    cur.execute("SELECT id, prima FROM auditoria_decisiones ORDER BY random() LIMIT 1")
    fid, prima = cur.fetchone()

    # Ataque: el administrador desactiva la proteccion y altera una prima
    cur.execute("ALTER TABLE auditoria_decisiones DISABLE TRIGGER no_update")
    cur.execute("UPDATE auditoria_decisiones SET prima = prima * 0.5 WHERE id = %s", (fid,))
    res["fila_alterada"] = fid
    res["prima_original"] = float(prima)

    rotos = verificar_cadena(cur)
    res["eslabones_rotos_despues"] = len(rotos)
    res["fila_detectada"] = fid in rotos
    res["manipulacion_detectada"] = len(rotos) > 0 and fid in rotos
finally:
    cn.rollback()      # la base queda exactamente como estaba
    cn.close()

res["base_restaurada"] = True
print(json.dumps(res, indent=2, ensure_ascii=False))
try:
    os.makedirs("resultados", exist_ok=True)
    json.dump(res, open("resultados/exp04-deteccion-manipulacion.json", "w"), indent=2, ensure_ascii=False)
except OSError as e:
    print("aviso: no se pudo guardar el archivo de resultados:", e)
