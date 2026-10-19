"""DTO de la versión 1 del contrato de cotización.

Estos esquemas son el contrato con los BFF (web y móvil) y son lo único que esos canales
conocen del módulo: nunca ven las entidades de dominio. Por eso el dominio puede
reorganizarse sin romper a nadie, que es lo que promete RC-05.

Regla de versionado: dentro de `v1` solo se agregan campos opcionales. Quitar un campo,
renombrarlo o cambiar su tipo obliga a publicar `v2` y a mantener `v1` en pie.
"""

from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field

from app.modulos.cotizacion.dominio.modelo import (
    BasePerfilamiento,
    Cotizacion,
    EstadoCotizacion,
    ProductoId,
)


class _Dto(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid")


class DatosAseguradoDto(_Dto):
    edad: int = Field(ge=18, le=100, description="Edad del asegurado en años cumplidos")
    ciudad: str = Field(min_length=1, max_length=80)
    valor_asegurado: int | None = Field(
        default=None, ge=0, description="Valor a asegurar en COP, si el producto lo pide"
    )


class SolicitudCotizacionDto(_Dto):
    producto: ProductoId
    asegurado: DatosAseguradoDto
    id_cliente: str | None = Field(
        default=None,
        description=(
            "Cliente autenticado. Sin él se cotiza a precio estándar: no hay "
            "consentimiento que verificar."
        ),
    )


class CoberturaDto(_Dto):
    nombre: str
    limite: str


class FactorDto(_Dto):
    nombre: str
    efecto: float = Field(description="Variación relativa sobre la prima base: -0.06 es -6 %")
    efecto_porcentual: str


class OfertaDto(_Dto):
    prima_mensual: int
    prima_estandar: int
    ahorro_mensual: int
    moneda: str
    valor_asegurado: str
    deducible: str
    coberturas: tuple[CoberturaDto, ...]
    base_perfilamiento: BasePerfilamiento
    uso_open_finance: bool
    version_regla: str


class CotizacionDto(_Dto):
    id_cotizacion: str
    producto: ProductoId
    nombre_producto: str
    estado: EstadoCotizacion
    creada_en: datetime
    expira_en: datetime
    segundos_restantes: int
    oferta: OfertaDto
    id_consentimiento: str | None = None
    id_cotizacion_origen: str | None = None


class ExplicacionDto(_Dto):
    """Respuesta de "¿por qué este precio?" (RC-08)."""

    id_cotizacion: str
    version_regla: str
    base_perfilamiento: BasePerfilamiento
    prima_estandar: int
    prima_mensual: int
    factores: tuple[FactorDto, ...]


class ProductoDto(_Dto):
    id: ProductoId
    nombre: str
    nombre_corto: str
    prima_estandar: int
    moneda: str
    valor_asegurado: str
    deducible: str
    coberturas: tuple[CoberturaDto, ...]


# -- traducción de dominio a DTO -------------------------------------------
# Vive aquí, del lado de la API, para que el dominio no conozca a pydantic.


def a_oferta_dto(cotizacion: Cotizacion) -> OfertaDto:
    oferta = cotizacion.oferta
    return OfertaDto(
        prima_mensual=oferta.prima_mensual.monto,
        prima_estandar=oferta.prima_estandar.monto,
        ahorro_mensual=oferta.ahorro_mensual.monto,
        moneda=oferta.prima_mensual.moneda,
        valor_asegurado=oferta.valor_asegurado,
        deducible=oferta.deducible,
        coberturas=tuple(CoberturaDto(nombre=c.nombre, limite=c.limite) for c in oferta.coberturas),
        base_perfilamiento=oferta.base_perfilamiento,
        uso_open_finance=oferta.base_perfilamiento.uso_open_finance,
        version_regla=oferta.version_regla,
    )


def a_cotizacion_dto(cotizacion: Cotizacion, ahora: datetime) -> CotizacionDto:
    return CotizacionDto(
        id_cotizacion=cotizacion.id_cotizacion,
        producto=cotizacion.producto,
        nombre_producto=cotizacion.nombre_producto,
        estado=cotizacion.estado(ahora),
        creada_en=cotizacion.creada_en,
        expira_en=cotizacion.expira_en,
        segundos_restantes=cotizacion.segundos_restantes(ahora),
        oferta=a_oferta_dto(cotizacion),
        id_consentimiento=cotizacion.id_consentimiento,
        id_cotizacion_origen=cotizacion.id_cotizacion_origen,
    )


def a_explicacion_dto(cotizacion: Cotizacion) -> ExplicacionDto:
    oferta = cotizacion.oferta
    return ExplicacionDto(
        id_cotizacion=cotizacion.id_cotizacion,
        version_regla=oferta.version_regla,
        base_perfilamiento=oferta.base_perfilamiento,
        prima_estandar=oferta.prima_estandar.monto,
        prima_mensual=oferta.prima_mensual.monto,
        factores=tuple(
            FactorDto(
                nombre=f.nombre,
                efecto=f.efecto,
                efecto_porcentual=f.efecto_porcentual,
            )
            for f in oferta.factores
        ),
    )
