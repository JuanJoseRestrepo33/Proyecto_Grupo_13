"""Repositorio de pagos en PostgreSQL. Cumple el mismo puerto que el de memoria.

`reservar` es un `INSERT … ON CONFLICT DO NOTHING` seguido de la lectura de lo que lo
impidió: si dos réplicas reciben la misma clave a la vez, solo una inserta y la otra lee el
intento ganador.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence

import psycopg
from psycopg.rows import dict_row

from app.compartido.dinero import Dinero
from app.compartido.errores import DependenciaNoDisponible

from ..dominio.modelo import EstadoPago, MedioPago, Pago
from . import esquema


def _a_pago(fila: Mapping[str, object]) -> Pago:
    return Pago(
        id_pago=str(fila["id_pago"]),
        concepto=str(fila["concepto"]),
        id_cliente=str(fila["id_cliente"]),
        medio=MedioPago(fila["medio"]),
        monto=Dinero(int(fila["monto"]), str(fila["moneda"])),  # type: ignore[call-overload]
        clave_idempotencia=str(fila["clave_idempotencia"]),
        canal=str(fila["canal"]),
        creado_en=fila["creado_en"],  # type: ignore[arg-type]
        estado=EstadoPago(fila["estado"]),
        referencia=fila["referencia"],  # type: ignore[arg-type]
        actualizado_en=fila["actualizado_en"],  # type: ignore[arg-type]
        id_decision=fila["id_decision"],  # type: ignore[arg-type]
    )


def _parametros(pago: Pago) -> dict[str, object]:
    return {
        "id_pago": pago.id_pago,
        "concepto": pago.concepto,
        "id_cliente": pago.id_cliente,
        "medio": str(pago.medio),
        "monto": pago.monto.monto,
        "moneda": pago.monto.moneda,
        "clave_idempotencia": pago.clave_idempotencia,
        "canal": pago.canal,
        "estado": str(pago.estado),
        "referencia": pago.referencia,
        "creado_en": pago.creado_en,
        "actualizado_en": pago.actualizado_en,
        "id_decision": pago.id_decision,
    }


class RepositorioPostgres:
    def __init__(self, dsn: str, tiempo_espera_conexion: int = 2) -> None:
        self._dsn = dsn
        self._tiempo_espera = tiempo_espera_conexion
        self._preparado = False

    def _abrir(self) -> psycopg.Connection:
        return psycopg.connect(self._dsn, row_factory=dict_row, connect_timeout=self._tiempo_espera)

    def _conectar(self) -> psycopg.Connection:
        if not self._preparado:
            self.preparar()
        return self._abrir()

    def preparar(self) -> None:
        """Aplica el esquema. Idempotente."""
        with self._abrir() as conexion:
            conexion.execute(esquema.TABLA)
        self._preparado = True

    def verificar_conexion(self) -> None:
        with self._abrir() as conexion:
            conexion.execute("SELECT 1")

    def reservar(self, pago: Pago) -> Pago:
        # Si el pago que impidió la inserción terminó antes de leerlo, se reintenta una sola
        # vez; si vuelve a pasar, la contención es anómala y se informa en vez de insistir.
        for _ in range(2):
            fila = self._intentar_reserva(pago)
            if fila is not None:
                return _a_pago(fila)
        raise DependenciaNoDisponible(
            "No se pudo reservar el pago por contención", proveedor="repositorio_pagos"
        )

    def _intentar_reserva(self, pago: Pago) -> Mapping[str, object] | None:
        with self._conectar() as conexion:
            fila = conexion.execute(esquema.INSERTAR_SI_LIBRE, _parametros(pago)).fetchone()
            if fila is None:
                fila = conexion.execute(
                    esquema.POR_CLAVE, {"clave": pago.clave_idempotencia}
                ).fetchone()
            if fila is None:
                fila = conexion.execute(
                    esquema.ACTIVO_DEL_CONCEPTO, {"concepto": pago.concepto}
                ).fetchone()
        return fila

    def actualizar(self, pago: Pago) -> None:
        with self._conectar() as conexion:
            conexion.execute(esquema.ACTUALIZAR, _parametros(pago))

    def liberar(self, id_pago: str) -> None:
        with self._conectar() as conexion:
            conexion.execute(esquema.LIBERAR, {"id_pago": id_pago})

    def obtener(self, id_pago: str) -> Pago | None:
        with self._conectar() as conexion:
            fila = conexion.execute(esquema.POR_ID, {"id_pago": id_pago}).fetchone()
        return _a_pago(fila) if fila else None

    def del_concepto(self, concepto: str) -> tuple[Pago, ...]:
        with self._conectar() as conexion:
            filas = conexion.execute(esquema.DEL_CONCEPTO, {"concepto": concepto}).fetchall()
        return tuple(_a_pago(f) for f in filas)

    def ultimos(self, conceptos: Sequence[str]) -> dict[str, Pago]:
        with self._conectar() as conexion:
            filas = conexion.execute(esquema.ULTIMOS, {"conceptos": list(conceptos)}).fetchall()
        return {str(f["concepto"]): _a_pago(f) for f in filas}
