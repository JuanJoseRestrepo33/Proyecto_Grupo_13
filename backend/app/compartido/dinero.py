"""Valor monetario del núcleo.

Se guarda como entero en la unidad mínima de la moneda para no arrastrar los errores
de redondeo del punto flotante: una prima nunca debe valer 132899.99999999997.
El peso colombiano se opera en unidades enteras, sin centavos.
"""

from __future__ import annotations

from dataclasses import dataclass

MONEDA_PREDETERMINADA = "COP"
#: Las primas se publican redondeadas a la centena más cercana, como en el tarifario.
REDONDEO_COP = 100


class MonedasIncompatiblesError(ValueError):
    """Se intentó operar dos importes de monedas distintas."""


@dataclass(frozen=True, slots=True, order=True)
class Dinero:
    monto: int
    moneda: str = MONEDA_PREDETERMINADA

    def __post_init__(self) -> None:
        if self.monto < 0:
            raise ValueError(f"un importe no puede ser negativo: {self.monto}")
        if not self.moneda.isalpha() or len(self.moneda) != 3:
            raise ValueError(f"código de moneda inválido: {self.moneda!r}")

    @classmethod
    def cop(cls, monto: int) -> Dinero:
        return cls(monto, MONEDA_PREDETERMINADA)

    def _mismo_tipo(self, otro: Dinero) -> None:
        if self.moneda != otro.moneda:
            raise MonedasIncompatiblesError(f"{self.moneda} y {otro.moneda} no son comparables")

    def __add__(self, otro: Dinero) -> Dinero:
        self._mismo_tipo(otro)
        return Dinero(self.monto + otro.monto, self.moneda)

    def __sub__(self, otro: Dinero) -> Dinero:
        """Resta acotada en cero: un ahorro nunca es negativo."""
        self._mismo_tipo(otro)
        return Dinero(max(self.monto - otro.monto, 0), self.moneda)

    def escalar(self, factor: float, redondeo: int = REDONDEO_COP) -> Dinero:
        """Aplica un factor multiplicativo y redondea a la centena más cercana."""
        if factor < 0:
            raise ValueError(f"el factor no puede ser negativo: {factor}")
        bruto = self.monto * factor
        return Dinero(int(round(bruto / redondeo)) * redondeo, self.moneda)

    def __str__(self) -> str:
        return f"{self.monto:,}".replace(",", ".") + f" {self.moneda}"
