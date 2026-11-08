"""Repositorio de suscripciones en PostgreSQL. Cumple el mismo puerto que el de memoria."""

from __future__ import annotations

from collections.abc import Mapping
from datetime import datetime

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb

from app.compartido.dinero import Dinero

from ..dominio.modelo import (
    CoberturaOfertada,
    CondicionesDeOferta,
    EstadoSuscripcion,
    Paso,
    PasoAuditado,
    Suscripcion,
)
from . import esquema


def _condiciones_a_json(condiciones: CondicionesDeOferta) -> dict[str, object]:
    return {
        "id_cotizacion": condiciones.id_cotizacion,
        "producto": condiciones.producto,
        "nombre_producto": condiciones.nombre_producto,
        "prima_mensual": condiciones.prima_mensual.monto,
        "moneda": condiciones.prima_mensual.moneda,
        "valor_asegurado": condiciones.valor_asegurado,
        "deducible": condiciones.deducible,
        "coberturas": [{"nombre": c.nombre, "limite": c.limite} for c in condiciones.coberturas],
    }


def _condiciones_de_json(datos: Mapping[str, object]) -> CondicionesDeOferta:
    return CondicionesDeOferta(
        id_cotizacion=str(datos["id_cotizacion"]),
        producto=str(datos["producto"]),
        nombre_producto=str(datos["nombre_producto"]),
        prima_mensual=Dinero(int(datos["prima_mensual"]), str(datos["moneda"])),  # type: ignore[call-overload]
        valor_asegurado=str(datos["valor_asegurado"]),
        deducible=str(datos["deducible"]),
        coberturas=tuple(
            CoberturaOfertada(str(c["nombre"]), str(c["limite"]))
            for c in datos["coberturas"]  # type: ignore[attr-defined]
        ),
    )


def _a_suscripcion(fila: Mapping[str, object]) -> Suscripcion:
    return Suscripcion(
        id_suscripcion=str(fila["id_suscripcion"]),
        id_cliente=str(fila["id_cliente"]),
        condiciones=_condiciones_de_json(fila["condiciones"]),  # type: ignore[arg-type]
        canal=str(fila["canal"]),
        aceptada_en=fila["aceptada_en"],  # type: ignore[arg-type]
        estado=EstadoSuscripcion(fila["estado"]),
        id_pago=fila["id_pago"],  # type: ignore[arg-type]
        pagada_en=fila["pagada_en"],  # type: ignore[arg-type]
        id_firma=fila["id_firma"],  # type: ignore[arg-type]
        firmada_en=fila["firmada_en"],  # type: ignore[arg-type]
        numero_poliza=fila["numero_poliza"],  # type: ignore[arg-type]
        poliza_inicio=fila["poliza_inicio"],  # type: ignore[arg-type]
        poliza_fin=fila["poliza_fin"],  # type: ignore[arg-type]
        emitida_en=fila["emitida_en"],  # type: ignore[arg-type]
        trazabilidad=tuple(
            PasoAuditado(
                Paso(p["paso"]), str(p["id_decision"]), datetime.fromisoformat(p["ocurrido_en"])
            )
            for p in fila["trazabilidad"]  # type: ignore[attr-defined]
        ),
    )


def _parametros(suscripcion: Suscripcion) -> dict[str, object]:
    return {
        "id_suscripcion": suscripcion.id_suscripcion,
        "id_cotizacion": suscripcion.id_cotizacion,
        "id_cliente": suscripcion.id_cliente,
        "condiciones": Jsonb(_condiciones_a_json(suscripcion.condiciones)),
        "canal": suscripcion.canal,
        "aceptada_en": suscripcion.aceptada_en,
        "estado": str(suscripcion.estado),
        "id_pago": suscripcion.id_pago,
        "pagada_en": suscripcion.pagada_en,
        "id_firma": suscripcion.id_firma,
        "firmada_en": suscripcion.firmada_en,
        "numero_poliza": suscripcion.numero_poliza,
        "poliza_inicio": suscripcion.poliza_inicio,
        "poliza_fin": suscripcion.poliza_fin,
        "emitida_en": suscripcion.emitida_en,
        "trazabilidad": Jsonb(
            [
                {
                    "paso": str(p.paso),
                    "id_decision": p.id_decision,
                    "ocurrido_en": p.ocurrido_en.isoformat(),
                }
                for p in suscripcion.trazabilidad
            ]
        ),
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

    def guardar_si_nueva(self, suscripcion: Suscripcion) -> Suscripcion:
        with self._conectar() as conexion:
            fila = conexion.execute(esquema.INSERTAR_SI_NUEVA, _parametros(suscripcion)).fetchone()
            if fila is None:
                fila = conexion.execute(
                    esquema.POR_COTIZACION, {"id_cotizacion": suscripcion.id_cotizacion}
                ).fetchone()
        return _a_suscripcion(fila)  # type: ignore[arg-type]

    def actualizar(self, suscripcion: Suscripcion) -> None:
        with self._conectar() as conexion:
            conexion.execute(esquema.ACTUALIZAR, _parametros(suscripcion))

    def obtener(self, id_suscripcion: str) -> Suscripcion | None:
        with self._conectar() as conexion:
            fila = conexion.execute(esquema.POR_ID, {"id_suscripcion": id_suscripcion}).fetchone()
        return _a_suscripcion(fila) if fila else None

    def por_cotizacion(self, id_cotizacion: str) -> Suscripcion | None:
        with self._conectar() as conexion:
            fila = conexion.execute(
                esquema.POR_COTIZACION, {"id_cotizacion": id_cotizacion}
            ).fetchone()
        return _a_suscripcion(fila) if fila else None

    def del_cliente(self, id_cliente: str) -> tuple[Suscripcion, ...]:
        with self._conectar() as conexion:
            filas = conexion.execute(esquema.DEL_CLIENTE, {"id_cliente": id_cliente}).fetchall()
        return tuple(_a_suscripcion(f) for f in filas)
