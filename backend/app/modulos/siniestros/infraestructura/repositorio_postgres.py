"""Repositorio de siniestros en PostgreSQL.

Cumple el mismo puerto que el repositorio en memoria. `guardar_si_nuevo` es una sola
sentencia (`INSERT … ON CONFLICT DO NOTHING`): dos envíos simultáneos con la misma clave no
pueden dejar dos siniestros, aunque lleguen a instancias distintas del núcleo.
"""

from __future__ import annotations

from collections.abc import Mapping

import psycopg
from psycopg.rows import dict_row

from ..dominio.evidencias import EvidenciaDelSiniestro
from ..dominio.modelo import DatosDeReporte, EstadoSiniestro, Siniestro, TipoEvento
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
    )


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
            conexion.execute(
                esquema.INSERTAR_EVIDENCIA,
                {
                    "id_evidencia": evidencia.id_evidencia,
                    "id_siniestro": evidencia.id_siniestro,
                    "tipo_contenido": evidencia.tipo_contenido,
                    "tamano": evidencia.tamano,
                    "nombre_original": evidencia.nombre_original,
                    "canal": evidencia.canal,
                    "cargada_en": evidencia.cargada_en,
                },
            )

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
            )
            for f in filas
        )
