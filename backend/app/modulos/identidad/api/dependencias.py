"""Composición del módulo de identidad.

El proveedor de KYC/AML es el simulado; `IDENTIDAD_KYC_CAIDO=1` lo deja fuera de servicio
para ensayar el camino de falla sin tocar código. El proveedor real entra aquí, detrás del
mismo puerto, cuando exista.

Con `IDENTIDAD_DATABASE_URL` las cuentas, los registros, los refrescos y las verificaciones
viven en PostgreSQL, en el esquema `identidad`, y todas las réplicas del núcleo ven lo mismo
(ARQ-05, #136). Sin ella, en memoria: válido con una sola instancia, y se pierden con cada
revisión.

Con `HUMO_CORREO` y `HUMO_CLAVE` se siembra al arrancar la cuenta de prueba de *staging*,
si no existe ya. Con `IDENTIDAD_OTP_FIJO`
todos los códigos de verificación del correo son ese, para las E2E del registro.

Los tokens se firman con `TOKENS_LLAVE_PRIVADA`. Sin ella, en local se genera una llave
efímera al arrancar; en Cloud Run (`K_SERVICE` definida) eso no sirve, porque ningún BFF la
conoce, así que `/salud` reporta los tokens caídos y la revisión no recibe tráfico.
"""

from __future__ import annotations

import logging
import os
from collections.abc import Mapping

from app.compartido.eventos import obtener_bus
from app.compartido.modulo import EstadoDeDependencia
from app.compartido.reloj import Reloj, RelojDelSistema
from app.compartido.unica import instancia_unica
from app.modulos import auditoria

from ..aplicacion.cuentas import ServicioDeCuentas
from ..aplicacion.servicio import ServicioDeVerificacionDeIdentidad
from ..dominio.modelo import DatosDeIdentidad
from ..infraestructura.auditoria_nucleo import RegistroDeAccesosEnAuditoria, RegistroEnAuditoria
from ..infraestructura.codigos_simulados import CanalDeCodigosSimulado
from ..infraestructura.cuentas_memoria import (
    RepositorioDeCuentasEnMemoria,
    RepositorioDeRefrescosEnMemoria,
    RepositorioDeRegistrosEnMemoria,
)
from ..infraestructura.proveedor_kyc_simulado import NOMBRE, ProveedorKYCSimulado
from ..infraestructura.repositorio_memoria import RepositorioEnMemoria
from ..infraestructura.repositorio_postgres import (
    RepositorioDeCuentasPostgres,
    RepositorioDeRefrescosPostgres,
    RepositorioDeRegistrosPostgres,
    RepositorioDeVerificacionesPostgres,
)
from ..infraestructura.tokens_es256 import EmisorEs256, LlaveInvalidaError

VARIABLE_PROVEEDOR_CAIDO = "IDENTIDAD_KYC_CAIDO"
VARIABLE_LLAVE_PRIVADA = "TOKENS_LLAVE_PRIVADA"
VARIABLE_LLAVE_PUBLICA = "TOKENS_LLAVE_PUBLICA"
VARIABLE_CLOUD_RUN = "K_SERVICE"
VARIABLE_OTP_FIJO = "IDENTIDAD_OTP_FIJO"
VARIABLE_DSN = "IDENTIDAD_DATABASE_URL"
#: Credenciales de la cuenta de prueba de *staging*, las mismas que usa el humo de `cd.yml`.
VARIABLE_CORREO_PRUEBA = "HUMO_CORREO"
VARIABLE_CLAVE_PRUEBA = "HUMO_CLAVE"

log = logging.getLogger(__name__)


def _proveedor_caido() -> bool:
    return os.environ.get(VARIABLE_PROVEEDOR_CAIDO, "") not in ("", "0", "false")


def crear_emisor(
    entorno: Mapping[str, str] | None = None,
) -> tuple[EmisorEs256, EstadoDeDependencia]:
    """El emisor de tokens y cómo lo reporta `/salud`.

    Nunca impide el arranque: con la llave mal configurada el núcleo sigue atendiendo lo
    que no exige sesión, y `/salud` responde 503 para que la plataforma no promueva la
    revisión.
    """
    entorno = os.environ if entorno is None else entorno
    privada = entorno.get(VARIABLE_LLAVE_PRIVADA, "").strip()
    if not privada:
        emisor = EmisorEs256.efimero()
        if entorno.get(VARIABLE_CLOUD_RUN):
            detalle = f"Falta {VARIABLE_LLAVE_PRIVADA}"
            return emisor, EstadoDeDependencia("tokens", "caido", detalle)
        detalle = f"Llave efímera de desarrollo; define {VARIABLE_LLAVE_PRIVADA}"
        return emisor, EstadoDeDependencia("tokens", "simulado", detalle)
    try:
        emisor = EmisorEs256.desde_pem(privada)
        publica = entorno.get(VARIABLE_LLAVE_PUBLICA, "").strip()
        if publica and not emisor.corresponde_a(publica):
            raise LlaveInvalidaError(f"{VARIABLE_LLAVE_PUBLICA} no es la de la llave privada")
    except LlaveInvalidaError as error:
        log.error("Llave de tokens inválida: %s", error)
        return EmisorEs256.efimero(), EstadoDeDependencia("tokens", "caido", str(error))
    return emisor, EstadoDeDependencia("tokens", "ok", f"ES256, kid {emisor.kid}")


def _dsn() -> str | None:
    return os.environ.get(VARIABLE_DSN) or None


def _preparar(repositorio: RepositorioDeCuentasPostgres) -> None:
    try:
        repositorio.preparar()
    except Exception:  # noqa: BLE001 - la base caída al arrancar no debe tumbar el núcleo
        log.exception("No se pudo preparar el esquema de identidad; se reintentará")


def _datos_de_identidad(id_cliente: str) -> DatosDeIdentidad:
    """Se resuelve al usarse: las cuentas también dependen del servicio de verificación."""
    return obtener_servicio_de_cuentas().datos_de_identidad(id_cliente)


@instancia_unica
def _componentes() -> tuple[ServicioDeVerificacionDeIdentidad, Reloj, ProveedorKYCSimulado]:
    reloj = RelojDelSistema()
    proveedor = ProveedorKYCSimulado(caido=_proveedor_caido())
    dsn = _dsn()
    servicio = ServicioDeVerificacionDeIdentidad(
        repositorio=RepositorioDeVerificacionesPostgres(dsn) if dsn else RepositorioEnMemoria(),
        proveedor=proveedor,
        auditoria=RegistroEnAuditoria(auditoria.obtener_servicio()),
        reloj=reloj,
        datos_de_identidad=_datos_de_identidad,
    )
    return servicio, reloj, proveedor


@instancia_unica
def _tokens() -> tuple[EmisorEs256, EstadoDeDependencia]:
    return crear_emisor()


@instancia_unica
def _cuentas() -> tuple[ServicioDeCuentas, CanalDeCodigosSimulado]:
    codigos = CanalDeCodigosSimulado(os.environ.get(VARIABLE_OTP_FIJO) or None)
    dsn = _dsn()
    if dsn:
        cuentas = RepositorioDeCuentasPostgres(dsn)
        _preparar(cuentas)
        registros = RepositorioDeRegistrosPostgres(dsn)
        refrescos = RepositorioDeRefrescosPostgres(dsn)
    else:
        cuentas = RepositorioDeCuentasEnMemoria()
        registros = RepositorioDeRegistrosEnMemoria()
        refrescos = RepositorioDeRefrescosEnMemoria()
    servicio = ServicioDeCuentas(
        cuentas=cuentas,
        registros=registros,
        refrescos=refrescos,
        codigos=codigos,
        emisor=obtener_emisor(),
        accesos=RegistroDeAccesosEnAuditoria(auditoria.obtener_servicio()),
        bus=obtener_bus(),
        reloj=obtener_reloj(),
        estado_identidad=obtener_servicio().estado_identidad,
    )
    correo = os.environ.get(VARIABLE_CORREO_PRUEBA, "").strip()
    clave = os.environ.get(VARIABLE_CLAVE_PRUEBA, "")
    if correo and clave:
        try:
            servicio.sembrar_cuenta_de_prueba(correo, clave)
            log.info("Cuenta de prueba sembrada")
        except Exception:  # noqa: BLE001 - sin base al arrancar, el núcleo sigue y /salud lo dice
            log.exception("No se pudo sembrar la cuenta de prueba")
    return servicio, codigos


def obtener_servicio() -> ServicioDeVerificacionDeIdentidad:
    return _componentes()[0]


def obtener_servicio_de_cuentas() -> ServicioDeCuentas:
    return _cuentas()[0]


def obtener_canal_de_codigos() -> CanalDeCodigosSimulado:
    return _cuentas()[1]


def obtener_emisor() -> EmisorEs256:
    return _tokens()[0]


def obtener_reloj() -> Reloj:
    return _componentes()[1]


def reiniciar() -> None:
    """Descarta la composición actual. Solo para pruebas."""
    _cuentas.cache_clear()
    _componentes.cache_clear()
    _tokens.cache_clear()


def _estado_repositorios() -> tuple[EstadoDeDependencia, ...]:
    """Las cuentas y las verificaciones comparten base: una sola consulta dice cómo está."""
    dsn = _dsn()
    if not dsn:
        return (
            EstadoDeDependencia(
                "repositorio_verificaciones", "simulado", f"En memoria; define {VARIABLE_DSN}"
            ),
            EstadoDeDependencia(
                "repositorio_cuentas", "simulado", "En memoria; se pierde con cada revisión"
            ),
        )
    try:
        RepositorioDeCuentasPostgres(dsn).verificar_conexion()
    except Exception as error:  # noqa: BLE001 - cualquier fallo de conexión es "caído"
        caido = type(error).__name__
        return (
            EstadoDeDependencia("repositorio_verificaciones", "caido", caido),
            EstadoDeDependencia("repositorio_cuentas", "caido", caido),
        )
    detalle = "PostgreSQL, esquema identidad"
    return (
        EstadoDeDependencia("repositorio_verificaciones", "ok", detalle),
        EstadoDeDependencia("repositorio_cuentas", "ok", detalle),
    )


def estado_dependencias() -> tuple[EstadoDeDependencia, ...]:
    proveedor = _componentes()[2]
    if proveedor.caido:
        estado_kyc = EstadoDeDependencia("proveedor_kyc", "caido", f"{NOMBRE} fuera de servicio")
    else:
        estado_kyc = EstadoDeDependencia(
            "proveedor_kyc", "simulado", f"{NOMBRE}; el proveedor real llega con ARQ-02"
        )
    return (
        estado_kyc,
        _tokens()[1],
        *_estado_repositorios(),
        EstadoDeDependencia("envio_codigos", "simulado", "Correo simulado; no envía correos"),
        EstadoDeDependencia("auditoria", "ok", "Módulo de auditoría del núcleo (ARQ-03)"),
    )
