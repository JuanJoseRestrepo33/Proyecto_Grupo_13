"""Repositorio de pólizas en PostgreSQL. Cumple el mismo puerto que el de memoria."""

from __future__ import annotations

from collections.abc import Mapping

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb

from app.compartido.dinero import Dinero

from ..dominio.modelo import CoberturaPoliza, Poliza
from . import esquema


def _a_poliza(fila: Mapping[str, object]) -> Poliza:
    prima = fila["prima_mensual"]
    coberturas = fila["coberturas"] or []
    return Poliza(
        numero=str(fila["numero"]),
        id_cliente=str(fila["id_cliente"]),
        producto=str(fila["producto"]),
        inicio=fila["inicio"],  # type: ignore[arg-type]
        fin=fila["fin"],  # type: ignore[arg-type]
        nombre_producto=fila["nombre_producto"],  # type: ignore[arg-type]
        coberturas=tuple(
            CoberturaPoliza(str(c["nombre"]), str(c["limite"]))
            for c in coberturas  # type: ignore[union-attr]
        ),
        deducible=fila["deducible"],  # type: ignore[arg-type]
        valor_asegurado=fila["valor_asegurado"],  # type: ignore[arg-type]
        prima_mensual=None if prima is None else Dinero(int(prima), str(fila["moneda"])),  # type: ignore[call-overload]
        id_suscripcion=fila["id_suscripcion"],  # type: ignore[arg-type]
        id_firma=fila["id_firma"],  # type: ignore[arg-type]
        emitida_en=fila["emitida_en"],  # type: ignore[arg-type]
        canal=fila["canal"],  # type: ignore[arg-type]
        id_documento=fila["id_documento"],  # type: ignore[arg-type]
        id_decision=fila["id_decision"],  # type: ignore[arg-type]
    )


def _parametros(poliza: Poliza) -> dict[str, object]:
    prima = poliza.prima_mensual
    return {
        "numero": poliza.numero,
        "id_cliente": poliza.id_cliente,
        "producto": poliza.producto,
        "inicio": poliza.inicio,
        "fin": poliza.fin,
        "nombre_producto": poliza.nombre_producto,
        "coberturas": Jsonb([{"nombre": c.nombre, "limite": c.limite} for c in poliza.coberturas]),
        "deducible": poliza.deducible,
        "valor_asegurado": poliza.valor_asegurado,
        "prima_mensual": None if prima is None else prima.monto,
        "moneda": None if prima is None else prima.moneda,
        "id_suscripcion": poliza.id_suscripcion,
        "id_firma": poliza.id_firma,
        "emitida_en": poliza.emitida_en,
        "canal": poliza.canal,
        "id_documento": poliza.id_documento,
        "id_decision": poliza.id_decision,
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

    def guardar(self, poliza: Poliza) -> None:
        with self._conectar() as conexion:
            conexion.execute(esquema.GUARDAR, _parametros(poliza))

    def emitir_si_nueva(self, poliza: Poliza) -> Poliza:
        with self._conectar() as conexion:
            fila = conexion.execute(esquema.EMITIR_SI_NUEVA, _parametros(poliza)).fetchone()
            if fila is None:
                fila = conexion.execute(
                    esquema.POR_SUSCRIPCION, {"id_suscripcion": poliza.id_suscripcion}
                ).fetchone()
        return _a_poliza(fila)  # type: ignore[arg-type]

    def por_numero(self, numero: str) -> Poliza | None:
        with self._conectar() as conexion:
            fila = conexion.execute(esquema.POR_NUMERO, {"numero": numero}).fetchone()
        return _a_poliza(fila) if fila else None

    def por_suscripcion(self, id_suscripcion: str) -> Poliza | None:
        with self._conectar() as conexion:
            fila = conexion.execute(
                esquema.POR_SUSCRIPCION, {"id_suscripcion": id_suscripcion}
            ).fetchone()
        return _a_poliza(fila) if fila else None

    def del_cliente(self, id_cliente: str) -> tuple[Poliza, ...]:
        with self._conectar() as conexion:
            filas = conexion.execute(esquema.DEL_CLIENTE, {"id_cliente": id_cliente}).fetchall()
        return tuple(_a_poliza(f) for f in filas)

    def siguiente_numero(self, anio: int) -> str:
        with self._conectar() as conexion:
            fila = conexion.execute(esquema.SIGUIENTE_NUMERO).fetchone()
        return f"POL-{anio}-{int(fila['consecutivo']):06d}"  # type: ignore[index]
