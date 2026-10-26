"""Modelo del siniestro reportado (WEB-F05).

Un siniestro nace `reportado` con un número de referencia que el cliente ve en los dos
canales. La póliza se referencia por su número, nunca por objeto: vive en otro módulo (ARQ-01).

El reporte es idempotente por la clave que manda el canal (`Idempotency-Key`): un doble
clic o un reintento por red lenta devuelve el mismo siniestro y la misma referencia
(P10, P18). La huella de los datos distingue un reintento de una clave reutilizada para
otro reporte.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from datetime import date, datetime
from enum import StrEnum


class TipoEvento(StrEnum):
    ACCIDENTE_VEHICULO = "accidente_vehiculo"
    HURTO = "hurto"
    DANO_HOGAR = "dano_hogar"
    RETRASO_VUELO = "retraso_vuelo"
    EQUIPO_DANADO_O_PERDIDO = "equipo_danado_o_perdido"
    CANCELACION_VIAJE = "cancelacion_viaje"
    OTRO = "otro"


class EstadoSiniestro(StrEnum):
    """Los estados del seguimiento (WEB-F06). En el Sprint 2 solo existe `reportado`."""

    REPORTADO = "reportado"
    EN_EVALUACION = "en_evaluacion"
    REQUIERE_INFORMACION = "requiere_informacion"
    APROBADO = "aprobado"
    RECHAZADO = "rechazado"
    PAGADO = "pagado"


class SiguientePaso(StrEnum):
    """Qué le toca al cliente ahora. Cada canal lo dice con su propio texto."""

    ADJUNTAR_EVIDENCIA = "adjuntar_evidencia"
    ESPERAR_EVALUACION = "esperar_evaluacion"


@dataclass(frozen=True, slots=True)
class DatosDeReporte:
    numero_poliza: str
    tipo_evento: TipoEvento
    ocurrido_el: date
    descripcion: str
    monto_estimado: int | None = None

    def huella(self) -> str:
        """Lo que hace que dos reportes con la misma clave sean el mismo."""
        canonico = json.dumps(
            {
                "numero_poliza": self.numero_poliza,
                "tipo_evento": str(self.tipo_evento),
                "ocurrido_el": self.ocurrido_el.isoformat(),
                "descripcion": self.descripcion.strip(),
                "monto_estimado": self.monto_estimado,
            },
            sort_keys=True,
            ensure_ascii=False,
        )
        return hashlib.sha256(canonico.encode()).hexdigest()


@dataclass(frozen=True, slots=True)
class Siniestro:
    id_siniestro: str
    referencia: str
    id_cliente: str
    datos: DatosDeReporte
    canal: str
    reportado_en: datetime
    estado: EstadoSiniestro = EstadoSiniestro.REPORTADO
    clave_idempotencia: str | None = None

    @property
    def siguiente_paso(self) -> SiguientePaso:
        if self.estado is EstadoSiniestro.REPORTADO:
            return SiguientePaso.ADJUNTAR_EVIDENCIA
        return SiguientePaso.ESPERAR_EVALUACION
