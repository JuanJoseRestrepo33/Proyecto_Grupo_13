"""Repositorio de cotizaciones en PostgreSQL.

Cumple el mismo puerto que el repositorio en memoria. Lo que cambia es que una cotización
sobrevive a un reinicio o a una revisión nueva del núcleo: sigue consultable con su estado y
con todo lo necesario para explicar y recalcular su prima (WEB-F01, criterio 5).
"""

from __future__ import annotations

from collections.abc import Mapping
from datetime import datetime, timedelta

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb

from app.compartido.dinero import Dinero
from app.modulos.cotizacion.dominio.modelo import (
    BasePerfilamiento,
    Cobertura,
    Cotizacion,
    DatosAsegurado,
    FactorAplicado,
    MotivoTarifa,
    Oferta,
    PerfilDeRiesgo,
    ProductoId,
)

from . import esquema


def _dinero(valor: Dinero) -> dict[str, object]:
    return {"monto": valor.monto, "moneda": valor.moneda}


def _a_datos(cotizacion: Cotizacion) -> dict[str, object]:
    oferta = cotizacion.oferta
    perfil = cotizacion.perfil
    return {
        "producto": str(cotizacion.producto),
        "nombre_producto": cotizacion.nombre_producto,
        "asegurado": {
            "edad": cotizacion.asegurado.edad,
            "ciudad": cotizacion.asegurado.ciudad,
            "valor_asegurado": cotizacion.asegurado.valor_asegurado,
        },
        "oferta": {
            "prima_mensual": _dinero(oferta.prima_mensual),
            "prima_estandar": _dinero(oferta.prima_estandar),
            "valor_asegurado": oferta.valor_asegurado,
            "deducible": oferta.deducible,
            "coberturas": [{"nombre": c.nombre, "limite": c.limite} for c in oferta.coberturas],
            "base_perfilamiento": str(oferta.base_perfilamiento),
            "version_regla": oferta.version_regla,
            "factores": [{"nombre": f.nombre, "efecto": f.efecto} for f in oferta.factores],
        },
        "id_consentimiento": cotizacion.id_consentimiento,
        "perfil": None
        if perfil is None
        else {
            "puntaje": perfil.puntaje,
            "comportamiento_pago": perfil.comportamiento_pago,
            "nivel_endeudamiento": perfil.nivel_endeudamiento,
            "desde_cache": perfil.desde_cache,
        },
        "id_cotizacion_origen": cotizacion.id_cotizacion_origen,
        "vigencia_segundos": cotizacion.vigencia.total_seconds(),
        "motivo_tarifa": str(cotizacion.motivo_tarifa) if cotizacion.motivo_tarifa else None,
        "usar_open_finance": cotizacion.usar_open_finance,
        "canal": cotizacion.canal,
    }


def _a_cotizacion(fila: Mapping[str, object]) -> Cotizacion:
    datos: dict = fila["datos"]  # type: ignore[assignment]
    oferta = datos["oferta"]
    perfil = datos["perfil"]
    creada_en: datetime = fila["creada_en"]  # type: ignore[assignment]
    return Cotizacion(
        id_cotizacion=str(fila["id_cotizacion"]),
        producto=ProductoId(datos["producto"]),
        nombre_producto=datos["nombre_producto"],
        asegurado=DatosAsegurado(**datos["asegurado"]),
        oferta=Oferta(
            prima_mensual=Dinero(**oferta["prima_mensual"]),
            prima_estandar=Dinero(**oferta["prima_estandar"]),
            valor_asegurado=oferta["valor_asegurado"],
            deducible=oferta["deducible"],
            coberturas=tuple(Cobertura(**c) for c in oferta["coberturas"]),
            base_perfilamiento=BasePerfilamiento(oferta["base_perfilamiento"]),
            version_regla=oferta["version_regla"],
            factores=tuple(FactorAplicado(**f) for f in oferta["factores"]),
        ),
        creada_en=creada_en,
        id_cliente=fila["id_cliente"],  # type: ignore[arg-type]
        id_consentimiento=datos["id_consentimiento"],
        perfil=None if perfil is None else PerfilDeRiesgo(**perfil),
        id_cotizacion_origen=datos["id_cotizacion_origen"],
        vigencia=timedelta(seconds=datos["vigencia_segundos"]),
        motivo_tarifa=MotivoTarifa(datos["motivo_tarifa"]) if datos["motivo_tarifa"] else None,
        usar_open_finance=datos["usar_open_finance"],
        canal=datos["canal"],
        huella=fila["huella"],  # type: ignore[arg-type]
    )


class RepositorioPostgres:
    """`tiempo_espera_conexion` acota cuánto puede tardar una cotización o `/salud` si la
    base no responde, igual que en el almacén de la auditoría."""

    def __init__(self, dsn: str, tiempo_espera_conexion: int = 2) -> None:
        self._dsn = dsn
        self._tiempo_espera = tiempo_espera_conexion
        self._preparado = False

    def _conectar(self) -> psycopg.Connection:
        if not self._preparado:
            self.preparar()
        return self._abrir()

    def _abrir(self) -> psycopg.Connection:
        return psycopg.connect(self._dsn, row_factory=dict_row, connect_timeout=self._tiempo_espera)

    def preparar(self) -> None:
        """Aplica el esquema. Idempotente."""
        with self._abrir() as conexion:
            conexion.execute(esquema.TABLA)
        self._preparado = True

    def verificar_conexion(self) -> None:
        with self._abrir() as conexion:
            conexion.execute("SELECT 1")

    def guardar(self, cotizacion: Cotizacion) -> None:
        with self._conectar() as conexion:
            conexion.execute(
                esquema.INSERTAR,
                {
                    "id_cotizacion": cotizacion.id_cotizacion,
                    "id_cliente": cotizacion.id_cliente,
                    "huella": cotizacion.huella,
                    "creada_en": cotizacion.creada_en,
                    "expira_en": cotizacion.expira_en,
                    "datos": Jsonb(_a_datos(cotizacion)),
                },
            )

    def obtener(self, id_cotizacion: str) -> Cotizacion | None:
        with self._conectar() as conexion:
            fila = conexion.execute(esquema.POR_ID, {"id": id_cotizacion}).fetchone()
        return _a_cotizacion(fila) if fila else None

    def vigente_por_huella(
        self, id_cliente: str, huella: str, ahora: datetime
    ) -> Cotizacion | None:
        with self._conectar() as conexion:
            fila = conexion.execute(
                esquema.VIGENTE_POR_HUELLA,
                {"id_cliente": id_cliente, "huella": huella, "ahora": ahora},
            ).fetchone()
        return _a_cotizacion(fila) if fila else None

    def siguiente_id(self, anio: int) -> str:
        with self._conectar() as conexion:
            fila = conexion.execute(esquema.SIGUIENTE_CONSECUTIVO).fetchone()
        return f"QT-{anio}-{int(fila['consecutivo']):05d}"  # type: ignore[index]
