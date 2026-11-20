"""Identidad en PostgreSQL (ARQ-05, #136): las cuentas se comparten entre réplicas del núcleo.

Corren si hay una base: `IDENTIDAD_DATABASE_URL` o, en su defecto, `AUDITORIA_DATABASE_URL`.
Cada "réplica" es un `ServicioDeCuentas` propio sobre la misma base, con el mismo emisor de
tokens: lo que en *staging* son dos instancias de Cloud Run con la misma llave.
"""

from __future__ import annotations

import os
from datetime import UTC, datetime

import psycopg
import pytest

from app.compartido.eventos import BusEnProceso
from app.compartido.reloj import RelojFijo
from app.modulos.identidad.aplicacion.cuentas import ServicioDeCuentas
from app.modulos.identidad.dominio.errores import (
    CorreoYaRegistradoError,
    TokenRefrescoInvalidoError,
)
from app.modulos.identidad.dominio.modelo import (
    DatosDeIdentidad,
    EstadoIdentidad,
    EstadoVerificacion,
    MotivoRechazo,
    TipoDocumento,
    VerificacionIdentidad,
)
from app.modulos.identidad.infraestructura.codigos_simulados import CanalDeCodigosSimulado
from app.modulos.identidad.infraestructura.repositorio_postgres import (
    PRIMER_CONSECUTIVO_CUENTAS,
    RepositorioDeCuentasPostgres,
    RepositorioDeRefrescosPostgres,
    RepositorioDeRegistrosPostgres,
    RepositorioDeVerificacionesPostgres,
)
from app.modulos.identidad.infraestructura.tokens_es256 import EmisorEs256

from .test_cuentas import CLAVE, DATOS, AccesosEnMemoria

DSN = os.environ.get("IDENTIDAD_DATABASE_URL") or os.environ.get("AUDITORIA_DATABASE_URL")
INSTANTE = datetime(2026, 10, 26, 15, 0, tzinfo=UTC)

pytestmark = pytest.mark.skipif(
    not DSN, reason="Define IDENTIDAD_DATABASE_URL para probar contra PostgreSQL"
)


@pytest.fixture(autouse=True)
def limpio():
    with psycopg.connect(DSN, autocommit=True) as conexion:
        conexion.execute("DROP SCHEMA IF EXISTS identidad CASCADE")


@pytest.fixture
def emisor() -> EmisorEs256:
    return EmisorEs256.efimero()


def _replica(emisor: EmisorEs256, codigos: CanalDeCodigosSimulado | None = None):
    return ServicioDeCuentas(
        cuentas=RepositorioDeCuentasPostgres(DSN),
        registros=RepositorioDeRegistrosPostgres(DSN),
        refrescos=RepositorioDeRefrescosPostgres(DSN),
        codigos=codigos or CanalDeCodigosSimulado(),
        emisor=emisor,
        accesos=AccesosEnMemoria(),
        bus=BusEnProceso(),
        reloj=RelojFijo(INSTANTE),
        estado_identidad=lambda _: EstadoIdentidad.PENDIENTE_VERIFICACION,
    )


def test_quien_se_registra_en_una_replica_verifica_e_inicia_sesion_en_la_otra(emisor):
    codigos = CanalDeCodigosSimulado()
    replica_a = _replica(emisor, codigos)
    replica_b = _replica(emisor, codigos)

    registro, cuenta = replica_a.registrar(DATOS, "movil")
    replica_b.verificar_correo(
        registro.id_registro, codigos.ultimo_codigo_para("ana@ejemplo.co"), "movil"
    )
    sesion = replica_a.iniciar_sesion("ana@ejemplo.co", CLAVE, "web")

    assert sesion.cuenta.id_cliente == cuenta.id_cliente
    assert sesion.cuenta.correo_verificado is True
    assert cuenta.id_cliente.endswith(str(PRIMER_CONSECUTIVO_CUENTAS))


def test_el_correo_ya_registrado_en_otra_replica_se_rechaza(emisor):
    _replica(emisor).registrar(DATOS, "movil")

    with pytest.raises(CorreoYaRegistradoError):
        _replica(emisor).registrar(DATOS, "web")


def test_el_refresco_es_de_un_solo_uso_entre_replicas(emisor):
    replica_a = _replica(emisor)
    replica_b = _replica(emisor)
    replica_a.sembrar_cuenta_de_prueba("prueba@solventa.co", CLAVE)
    sesion = replica_a.iniciar_sesion("prueba@solventa.co", CLAVE, "movil")

    replica_b.renovar_sesion(sesion.refresco)

    with pytest.raises(TokenRefrescoInvalidoError):
        replica_a.renovar_sesion(sesion.refresco)


def test_sembrar_la_cuenta_de_prueba_dos_veces_deja_una_y_adopta_la_clave_rotada(emisor):
    primera = _replica(emisor).sembrar_cuenta_de_prueba("prueba@solventa.co", CLAVE)
    otra = _replica(emisor)
    segunda = otra.sembrar_cuenta_de_prueba("prueba@solventa.co", CLAVE)
    assert segunda == primera

    otra.sembrar_cuenta_de_prueba("prueba@solventa.co", "Rotada2026x")

    assert otra.iniciar_sesion("prueba@solventa.co", "Rotada2026x", "web").cuenta.id_cliente == (
        primera.id_cliente
    )


def test_la_verificacion_de_identidad_vuelve_igual_y_se_puede_quitar():
    repositorio = RepositorioDeVerificacionesPostgres(DSN)
    rechazada = VerificacionIdentidad(
        id_verificacion="VER-1",
        id_cliente="CLI-2026-000001",
        datos=DatosDeIdentidad("Ana María Rojas", TipoDocumento.CEDULA_CIUDADANIA, "1020304050"),
        canal="movil",
        solicitada_en=INSTANTE,
        actualizada_en=INSTANTE,
        estado=EstadoVerificacion.RECHAZADA,
        proveedor="kyc-simulado",
        referencia_proveedor="REF-1",
        motivo=MotivoRechazo.DATOS_NO_COINCIDEN,
        reintento_permitido=True,
    )
    repositorio.guardar(rechazada)

    assert RepositorioDeVerificacionesPostgres(DSN).ultima_del_cliente("CLI-2026-000001") == (
        rechazada
    )
    repositorio.quitar("VER-1")
    assert repositorio.ultima_del_cliente("CLI-2026-000001") is None
