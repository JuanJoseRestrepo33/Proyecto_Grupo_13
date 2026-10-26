"""Modelo de dominio de la capacidad de cotización.

Entidad raíz: `Cotizacion`. Sus vínculos con las demás entidades de la arquitectura
son por identificador, nunca por objeto, porque viven en otros módulos y en otra base
de datos (database per bounded context):

    Cliente        → `id_cliente`        (módulo identidad)
    Consentimiento → `id_consentimiento` (módulo consentimiento, WEB-F02)
    Perfil riesgo  → `PerfilDeRiesgo`    (módulo perfilamiento, copia inmutable del
                                          perfil en el instante en que se tarifó)

El perfil se copia en lugar de referenciarse a propósito: una oferta tiene que poder
reconstruirse años después tal como se calculó, aunque el perfil del cliente ya haya
cambiado. Es lo que exige RC-08.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta
from enum import StrEnum

from app.compartido.dinero import Dinero
from app.compartido.errores import ReglaDeNegocioViolada

#: Vigencia de una oferta. Pasado este tiempo hay que recotizar (regla de WEB-F01).
VIGENCIA_OFERTA = timedelta(minutes=5)

#: Canal de una cotización pedida directo a la API, sin pasar por un BFF.
CANAL_API = "api-v1"


class ProductoId(StrEnum):
    AUTO = "auto"
    HOGAR = "hogar"
    VIAJE = "viaje"
    DISPOSITIVOS = "dispositivos"
    VIDA_HIPOTECARIO = "vida_hipotecario"


class BasePerfilamiento(StrEnum):
    """Con qué información se calculó la prima. Es la explicación del precio."""

    #: Consentimiento vigente y perfil obtenido del proveedor.
    OPEN_FINANCE = "open_finance"
    #: Consentimiento vigente pero el proveedor estaba degradado: se usó la caché.
    OPEN_FINANCE_EN_CACHE = "open_finance_en_cache"
    #: Sin consentimiento vigente. Tarifa de catálogo, sin datos financieros.
    ESTANDAR = "estandar"

    @property
    def uso_open_finance(self) -> bool:
        return self is not BasePerfilamiento.ESTANDAR


class MotivoTarifa(StrEnum):
    """Por qué una oferta salió a tarifa estándar. Se le explica al cliente; no es un error."""

    #: Sin consentimiento vigente para la finalidad de cotización, o cliente anónimo.
    SIN_CONSENTIMIENTO = "sin_consentimiento"
    #: El cliente pidió cotizar sin sus datos financieros.
    OPEN_FINANCE_DESACTIVADO = "open_finance_desactivado"
    #: Había consentimiento, pero el proveedor no respondió y no había perfil en caché.
    PROVEEDOR_NO_DISPONIBLE = "proveedor_no_disponible"


class EstadoCotizacion(StrEnum):
    VIGENTE = "vigente"
    VENCIDA = "vencida"


@dataclass(frozen=True, slots=True)
class Cobertura:
    nombre: str
    limite: str


@dataclass(frozen=True, slots=True)
class FactorAplicado:
    """Un factor del tarificador y su efecto sobre la prima base.

    `efecto` es la variación relativa: -0.06 significa que bajó la prima un 6 %.
    Se guarda junto a la oferta porque RC-08 exige poder explicar cada precio.
    """

    nombre: str
    efecto: float

    @property
    def efecto_porcentual(self) -> str:
        signo = "+" if self.efecto > 0 else ("−" if self.efecto < 0 else "")
        return f"{signo}{abs(self.efecto) * 100:.0f}%"


@dataclass(frozen=True, slots=True)
class PerfilDeRiesgo:
    """Copia inmutable del perfil usado para tarifar."""

    puntaje: int
    comportamiento_pago: str
    nivel_endeudamiento: str
    desde_cache: bool = False


@dataclass(frozen=True, slots=True)
class Oferta:
    prima_mensual: Dinero
    prima_estandar: Dinero
    valor_asegurado: str
    deducible: str
    coberturas: tuple[Cobertura, ...]
    base_perfilamiento: BasePerfilamiento
    version_regla: str
    factores: tuple[FactorAplicado, ...]

    @property
    def ahorro_mensual(self) -> Dinero:
        """Cuánto ahorra el cliente frente al precio estándar. Nunca negativo."""
        return self.prima_estandar - self.prima_mensual


@dataclass(frozen=True, slots=True)
class DatosAsegurado:
    """Datos de entrada del flujo web de cotización."""

    edad: int
    ciudad: str
    valor_asegurado: int | None = None

    def __post_init__(self) -> None:
        if not 18 <= self.edad <= 100:
            raise ReglaDeNegocioViolada(
                "la edad del asegurado debe estar entre 18 y 100 años",
                edad=self.edad,
            )
        if not self.ciudad.strip():
            raise ReglaDeNegocioViolada("la ciudad del asegurado es obligatoria")


@dataclass(slots=True)
class Cotizacion:
    id_cotizacion: str
    producto: ProductoId
    nombre_producto: str
    asegurado: DatosAsegurado
    oferta: Oferta
    creada_en: datetime
    id_cliente: str | None = None
    id_consentimiento: str | None = None
    perfil: PerfilDeRiesgo | None = None
    id_cotizacion_origen: str | None = None
    vigencia: timedelta = field(default=VIGENCIA_OFERTA)
    motivo_tarifa: MotivoTarifa | None = None
    usar_open_finance: bool = True
    canal: str = CANAL_API

    @property
    def expira_en(self) -> datetime:
        return self.creada_en + self.vigencia

    def estado(self, ahora: datetime) -> EstadoCotizacion:
        if ahora >= self.expira_en:
            return EstadoCotizacion.VENCIDA
        return EstadoCotizacion.VIGENTE

    def segundos_restantes(self, ahora: datetime) -> int:
        return max(int((self.expira_en - ahora).total_seconds()), 0)

    def exigir_vigente(self, ahora: datetime) -> None:
        """La oferta solo se puede tomar mientras esté vigente."""
        if self.estado(ahora) is EstadoCotizacion.VENCIDA:
            raise OfertaVencidaError(
                f"la oferta {self.id_cotizacion} venció; hay que recotizar",
                id_cotizacion=self.id_cotizacion,
                expiro_en=self.expira_en.isoformat(),
            )


class OfertaVencidaError(ReglaDeNegocioViolada):
    codigo = "oferta_vencida"
