"""Repositorio de siniestros en PostgreSQL.

Cumple el mismo puerto que el repositorio en memoria. `guardar_si_nuevo` es una sola
sentencia (`INSERT … ON CONFLICT DO NOTHING`): dos envíos simultáneos con la misma clave no
pueden dejar dos siniestros, aunque lleguen a instancias distintas del núcleo.

El cambio de estado y la respuesta a un requerimiento escriben varias tablas en una sola
transacción, que empieza por el `UPDATE` condicional: si otro llegó primero, se deshace y no
queda nada a medias.
"""

from __future__ import annotations

from collections.abc import Mapping

import psycopg
from psycopg.rows import dict_row

from ..dominio.evidencias import Captura, EvidenciaDelSiniestro
from ..dominio.modelo import DatosDeReporte, EstadoSiniestro, Siniestro, TipoEvento
from ..dominio.seguimiento import EventoDeSeguimiento, Requerimiento
from . import esquema


def _a_siniestro(fila: Mapping[str, object]) -> Siniestro:
    return Siniestro(
        id_siniestro=str(fila["id_siniestro"]),
        referencia=str(fila["referencia"]),
        id_cliente=str(fila["id_cliente"]),
        datos=DatosDeReporte(
            numero_poliza=str(fila["numero_poliza"]),
            tipo_evento=TipoEvento(fila["tipo_evento"]),
            ocurrido_el=fila["ocurrido_el"],  # type: ignore[arg-type]
            descripcion=str(fila["descripcion"]),
            monto_estimado=fila["monto_estimado"],  # type: ignore[arg-type]
        ),
        canal=str(fila["canal"]),
        reportado_en=fila["reportado_en"],  # type: ignore[arg-type]
        estado=EstadoSiniestro(fila["estado"]),
        clave_idempotencia=fila["clave_idempotencia"],  # type: ignore[arg-type]
        estado_actualizado_en=fila.get("estado_actualizado_en"),  # type: ignore[arg-type]
    )


def _a_evento(fila: Mapping[str, object]) -> EventoDeSeguimiento:
    anterior = fila["estado_anterior"]
    return EventoDeSeguimiento(
        id_evento=str(fila["id_evento"]),
        id_siniestro=str(fila["id_siniestro"]),
        estado_anterior=None if anterior is None else EstadoSiniestro(anterior),
        estado_nuevo=EstadoSiniestro(fila["estado_nuevo"]),
        ocurrido_en=fila["ocurrido_en"],  # type: ignore[arg-type]
        actor=str(fila["actor"]),
        motivo=fila["motivo"],  # type: ignore[arg-type]
        id_decision=fila["id_decision"],  # type: ignore[arg-type]
    )


def _a_requerimiento(fila: Mapping[str, object]) -> Requerimiento:
    return Requerimiento(
        id_requerimiento=str(fila["id_requerimiento"]),
        id_siniestro=str(fila["id_siniestro"]),
        descripcion=str(fila["descripcion"]),
        creado_en=fila["creado_en"],  # type: ignore[arg-type]
        id_evento=str(fila["id_evento"]),
        atendido_en=fila["atendido_en"],  # type: ignore[arg-type]
        id_evidencia=fila["id_evidencia"],  # type: ignore[arg-type]
        cerrado_en=fila["cerrado_en"],  # type: ignore[arg-type]
    )


def _parametros_evidencia(evidencia: EvidenciaDelSiniestro) -> dict[str, object]:
    return {
        "id_evidencia": evidencia.id_evidencia,
        "id_siniestro": evidencia.id_siniestro,
        "tipo_contenido": evidencia.tipo_contenido,
        "tamano": evidencia.tamano,
        "nombre_original": evidencia.nombre_original,
        "canal": evidencia.canal,
        "cargada_en": evidencia.cargada_en,
        "tomada_en": evidencia.captura.tomada_en,
        "latitud": evidencia.captura.latitud,
        "longitud": evidencia.captura.longitud,
        "lugar": evidencia.captura.lugar,
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

    def guardar_si_nuevo(self, siniestro: Siniestro) -> Siniestro:
        datos = siniestro.datos
        with self._conectar() as conexion:
            fila = conexion.execute(
                esquema.INSERTAR_SI_NUEVO,
                {
                    "id_siniestro": siniestro.id_siniestro,
                    "referencia": siniestro.referencia,
                    "id_cliente": siniestro.id_cliente,
                    "numero_poliza": datos.numero_poliza,
                    "tipo_evento": str(datos.tipo_evento),
                    "ocurrido_el": datos.ocurrido_el,
                    "descripcion": datos.descripcion,
                    "monto_estimado": datos.monto_estimado,
                    "canal": siniestro.canal,
                    "estado": str(siniestro.estado),
                    "reportado_en": siniestro.reportado_en,
                    "clave_idempotencia": siniestro.clave_idempotencia,
                    "estado_actualizado_en": siniestro.estado_actualizado_en,
                },
            ).fetchone()
            if fila is None:
                fila = conexion.execute(
                    esquema.POR_CLAVE,
                    {
                        "id_cliente": siniestro.id_cliente,
                        "clave_idempotencia": siniestro.clave_idempotencia,
                    },
                ).fetchone()
        return _a_siniestro(fila)  # type: ignore[arg-type]

    def obtener(self, id_cliente: str, id_siniestro: str) -> Siniestro | None:
        with self._conectar() as conexion:
            fila = conexion.execute(
                esquema.POR_ID, {"id_cliente": id_cliente, "id_siniestro": id_siniestro}
            ).fetchone()
        return _a_siniestro(fila) if fila else None

    def del_cliente(self, id_cliente: str) -> tuple[Siniestro, ...]:
        with self._conectar() as conexion:
            filas = conexion.execute(esquema.DEL_CLIENTE, {"id_cliente": id_cliente}).fetchall()
        return tuple(_a_siniestro(f) for f in filas)

    def siguiente_referencia(self, anio: int) -> str:
        with self._conectar() as conexion:
            fila = conexion.execute(esquema.SIGUIENTE_REFERENCIA).fetchone()
        return f"SIN-{anio}-{int(fila['consecutivo']):06d}"  # type: ignore[index]

    def agregar_evidencia(self, evidencia: EvidenciaDelSiniestro) -> None:
        with self._conectar() as conexion:
            conexion.execute(esquema.INSERTAR_EVIDENCIA, _parametros_evidencia(evidencia))

    def evidencias(self, id_siniestro: str) -> tuple[EvidenciaDelSiniestro, ...]:
        with self._conectar() as conexion:
            filas = conexion.execute(esquema.EVIDENCIAS, {"id_siniestro": id_siniestro}).fetchall()
        return tuple(
            EvidenciaDelSiniestro(
                id_evidencia=str(f["id_evidencia"]),
                id_siniestro=str(f["id_siniestro"]),
                tipo_contenido=str(f["tipo_contenido"]),
                tamano=int(f["tamano"]),  # type: ignore[call-overload]
                nombre_original=f["nombre_original"],  # type: ignore[arg-type]
                canal=str(f["canal"]),
                cargada_en=f["cargada_en"],  # type: ignore[arg-type]
                captura=Captura(
                    tomada_en=f["tomada_en"],  # type: ignore[arg-type]
                    latitud=f["latitud"],  # type: ignore[arg-type]
                    longitud=f["longitud"],  # type: ignore[arg-type]
                    lugar=f["lugar"],  # type: ignore[arg-type]
                ),
            )
            for f in filas
        )

    # -- seguimiento (WEB-F06) ---------------------------------------------------------

    def por_id(self, id_siniestro: str) -> Siniestro | None:
        with self._conectar() as conexion:
            fila = conexion.execute(
                esquema.POR_ID_INTERNO, {"id_siniestro": id_siniestro}
            ).fetchone()
        return _a_siniestro(fila) if fila else None

    def aplicar_cambio_de_estado(
        self,
        anterior: EstadoSiniestro,
        evento: EventoDeSeguimiento,
        requerimientos: tuple[Requerimiento, ...],
    ) -> bool:
        instante = evento.ocurrido_en
        with self._conectar() as conexion:
            cursor = conexion.execute(
                esquema.CAMBIAR_ESTADO,
                {
                    "id_siniestro": evento.id_siniestro,
                    "anterior": str(anterior),
                    "nuevo": str(evento.estado_nuevo),
                    "instante": instante,
                },
            )
            if cursor.rowcount != 1:
                conexion.rollback()
                return False
            conexion.execute(
                esquema.INSERTAR_EVENTO,
                {
                    "id_evento": evento.id_evento,
                    "id_siniestro": evento.id_siniestro,
                    "estado_anterior": None
                    if evento.estado_anterior is None
                    else str(evento.estado_anterior),
                    "estado_nuevo": str(evento.estado_nuevo),
                    "ocurrido_en": instante,
                    "actor": evento.actor,
                    "motivo": evento.motivo,
                    "id_decision": evento.id_decision,
                },
            )
            conexion.execute(
                esquema.CERRAR_PENDIENTES,
                {"id_siniestro": evento.id_siniestro, "instante": instante},
            )
            for r in requerimientos:
                conexion.execute(
                    esquema.INSERTAR_REQUERIMIENTO,
                    {
                        "id_requerimiento": r.id_requerimiento,
                        "id_siniestro": r.id_siniestro,
                        "descripcion": r.descripcion,
                        "creado_en": r.creado_en,
                        "id_evento": r.id_evento,
                        "atendido_en": r.atendido_en,
                        "id_evidencia": r.id_evidencia,
                        "cerrado_en": r.cerrado_en,
                    },
                )
        return True

    def eventos(self, id_siniestro: str) -> tuple[EventoDeSeguimiento, ...]:
        with self._conectar() as conexion:
            filas = conexion.execute(esquema.EVENTOS, {"id_siniestro": id_siniestro}).fetchall()
        return tuple(_a_evento(f) for f in filas)

    def requerimientos(self, id_siniestro: str) -> tuple[Requerimiento, ...]:
        with self._conectar() as conexion:
            filas = conexion.execute(
                esquema.REQUERIMIENTOS, {"id_siniestro": id_siniestro}
            ).fetchall()
        return tuple(_a_requerimiento(f) for f in filas)

    def responder_requerimiento(
        self, evidencia: EvidenciaDelSiniestro, id_requerimiento: str
    ) -> bool:
        with self._conectar() as conexion:
            cursor = conexion.execute(
                esquema.ATENDER_REQUERIMIENTO,
                {
                    "id_requerimiento": id_requerimiento,
                    "id_siniestro": evidencia.id_siniestro,
                    "id_evidencia": evidencia.id_evidencia,
                    "instante": evidencia.cargada_en,
                },
            )
            if cursor.rowcount != 1:
                conexion.rollback()
                return False
            conexion.execute(esquema.INSERTAR_EVIDENCIA, _parametros_evidencia(evidencia))
        return True
