"""Pool de conexiones compartido (ARQ-05, #216), contra un PostgreSQL real.

Antes cada operación abría una conexión nueva; en la `db-f1-micro` de *staging* eso trababa
el núcleo tras unas mil cotizaciones. Estas pruebas fijan lo que el pool promete: reutiliza
la conexión, confirma al salir y revierte si hubo una excepción.
"""

from __future__ import annotations

import os

import pytest

from app.compartido import conexiones

DSN = os.environ.get("AUDITORIA_DATABASE_URL")

pytestmark = pytest.mark.skipif(not DSN, reason="Requiere AUDITORIA_DATABASE_URL")


@pytest.fixture(autouse=True)
def pools_limpios():
    conexiones.cerrar_todos()
    yield
    conexiones.cerrar_todos()


def _proceso(conexion) -> int:
    return conexion.execute("SELECT pg_backend_pid() AS pid").fetchone()["pid"]


def test_dos_operaciones_seguidas_usan_la_misma_conexion():
    with conexiones.conexion(DSN) as primera:
        pid = _proceso(primera)
    with conexiones.conexion(DSN) as segunda:
        assert _proceso(segunda) == pid


def test_confirma_al_salir_y_revierte_si_hay_una_excepcion():
    with conexiones.conexion(DSN) as c:
        c.execute("CREATE TABLE IF NOT EXISTS public.prueba_pool (valor INT)")
        c.execute("TRUNCATE public.prueba_pool")
    with conexiones.conexion(DSN) as c:
        c.execute("INSERT INTO public.prueba_pool VALUES (1)")
    with pytest.raises(RuntimeError), conexiones.conexion(DSN) as c:
        c.execute("INSERT INTO public.prueba_pool VALUES (2)")
        raise RuntimeError("falla a mitad de la operación")
    with conexiones.conexion(DSN) as c:
        valores = [f["valor"] for f in c.execute("SELECT valor FROM public.prueba_pool").fetchall()]
        c.execute("DROP TABLE public.prueba_pool")
    assert valores == [1]


def test_el_tamano_maximo_se_ajusta_por_variable(monkeypatch):
    monkeypatch.setenv("POSTGRES_POOL_MAXIMO", "5")
    assert conexiones._maximo() == 5
    monkeypatch.setenv("POSTGRES_POOL_MAXIMO", "no-es-numero")
    assert conexiones._maximo() == conexiones.MAXIMO_POR_DEFECTO
