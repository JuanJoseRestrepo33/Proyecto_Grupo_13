"""Configuración del BFF, leída del entorno al arrancar.

Toda la configuración entra por variables de entorno, como en el núcleo; los nombres son los
acordados en `docs/devops.md` ("Nombres acordados entre componentes").
"""

from __future__ import annotations

import os
from collections.abc import Mapping
from dataclasses import dataclass

VARIABLE_NUCLEO = "NUCLEO_URL"
VARIABLE_LLAVE = "TOKENS_LLAVE_PUBLICA"
VARIABLE_LLAVE_ANTERIOR = "TOKENS_LLAVE_PUBLICA_ANTERIOR"
#: Cloud Run la define en todo contenedor; su presencia dice que no es un ambiente local.
VARIABLE_CLOUD_RUN = "K_SERVICE"

NUCLEO_LOCAL = "http://127.0.0.1:8000"


@dataclass(frozen=True)
class Configuracion:
    """`llaves_publicas` son las PEM con que se validan los tokens: la actual y, durante
    una rotación, la anterior. El BFF nunca recibe la privada: no puede emitir sesiones."""

    nucleo_url: str = NUCLEO_LOCAL
    llaves_publicas: tuple[str, ...] = ()
    en_la_nube: bool = False
    tiempo_espera_nucleo: float = 5.0

    @classmethod
    def desde_entorno(cls, entorno: Mapping[str, str] | None = None) -> Configuracion:
        entorno = os.environ if entorno is None else entorno
        llaves = tuple(
            entorno[v].strip()
            for v in (VARIABLE_LLAVE, VARIABLE_LLAVE_ANTERIOR)
            if entorno.get(v, "").strip()
        )
        return cls(
            nucleo_url=entorno.get(VARIABLE_NUCLEO, NUCLEO_LOCAL).rstrip("/"),
            llaves_publicas=llaves,
            en_la_nube=bool(entorno.get(VARIABLE_CLOUD_RUN)),
        )
