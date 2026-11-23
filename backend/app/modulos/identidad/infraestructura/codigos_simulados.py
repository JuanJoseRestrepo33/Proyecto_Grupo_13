"""Envío simulado del código de verificación del correo.

No manda correos: escribe en el log a quién se envió, con el correo enmascarado. Con
`codigo_fijo` (variable `IDENTIDAD_OTP_FIJO`) todos los códigos son ese, para que las pruebas
de extremo a extremo de *staging* puedan completar un registro sin leer un buzón. En
producción no se define y cada código es aleatorio.
"""

from __future__ import annotations

import logging
import secrets

log = logging.getLogger(__name__)

NOMBRE = "Correo simulado"


def enmascarar(correo: str) -> str:
    usuario, _, dominio = correo.partition("@")
    return f"{usuario[:1]}***@{dominio}"


class CanalDeCodigosSimulado:
    def __init__(self, codigo_fijo: str | None = None) -> None:
        if codigo_fijo is not None and not (codigo_fijo.isdigit() and len(codigo_fijo) == 6):
            raise ValueError("el código fijo debe tener 6 dígitos")
        self.codigo_fijo = codigo_fijo
        self._ultimos: dict[str, str] = {}

    def nuevo_codigo(self) -> str:
        return self.codigo_fijo or f"{secrets.randbelow(1_000_000):06d}"

    def enviar(self, correo: str, codigo: str) -> None:
        self._ultimos[correo] = codigo
        log.info("%s: código de verificación enviado a %s", NOMBRE, enmascarar(correo))

    def ultimo_codigo_para(self, correo: str) -> str | None:
        """Solo para pruebas: el último código enviado a ese correo."""
        return self._ultimos.get(correo)
