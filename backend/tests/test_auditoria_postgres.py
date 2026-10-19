"""Pruebas de ARQ-03 contra PostgreSQL: las tres capas de protección y la concurrencia.

Corren solo si `AUDITORIA_DATABASE_URL` apunta a una base con un usuario administrador
(en el CI, el servicio `postgres` del job `backend`). Cada prueba parte de un esquema
limpio para que la alteración deliberada de una no contamine a las demás.
"""

from __future__ import annotations

import os
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime
from decimal import Decimal

import psycopg
import pytest

from app.modulos.auditoria import EventoAuditable, ServicioDeAuditoria
from app.modulos.auditoria.infraestructura import esquema
from app.modulos.auditoria.infraestructura.almacen_postgres import AlmacenPostgres

DSN = os.environ.get("AUDITORIA_DATABASE_URL")

pytestmark = pytest.mark.skipif(
    not DSN, reason="Define AUDITORIA_DATABASE_URL para probar contra PostgreSQL"
)

INSTANTE = datetime(2026, 10, 17, 15, 0, 0, tzinfo=UTC)


def decision(n: int = 1, **cambios: object) -> EventoAuditable:
    base = dict(
        tipo="decision_cotizacion",
        actor="CLI-0001",
        canal="api-v1",
        ocurrido_en=INSTANTE,
        version_regla="rating-v3.2",
        variables_entrada={"edad": 34, "ciudad": "Bogotá", "factores": {"of": -0.1}},
        resultado={"prima_mensual": 133200 + n, "monto": Decimal("133200.50")},
        referencias={"id_cotizacion": f"QT-{n:04d}"},
    )
    base.update(cambios)
    return EventoAuditable(**base)  # type: ignore[arg-type]


@pytest.fixture
def almacen() -> AlmacenPostgres:
    with psycopg.connect(DSN, autocommit=True) as conexion:
        conexion.execute("DROP SCHEMA IF EXISTS auditoria CASCADE")
    almacen = AlmacenPostgres(DSN)
    almacen.preparar()
    return almacen


@pytest.fixture
def servicio(almacen) -> ServicioDeAuditoria:
    return ServicioDeAuditoria(almacen)


def test_el_esquema_es_idempotente(almacen):
    almacen.preparar()
    almacen.preparar()


def test_un_registro_va_y_vuelve_igual(servicio):
    id_decision = servicio.registrar(decision())

    registro = servicio.reconstruir(id_decision)

    assert registro.secuencia == 1
    assert registro.evento.ocurrido_en == INSTANTE
    assert registro.evento.variables_entrada == {
        "edad": 34,
        "ciudad": "Bogotá",
        "factores": {"of": -0.1},
    }
    # Decimal se guarda como texto para que lo hasheado sea exactamente lo guardado.
    assert registro.evento.resultado == {"prima_mensual": 133201, "monto": "133200.50"}
    assert servicio.verificar_integridad().conforme


# --- Criterio 2: la base rechaza UPDATE, DELETE y TRUNCATE ---------------------------------


@pytest.mark.parametrize(
    "sentencia",
    [
        "UPDATE auditoria.registros SET actor = 'ALTERADO'",
        "DELETE FROM auditoria.registros",
        "TRUNCATE auditoria.registros",
    ],
)
def test_el_rol_de_aplicacion_no_tiene_permiso_de_modificar(servicio, sentencia):
    servicio.registrar(decision())

    with psycopg.connect(DSN) as conexion:
        conexion.execute(f"SET ROLE {esquema.ROL_APLICACION}")
        with pytest.raises(psycopg.errors.InsufficientPrivilege):
            conexion.execute(sentencia)


@pytest.mark.parametrize(
    "sentencia",
    [
        "UPDATE auditoria.registros SET actor = 'ALTERADO'",
        "DELETE FROM auditoria.registros",
        "TRUNCATE auditoria.registros",
    ],
)
def test_los_triggers_bloquean_incluso_al_administrador(servicio, sentencia):
    servicio.registrar(decision())

    with (
        psycopg.connect(DSN) as conexion,
        pytest.raises(psycopg.errors.IntegrityConstraintViolation),
    ):
        conexion.execute(sentencia)


def test_el_rol_de_aplicacion_si_puede_insertar_y_leer(almacen):
    with psycopg.connect(DSN) as conexion:
        conexion.execute(f"SET ROLE {esquema.ROL_APLICACION}")
        conexion.execute("SELECT count(*) FROM auditoria.registros").fetchone()
        # INSERT directo con hash arbitrario: la base no calcula el hash, lo hace el módulo.
        conexion.execute(
            esquema.INSERTAR,
            {
                "secuencia": 1,
                "id_decision": "DEC-manual",
                "tipo": "prueba",
                "actor": "rol",
                "canal": "sql",
                "ocurrido_en": INSTANTE,
                "version_regla": None,
                "variables_entrada": psycopg.types.json.Jsonb({}),
                "resultado": psycopg.types.json.Jsonb({}),
                "referencias": psycopg.types.json.Jsonb({}),
                "version_texto_legal": None,
                "hash_anterior": "0" * 64,
                "hash": "f" * 64,
            },
        )


# --- Criterio 4: un administrador que salta las dos capas deja huella -----------------------


def test_una_alteracion_directa_se_senala_exactamente(servicio):
    for n in range(1, 6):
        servicio.registrar(decision(n))

    with psycopg.connect(DSN, autocommit=True) as conexion:
        # Un administrador desactivando los triggers: la tercera capa es la que queda.
        conexion.execute("SET session_replication_role = replica")
        conexion.execute(
            "UPDATE auditoria.registros SET resultado = %s WHERE secuencia = 3",
            (psycopg.types.json.Jsonb({"prima_mensual": 1}),),
        )

    informe = servicio.verificar_integridad()

    assert informe.total == 5
    assert [(e.secuencia, e.motivo) for e in informe.eslabones_rotos] == [(3, "hash_no_coincide")]


# --- Criterio 5: escritores concurrentes contra la base real ------------------------------


def test_veinte_escrituras_concurrentes_en_postgres_mantienen_la_cadena(servicio):
    with ThreadPoolExecutor(max_workers=20) as hilos:
        list(hilos.map(lambda n: servicio.registrar(decision(n)), range(20)))

    informe = servicio.verificar_integridad()
    assert informe.total == 20
    assert informe.conforme
