"""Sesión del cliente: el token de acceso que la abre y el de refresco que la renueva.

El token de acceso es corto y firmado: el BFF lo valida sin preguntarle al núcleo. Su
vigencia depende del canal, porque la web no renueva (al vencer, se vuelve a iniciar
sesión) y la app sí (con biometría, usando el token de refresco).

El token de refresco es un secreto opaco de un solo uso: renovar entrega uno nuevo e
invalida el anterior (rotación). El núcleo guarda solo su resumen, nunca el valor.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta

from app.compartido.errores import NoAutenticado

#: Vigencia del token de acceso por canal. La web no tiene renovación: su sesión dura más.
VIGENCIA_ACCESO = {"web": timedelta(minutes=60), "movil": timedelta(minutes=15)}
#: Vigencia del token de refresco de la app. Pasado este tiempo hay que usar la contraseña.
VIGENCIA_REFRESCO = timedelta(days=30)


class SesionNoVigenteError(NoAutenticado):
    codigo = "sesion_no_vigente"


@dataclass(frozen=True, slots=True)
class TokenEmitido:
    token: str
    expira_en: datetime


@dataclass(frozen=True, slots=True)
class ReclamosDeAcceso:
    id_cliente: str
    canal: str
    expira_en: datetime


@dataclass(frozen=True, slots=True)
class Refresco:
    """Lo que el núcleo guarda de un token de refresco: su resumen, nunca el valor."""

    resumen: str
    id_cliente: str
    canal: str
    expira_en: datetime

    def vigente(self, ahora: datetime) -> bool:
        return ahora < self.expira_en
