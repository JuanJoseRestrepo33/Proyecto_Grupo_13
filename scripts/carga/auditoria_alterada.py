"""Altera un registro del log de auditoría en staging y comprueba que la cadena lo detecta (ARQ-05,
criterio 6; RC-08). Después lo deja como estaba.

    python3 scripts/carga/auditoria_alterada.py --nucleo https://… --proyecto … --region …

1. Crea una cotización propia (cliente `CLI-RC08-<marca>`) y busca su decisión en el log.
2. Comprueba que un `UPDATE` normal falla: el trigger de la tabla lo rechaza.
3. Como dueño de la tabla, apaga el trigger solo dentro de una transacción, cambia la prima del
   resultado de esa decisión y vuelve a encender el trigger.
4. `GET /v1/auditoria/integridad` debe decir `conforme: false` y señalar esa secuencia.
5. Restaura el resultado original (en `finally`, pase lo que pase) y comprueba que la cadena
   vuelve a ser conforme.

La cadena de conexión del dueño sale de Secret Manager (`auditoria-database-url`) y nunca se
imprime.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "solventa"
SECRETO = "auditoria-database-url"
TABLA = "auditoria.registros"
TRIGGER = "registros_inmutables"


def pedir(nucleo: str, metodo: str, ruta: str, cuerpo: dict | None = None) -> dict:
    datos = json.dumps(cuerpo).encode() if cuerpo is not None else None
    solicitud = urllib.request.Request(
        nucleo.rstrip("/") + "/v1" + ruta,
        data=datos,
        method=metodo,
        headers={"Content-Type": "application/json", "X-Canal": "web"},
    )
    with urllib.request.urlopen(solicitud, timeout=60) as respuesta:
        return json.loads(respuesta.read() or b"null")


def integridad(nucleo: str) -> dict:
    return pedir(nucleo, "GET", "/auditoria/integridad")


def credenciales(proyecto: str, sufijo: str = "") -> tuple[str, str, str]:
    """(conexión de la instancia, usuario, clave) del dueño, sin imprimir nada."""
    cadena = subprocess.run(
        ["gcloud", "secrets", "versions", "access", "latest", f"--secret={SECRETO}{sufijo}",
         f"--project={proyecto}"],
        capture_output=True, text=True, check=True,
    ).stdout.strip()  # fmt: skip
    partes = urllib.parse.urlsplit(cadena)
    host = urllib.parse.parse_qs(partes.query)["host"][0]
    return (
        host.removeprefix("/cloudsql/"),
        partes.username or "",
        urllib.parse.unquote(partes.password or ""),
    )


def conectar(proyecto: str, sufijo: str = ""):  # pragma: no cover - red real
    from google.cloud.sql.connector import Connector

    instancia, usuario, clave = credenciales(proyecto, sufijo)
    return Connector().connect(instancia, "pg8000", user=usuario, password=clave, db=BASE)


def escribir_resultado(con, id_decision: str, resultado: dict) -> None:
    """Cambia `resultado` con el trigger apagado solo durante esta transacción."""
    cursor = con.cursor()
    cursor.execute(f"ALTER TABLE {TABLA} DISABLE TRIGGER {TRIGGER}")
    cursor.execute(
        f"UPDATE {TABLA} SET resultado = %s::jsonb WHERE id_decision = %s",
        (json.dumps(resultado), id_decision),
    )
    cursor.execute(f"ALTER TABLE {TABLA} ENABLE TRIGGER {TRIGGER}")
    con.commit()


def main() -> int:
    argumentos = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    argumentos.add_argument("--nucleo", required=True)
    argumentos.add_argument("--proyecto", required=True)
    # "" en staging y "-prd" en producción: el secreto del dueño de cada base.
    argumentos.add_argument("--sufijo", default="")
    opciones = argumentos.parse_args()
    nucleo = opciones.nucleo

    antes = integridad(nucleo)
    print(f"Antes: {antes['total']} registros, conforme={antes['conforme']}")
    if not antes["conforme"]:
        print("::error::La cadena ya estaba rota: no se altera nada")
        return 1

    cliente = f"CLI-RC08-{int(time.time())}"
    cotizacion = pedir(nucleo, "POST", "/cotizacion/cotizaciones", {
        "producto": "auto", "id_cliente": cliente, "usar_open_finance": True,
        "asegurado": {"edad": 44, "ciudad": "Bogotá", "valor_asegurado": 70_000_000},
    })  # fmt: skip
    id_decision = ""
    for _ in range(20):  # el log se escribe en segundo plano
        try:
            id_decision = pedir(
                nucleo, "GET", f"/cotizacion/cotizaciones/{cotizacion['id_cotizacion']}/recalculo"
            )["id_decision"]
            break
        except urllib.error.HTTPError:
            time.sleep(0.5)
    if not id_decision:
        print("::error::La decisión de la cotización no llegó al log")
        return 1
    registro = pedir(nucleo, "GET", f"/auditoria/registros/{id_decision}")
    secuencia = registro["secuencia"]
    original = registro["resultado"]
    print(f"Cotización {cotizacion['id_cotizacion']} → decisión en la secuencia {secuencia}")

    con = conectar(opciones.proyecto, opciones.sufijo)
    fallos: list[str] = []
    try:
        # 2. Sin apagar el trigger, la base rechaza el cambio.
        try:
            con.cursor().execute(
                f"UPDATE {TABLA} SET resultado = resultado WHERE id_decision = %s", (id_decision,)
            )
            con.commit()
            fallos.append("un UPDATE con el trigger encendido no falló")
        except Exception as error:  # noqa: BLE001 - se espera el error del trigger
            con.rollback()
            print(f"UPDATE normal rechazado por la base: {str(error).splitlines()[0][:160]}")

        # 3 y 4. Alterado como lo haría alguien con un rol que salta las dos primeras capas.
        alterado = {**original, "prima_mensual": int(original["prima_mensual"]) - 1000}
        escribir_resultado(con, id_decision, alterado)
        print(f"Secuencia {secuencia}: prima_mensual {original['prima_mensual']} → "
              f"{alterado['prima_mensual']}")  # fmt: skip
        informe = integridad(nucleo)
        print(f"Con el registro alterado: {json.dumps(informe, ensure_ascii=False)}")
        if informe["conforme"]:
            fallos.append("la verificación no detectó el registro alterado")
        if secuencia not in [e["secuencia"] for e in informe["eslabones_rotos"]]:
            fallos.append(f"la verificación no señaló la secuencia {secuencia}")
    finally:
        escribir_resultado(con, id_decision, original)
        con.close()
        print(f"Secuencia {secuencia}: resultado original restaurado")

    despues = integridad(nucleo)
    print(f"Después: {despues['total']} registros, conforme={despues['conforme']}")
    if not despues["conforme"]:
        fallos.append("la cadena no volvió a ser conforme tras restaurar")

    for fallo in fallos:
        print(f"::error::{fallo}")
    print(f"RC-08 verificado en {nucleo}" if not fallos else f"RC-08 NO verificado en {nucleo}")
    return 1 if fallos else 0


if __name__ == "__main__":
    sys.exit(main())
