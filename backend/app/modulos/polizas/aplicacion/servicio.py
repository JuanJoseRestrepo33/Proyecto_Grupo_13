"""Consulta de pólizas por su interfaz pública.

Otros módulos preguntan aquí, nunca leyendo los datos de pólizas directamente (ARQ-01). El
reporte de siniestros valida la vigencia con `poliza_del_cliente`.
"""

from __future__ import annotations

from collections.abc import Sequence
from datetime import date

from app.compartido.reloj import Reloj

from ..dominio.modelo import PlantillaDePoliza, Poliza
from ..dominio.puertos import RepositorioPolizas


class ServicioDePolizas:
    def __init__(
        self,
        repositorio: RepositorioPolizas,
        reloj: Reloj,
        plantillas: Sequence[PlantillaDePoliza] = (),
    ) -> None:
        self._repositorio = repositorio
        self._reloj = reloj
        self._plantillas = tuple(plantillas)

    def del_cliente(self, id_cliente: str) -> tuple[Poliza, ...]:
        """Las pólizas del cliente, de la más reciente a la más antigua."""
        propias = self._repositorio.del_cliente(id_cliente)
        de_plantilla = tuple(p.para(id_cliente) for p in self._plantillas)
        return tuple(sorted(propias + de_plantilla, key=lambda p: p.inicio, reverse=True))

    def poliza_del_cliente(self, numero: str, id_cliente: str) -> Poliza | None:
        """La póliza si existe y es del cliente; si no, None, sin distinguir los dos casos."""
        poliza = self._repositorio.por_numero(numero)
        if poliza is not None:
            return poliza if poliza.id_cliente == id_cliente else None
        return next((p for p in self.del_cliente(id_cliente) if p.numero == numero), None)

    def hoy(self) -> date:
        """El día de hoy según el reloj del módulo, para calcular el estado de una póliza."""
        return self._reloj.ahora().date()
