"""Pruebas del hook de KYC/AML (MOB-F01, parte de backend).

Cubren el proveedor simulado, el servicio, la auditoría de cada intento y el contrato HTTP
que consume el BFF móvil.
"""

from __future__ import annotations

from datetime import UTC, datetime

import pytest
from fastapi.testclient import TestClient

from app.compartido.errores import RecursoNoEncontrado
from app.compartido.reloj import RelojFijo
from app.main import crear_app
from app.modulos.auditoria.api import dependencias as auditoria_dependencias
from app.modulos.auditoria.aplicacion.servicio import ServicioDeAuditoria
from app.modulos.auditoria.infraestructura.almacen_memoria import AlmacenEnMemoria
from app.modulos.identidad.api import dependencias
from app.modulos.identidad.aplicacion.servicio import ServicioDeVerificacionDeIdentidad
from app.modulos.identidad.dominio.errores import (
    IdentidadYaVerificadaError,
    ProveedorKYCNoDisponibleError,
    VerificacionEnCursoError,
)
from app.modulos.identidad.dominio.modelo import (
    DatosDeIdentidad,
    EstadoIdentidad,
    EstadoVerificacion,
    MotivoRechazo,
    ResultadoKYC,
    TipoDocumento,
)
from app.modulos.identidad.infraestructura.auditoria_nucleo import RegistroEnAuditoria
from app.modulos.identidad.infraestructura.proveedor_kyc_simulado import (
    NOMBRE,
    ProveedorKYCSimulado,
)
from app.modulos.identidad.infraestructura.repositorio_memoria import RepositorioEnMemoria

INSTANTE = datetime(2026, 10, 18, 9, 0, 0, tzinfo=UTC)
CLIENTE = "CLI-0001"
RUTA = f"/v1/identidad/clientes/{CLIENTE}"


def datos(numero_documento: str = "1020304050") -> DatosDeIdentidad:
    return DatosDeIdentidad("Ana María Rojas", TipoDocumento.CEDULA_CIUDADANIA, numero_documento)


def solicitud(numero_documento: str = "1020304050") -> dict[str, str]:
    return {
        "nombre_completo": "Ana María Rojas",
        "tipo_documento": "cedula_ciudadania",
        "numero_documento": numero_documento,
    }


@pytest.fixture
def reloj() -> RelojFijo:
    return RelojFijo(INSTANTE)


@pytest.fixture
def proveedor() -> ProveedorKYCSimulado:
    return ProveedorKYCSimulado()


@pytest.fixture
def almacen_auditoria() -> AlmacenEnMemoria:
    return AlmacenEnMemoria()


@pytest.fixture
def servicio(reloj, proveedor, almacen_auditoria) -> ServicioDeVerificacionDeIdentidad:
    return ServicioDeVerificacionDeIdentidad(
        repositorio=RepositorioEnMemoria(),
        proveedor=proveedor,
        auditoria=RegistroEnAuditoria(ServicioDeAuditoria(almacen_auditoria)),
        reloj=reloj,
    )


@pytest.fixture
def cliente(servicio) -> TestClient:
    app = crear_app()
    app.dependency_overrides[dependencias.obtener_servicio] = lambda: servicio
    return TestClient(app)


# --- El proveedor simulado es determinista y sigue la regla del mock del BFF móvil --------


@pytest.mark.parametrize(
    ("documento", "estado", "motivo"),
    [
        ("1020304050", EstadoVerificacion.APROBADA, None),
        ("1020304057", EstadoVerificacion.RECHAZADA, MotivoRechazo.DATOS_NO_COINCIDEN),
        ("1020304058", EstadoVerificacion.EN_REVISION, None),
        ("1020304059", EstadoVerificacion.EN_PROCESO, None),
    ],
)
def test_el_proveedor_simulado_decide_por_el_ultimo_digito(proveedor, documento, estado, motivo):
    resultado = proveedor.verificar(datos(documento))

    assert resultado.estado is estado
    assert resultado.motivo is motivo
    assert resultado.proveedor == NOMBRE
    assert resultado.referencia_proveedor


def test_un_rechazo_sin_motivo_no_es_valido():
    with pytest.raises(ValueError, match="motivo"):
        ResultadoKYC(EstadoVerificacion.RECHAZADA, NOMBRE)


def test_el_proveedor_caido_falla_con_el_error_tipado_del_dominio():
    caido = ProveedorKYCSimulado(caido=True)
    with pytest.raises(ProveedorKYCNoDisponibleError) as error:
        caido.verificar(datos())
    assert error.value.detalles["proveedor"] == NOMBRE


# --- Solicitar: cada resultado deja un siguiente paso claro --------------------------------


def test_una_verificacion_aprobada_deja_la_identidad_verificada(servicio):
    verificacion = servicio.solicitar(CLIENTE, datos("1020304050"), canal="movil")

    assert verificacion.estado is EstadoVerificacion.APROBADA
    assert verificacion.estado_identidad is EstadoIdentidad.VERIFICADA
    assert verificacion.solicitada_en == INSTANTE
    assert servicio.estado_identidad(CLIENTE) is EstadoIdentidad.VERIFICADA


def test_un_rechazo_trae_motivo_claro_y_permite_reintentar(servicio):
    verificacion = servicio.solicitar(CLIENTE, datos("1020304057"), canal="movil")

    assert verificacion.estado is EstadoVerificacion.RECHAZADA
    assert verificacion.motivo is MotivoRechazo.DATOS_NO_COINCIDEN
    assert verificacion.reintento_permitido is True
    assert servicio.estado_identidad(CLIENTE) is EstadoIdentidad.RECHAZADA
    # Reintentar es posible: el rechazo no deja la cuenta bloqueada.
    segunda = servicio.solicitar(CLIENTE, datos("1020304050"), canal="movil")
    assert segunda.estado is EstadoVerificacion.APROBADA


def test_una_revision_manual_deja_la_cuenta_en_revision_y_bloquea_otra_solicitud(servicio):
    servicio.solicitar(CLIENTE, datos("1020304058"), canal="movil")

    assert servicio.estado_identidad(CLIENTE) is EstadoIdentidad.EN_REVISION
    with pytest.raises(VerificacionEnCursoError):
        servicio.solicitar(CLIENTE, datos("1020304050"), canal="movil")


def test_no_se_vuelve_a_verificar_una_identidad_verificada(servicio):
    servicio.solicitar(CLIENTE, datos("1020304050"), canal="movil")

    with pytest.raises(IdentidadYaVerificadaError):
        servicio.solicitar(CLIENTE, datos("1020304050"), canal="movil")


def test_una_verificacion_en_proceso_se_resuelve_al_consultar(servicio, reloj):
    verificacion = servicio.solicitar(CLIENTE, datos("1020304059"), canal="movil")
    assert verificacion.estado is EstadoVerificacion.EN_PROCESO
    assert servicio.estado_identidad(CLIENTE) is EstadoIdentidad.VERIFICADA  # ya consultó

    reloj.avanzar(30)
    resuelta = servicio.consultar(CLIENTE)

    assert resuelta.estado is EstadoVerificacion.APROBADA
    assert resuelta.id_verificacion == verificacion.id_verificacion


def test_un_cliente_sin_verificacion_esta_pendiente_y_no_tiene_ultima(servicio):
    assert servicio.estado_identidad(CLIENTE) is EstadoIdentidad.PENDIENTE_VERIFICACION
    with pytest.raises(RecursoNoEncontrado):
        servicio.consultar(CLIENTE)


# --- Proveedor caído: error tipado, intento auditado, el cliente puede reintentar ---------


def test_con_el_proveedor_caido_el_intento_se_audita_y_se_puede_reintentar(
    reloj, almacen_auditoria
):
    proveedor = ProveedorKYCSimulado(caido=True)
    servicio = ServicioDeVerificacionDeIdentidad(
        RepositorioEnMemoria(),
        proveedor,
        RegistroEnAuditoria(ServicioDeAuditoria(almacen_auditoria)),
        reloj,
    )

    with pytest.raises(ProveedorKYCNoDisponibleError):
        servicio.solicitar(CLIENTE, datos(), canal="movil")

    assert servicio.estado_identidad(CLIENTE) is EstadoIdentidad.PENDIENTE_VERIFICACION
    registros = almacen_auditoria.todos()
    assert [r.evento.tipo for r in registros] == ["verificacion_identidad_proveedor_no_disponible"]
    assert registros[0].evento.resultado["proveedor"] == NOMBRE

    proveedor.caido = False
    assert servicio.solicitar(CLIENTE, datos(), canal="movil").estado is EstadoVerificacion.APROBADA


# --- Criterio 9: todo intento queda en el log con resultado, proveedor, canal y tiempo ----


def test_cada_intento_queda_en_auditoria_sin_el_numero_de_documento(servicio, almacen_auditoria):
    servicio.solicitar(CLIENTE, datos("1020304057"), canal="movil")

    (registro,) = almacen_auditoria.todos()
    evento = registro.evento
    assert evento.tipo == "verificacion_identidad_solicitada"
    assert evento.actor == CLIENTE
    assert evento.canal == "movil"
    assert evento.ocurrido_en == INSTANTE
    assert evento.resultado["estado"] == "rechazada"
    assert evento.resultado["motivo"] == "datos_no_coinciden"
    assert evento.resultado["proveedor"] == NOMBRE
    assert "1020304057" not in str(evento.variables_entrada)
    assert "1020304057" not in str(evento.resultado)


def test_la_resolucion_de_una_verificacion_en_proceso_tambien_se_audita(
    servicio, almacen_auditoria
):
    servicio.solicitar(CLIENTE, datos("1020304059"), canal="movil")
    servicio.consultar(CLIENTE)

    assert [r.evento.tipo for r in almacen_auditoria.todos()] == [
        "verificacion_identidad_solicitada",
        "verificacion_identidad_resuelta",
    ]


# --- Contrato HTTP que consume el BFF móvil -------------------------------------------------


def test_solicitar_por_http_responde_202_con_la_forma_del_contrato(cliente):
    respuesta = cliente.post(f"{RUTA}/verificaciones", json=solicitud("1020304057"))

    assert respuesta.status_code == 202
    cuerpo = respuesta.json()
    assert cuerpo["estado"] == "rechazada"
    assert cuerpo["motivo"] == "datos_no_coinciden"
    assert cuerpo["reintento_permitido"] is True
    assert cuerpo["id"].startswith("KYC-")
    assert cuerpo["actualizada_en"] == "2026-10-18T09:00:00Z"


def test_repetir_la_solicitud_con_identidad_verificada_es_409_problem_json(cliente):
    cliente.post(f"{RUTA}/verificaciones", json=solicitud())

    respuesta = cliente.post(f"{RUTA}/verificaciones", json=solicitud())

    assert respuesta.status_code == 409
    assert respuesta.headers["content-type"].startswith("application/problem+json")
    assert respuesta.json()["title"] == "identidad_ya_verificada"


def test_la_ultima_verificacion_y_el_estado_de_identidad_por_http(cliente):
    assert cliente.get(f"{RUTA}/verificaciones/ultima").status_code == 404
    assert cliente.get(f"{RUTA}/estado").json()["estado_identidad"] == "pendiente_verificacion"

    cliente.post(
        f"{RUTA}/verificaciones", json=solicitud("1020304059"), headers={"X-Canal": "movil"}
    )

    ultima = cliente.get(f"{RUTA}/verificaciones/ultima").json()
    assert ultima["estado"] == "aprobada"
    assert cliente.get(f"{RUTA}/estado").json()["estado_identidad"] == "verificada"


def test_un_documento_con_formato_invalido_es_422(cliente):
    respuesta = cliente.post(f"{RUTA}/verificaciones", json=solicitud("12"))
    assert respuesta.status_code == 422


def test_con_el_proveedor_caido_la_fachada_responde_503(monkeypatch):
    monkeypatch.setenv(dependencias.VARIABLE_PROVEEDOR_CAIDO, "1")
    monkeypatch.delenv(auditoria_dependencias.VARIABLE_DSN, raising=False)
    dependencias.reiniciar()
    auditoria_dependencias.reiniciar()
    try:
        cliente = TestClient(crear_app())

        respuesta = cliente.post(f"{RUTA}/verificaciones", json=solicitud())
        salud = cliente.get("/salud")

        assert respuesta.status_code == 503
        assert respuesta.json()["title"] == "proveedor_kyc_no_disponible"
        assert salud.status_code == 503
        assert (
            salud.json()["modulos"]["identidad"]["dependencias"]["proveedor_kyc"]["estado"]
            == "caido"
        )
    finally:
        dependencias.reiniciar()
        auditoria_dependencias.reiniciar()
