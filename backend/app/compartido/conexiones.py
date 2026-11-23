"""Pool de conexiones a PostgreSQL, uno por cadena de conexión (ARQ-05, #216).

Antes cada operación de un repositorio abría y cerraba su propia conexión. Una cotización
hace seis o más (consentimiento, cotización, auditoría) y en *staging* cada una pasa por el
socket de Cloud SQL con su autenticación. La `db-f1-micro` es de núcleo compartido: tras
unas mil cotizaciones seguidas dejaba de dar abasto y la latencia saltaba de 65 ms a más de
6 s, sin errores (informe de ARQ-05, sección 3). Con el pool las conexiones se reutilizan.

Cada módulo tiene su propio usuario y, por tanto, su propia cadena y su propio pool. El
tamaño es chico a propósito: la `db-g1-small` admite 50 conexiones y se reparten entre los
módulos y las réplicas del núcleo. Las conexiones ociosas se cierran solas (`max_idle`), así
que un módulo que no se usa no ocupa ninguna.

Cada préstamo verifica la conexión (`check`): si Cloud SQL se reinició o se apagó por la
noche, el pool descarta la conexión rota y abre otra en lugar de devolver un error.
"""

from __future__ import annotations

import os
import threading
from collections.abc import Iterator
from contextlib import contextmanager

import psycopg
from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

#: Conexiones máximas por módulo y por réplica. `POSTGRES_POOL_MAXIMO` lo ajusta sin tocar código.
MAXIMO_POR_DEFECTO = 3
#: Segundos que una conexión puede quedar ociosa antes de cerrarse.
OCIOSA_MAXIMO_S = 60.0

_pools: dict[str, ConnectionPool] = {}
_cerrojo = threading.Lock()


def _maximo() -> int:
    try:
        return max(1, int(os.environ.get("POSTGRES_POOL_MAXIMO", MAXIMO_POR_DEFECTO)))
    except ValueError:
        return MAXIMO_POR_DEFECTO


def _pool(dsn: str, tiempo_espera: float) -> ConnectionPool:
    with _cerrojo:
        pool = _pools.get(dsn)
        if pool is None:
            pool = ConnectionPool(
                dsn,
                min_size=0,
                max_size=_maximo(),
                max_idle=OCIOSA_MAXIMO_S,
                kwargs={"row_factory": dict_row, "connect_timeout": max(1, int(tiempo_espera))},
                check=ConnectionPool.check_connection,
                open=True,
                name=f"solventa-{len(_pools) + 1}",
            )
            _pools[dsn] = pool
        return pool


@contextmanager
def conexion(dsn: str, tiempo_espera: float = 2) -> Iterator[psycopg.Connection]:
    """Presta una conexión del pool de `dsn`, como `with psycopg.connect(...)`.

    Al salir confirma la transacción, o la revierte si hubo una excepción, y devuelve la
    conexión al pool. `tiempo_espera` acota tanto abrir una conexión nueva como esperar una
    libre: con la base caída, el llamador recibe `PoolTimeout` en segundos y no se cuelga.
    """
    with _pool(dsn, tiempo_espera).connection(timeout=tiempo_espera) as prestada:
        yield prestada


def cerrar_todos() -> None:
    """Cierra los pools abiertos. Para pruebas y para apagar."""
    with _cerrojo:
        pools = list(_pools.values())
        _pools.clear()
    for pool in pools:
        pool.close()
