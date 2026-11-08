"""Proveedor de firma electrónica simulado (integraciones externas simuladas).

Devuelve un identificador de firma y el instante. La firma es sobre la huella de las
condiciones aceptadas: si alguien cambiara la prima o una cobertura, la huella ya no
coincidiría con la firmada. Con `FIRMA_MODO=caida` el proveedor no responde y el núcleo
responde 503 sin emitir; firmar otra vez es seguro.
"""

from __future__ import annotations

import hashlib
import json
import uuid
from collections.abc import Mapping

from app.compartido.errores import DependenciaNoDisponible
from app.compartido.reloj import Reloj

from ..dominio.modelo import Firma, Suscripcion

PROVEEDOR = "firma-simulada"


def huella_de_condiciones(suscripcion: Suscripcion) -> str:
    condiciones = suscripcion.condiciones
    canonico = json.dumps(
        {
            "id_suscripcion": suscripcion.id_suscripcion,
            "id_cliente": suscripcion.id_cliente,
            "producto": condiciones.producto,
            "prima_mensual": condiciones.prima_mensual.monto,
            "moneda": condiciones.prima_mensual.moneda,
            "valor_asegurado": condiciones.valor_asegurado,
            "deducible": condiciones.deducible,
            "coberturas": [[c.nombre, c.limite] for c in condiciones.coberturas],
        },
        sort_keys=True,
        ensure_ascii=False,
    )
    return hashlib.sha256(canonico.encode()).hexdigest()


class FirmaSimulada:
    def __init__(self, reloj: Reloj, caida: bool = False) -> None:
        self._reloj = reloj
        self.caida = caida
        self.firmadas: list[tuple[str, str]] = []

    @classmethod
    def desde_entorno(cls, reloj: Reloj, entorno: Mapping[str, str]) -> FirmaSimulada:
        modo = entorno.get("FIRMA_MODO", "sana")
        if modo not in ("sana", "caida"):
            raise RuntimeError(f"FIRMA_MODO no admite {modo!r}: usa sana o caida")
        return cls(reloj, caida=modo == "caida")

    def firmar(self, suscripcion: Suscripcion) -> Firma:
        if self.caida:
            raise DependenciaNoDisponible("El proveedor de firma no respondió", proveedor="firma")
        self.firmadas.append((suscripcion.id_suscripcion, huella_de_condiciones(suscripcion)))
        return Firma(f"FIR-{uuid.uuid4()}", PROVEEDOR, self._reloj.ahora())
