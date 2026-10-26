"""Reglas de la evidencia de un siniestro (MOB-F05), antes de llegar al almacén.

El puerto de almacenamiento solo guarda lo que recibe (`almacen_evidencias.py`); los límites
de negocio se validan aquí: hasta 10 MB y solo fotos, videos o documentos PDF. El formato se
comprueba con los primeros bytes del archivo y no solo con el tipo declarado: un ejecutable
renombrado a `.jpg` no pasa.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta

from app.compartido.errores import ErrorDeNegocio

#: Límite por archivo, el mismo que la web valida antes de enviar (criterio 4).
TAMANO_MAXIMO = 10 * 1024 * 1024

#: Tipos admitidos y la firma de sus primeros bytes.
FORMATOS_ADMITIDOS = ("image/jpeg", "image/png", "image/heic", "video/mp4", "application/pdf")


class FormatoNoAdmitidoError(ErrorDeNegocio):
    codigo = "formato_no_admitido"


class ArchivoDemasiadoGrandeError(ErrorDeNegocio):
    codigo = "archivo_demasiado_grande"


class ArchivoVacioError(ErrorDeNegocio):
    codigo = "archivo_vacio"


class UbicacionInvalidaError(ErrorDeNegocio):
    codigo = "ubicacion_invalida"


class FechaDeCapturaInvalidaError(ErrorDeNegocio):
    codigo = "fecha_de_captura_invalida"


#: Holgura para el reloj del teléfono, que puede ir unos minutos adelantado.
TOLERANCIA_RELOJ = timedelta(minutes=5)
LARGO_MAXIMO_LUGAR = 200


@dataclass(frozen=True, slots=True)
class Captura:
    """Cuándo y dónde se tomó la evidencia (MOB-F05, criterios 1 y 2).

    La app manda la fecha y hora de la cámara y, si el cliente concedió el permiso, la
    ubicación. Si lo negó, puede escribir el lugar: el reporte nunca queda bloqueado por un
    permiso (P20). Todo es opcional.
    """

    tomada_en: datetime | None = None
    latitud: float | None = None
    longitud: float | None = None
    lugar: str | None = None

    def validada(self, ahora: datetime) -> Captura:
        if (self.latitud is None) != (self.longitud is None):
            raise UbicacionInvalidaError("La ubicación necesita latitud y longitud")
        if self.latitud is not None and not -90 <= self.latitud <= 90:
            raise UbicacionInvalidaError("La latitud va de -90 a 90", latitud=self.latitud)
        if self.longitud is not None and not -180 <= self.longitud <= 180:
            raise UbicacionInvalidaError("La longitud va de -180 a 180", longitud=self.longitud)
        if self.tomada_en is not None:
            if self.tomada_en.tzinfo is None:
                raise FechaDeCapturaInvalidaError("La fecha de captura necesita zona horaria")
            if self.tomada_en > ahora + TOLERANCIA_RELOJ:
                raise FechaDeCapturaInvalidaError("La fecha de captura no puede ser futura")
        lugar = (self.lugar or "").strip()[:LARGO_MAXIMO_LUGAR] or None
        return Captura(self.tomada_en, self.latitud, self.longitud, lugar)


def _es_mp4_o_heic(contenido: bytes) -> str | None:
    """Los dos son ISO-BMFF: `ftyp` en el byte 4 y la marca del formato después."""
    if contenido[4:8] != b"ftyp":
        return None
    marca = contenido[8:12]
    if marca in (b"heic", b"heix", b"mif1", b"msf1"):
        return "image/heic"
    return "video/mp4"


def tipo_real(contenido: bytes) -> str | None:
    """El formato que dicen los primeros bytes, o None si no es uno de los admitidos."""
    if contenido.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if contenido.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if contenido.startswith(b"%PDF-"):
        return "application/pdf"
    return _es_mp4_o_heic(contenido)


def exigir_evidencia_valida(tipo_contenido: str, contenido: bytes) -> str:
    """Valida tamaño y formato. Devuelve el tipo normalizado, que es el que se guarda."""
    if not contenido:
        raise ArchivoVacioError("El archivo está vacío")
    if len(contenido) > TAMANO_MAXIMO:
        raise ArchivoDemasiadoGrandeError(
            "El archivo supera los 10 MB", tamano=len(contenido), maximo=TAMANO_MAXIMO
        )
    declarado = tipo_contenido.split(";")[0].strip().lower()
    if declarado == "image/jpg":
        declarado = "image/jpeg"
    if declarado == "video/quicktime":
        declarado = "video/mp4"
    real = tipo_real(contenido)
    if declarado not in FORMATOS_ADMITIDOS or real != declarado:
        raise FormatoNoAdmitidoError(
            "Solo se admiten fotos (JPEG, PNG, HEIC), videos MP4 y documentos PDF",
            tipo_declarado=declarado,
            admitidos=list(FORMATOS_ADMITIDOS),
        )
    return real


@dataclass(frozen=True, slots=True)
class EvidenciaDelSiniestro:
    """Lo que el siniestro sabe de una evidencia: el archivo está en el almacén."""

    id_evidencia: str
    id_siniestro: str
    tipo_contenido: str
    tamano: int
    nombre_original: str | None
    canal: str
    cargada_en: datetime
    captura: Captura = Captura()
