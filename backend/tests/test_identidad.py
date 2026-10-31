"""Pruebas del hook de KYC/AML (MOB-F01, parte de backend).

Cubren el proveedor simulado, el servicio, la auditoría de cada intento y el contrato HTTP
que consume el BFF móvil. Los datos vienen de la cuenta: aquí los pone `Titular`.
"""

from __future__ import annotations

import threading
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
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
    ReintentoNoPermitidoError,
    ReintentoSinCambiosError,
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


class Titular:
    """Hace las veces de la cuenta: el servicio toma de aquí los datos para el proveedor."""

    def __init__(self) -> None:
        self.numero_documento = "1020304050"

    def __call__(self, _id_cliente: str) -> DatosDeIdentidad:
        return datos(self.numero_documento)


@dataclass
class ProveedorProgramado:
    """Responde lo que cada prueba le programe y recuerda cuántas veces lo llamaron."""

    resultado: ResultadoKYC | Exception
    llamadas: int = 0
    entro: threading.Event = field(default_factory=threading.Event)
    seguir: threading.Event = field(default_factory=threading.Event)

    def __post_init__(self) -> None:
        self.seguir.set()

    def verificar(self, _datos: DatosDeIdentidad) -> ResultadoKYC:
        self.llamadas += 1
        self.entro.set()
        assert self.seguir.wait(5)
        if isinstance(self.resultado, Exception):
            raise self.resultado
        return self.resultado

    def consultar(self, referencia_proveedor: str) -> ResultadoKYC:
        raise NotImplementedError


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
def titular() -> Titular:
    return Titular()


@pytest.fixture
def armar_servicio(reloj, almacen_auditoria, titular):
    def armar(proveedor) -> ServicioDeVerificacionDeIdentidad:
        return ServicioDeVerificacionDeIdentidad(
            repositorio=RepositorioEnMemoria(),
            proveedor=proveedor,
            auditoria=RegistroEnAuditoria(ServicioDeAuditoria(almacen_auditoria)),
            reloj=reloj,
            datos_de_identidad=titular,
        )

    return armar


@pytest.fixture
def servicio(armar_servicio, proveedor) -> ServicioDeVerificacionDeIdentidad:
    return armar_servicio(proveedor)


@pytest.fixture
def solicitar(servicio, titular):
    """Solicita la verificación de una cuenta con ese documento."""

    def pedir(numero_documento: str = "1020304050"):
        titular.numero_documento = numero_documento
        return servicio.solicitar(CLIENTE, canal="movil")

    return pedir


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


def test_una_verificacion_aprobada_deja_la_identidad_verificada(solicitar, servicio):
    verificacion = solicitar("1020304050")

    assert verificacion.estado is EstadoVerificacion.APROBADA
    assert verificacion.estado_identidad is EstadoIdentidad.VERIFICADA
    assert verificacion.solicitada_en == INSTANTE
    assert servicio.estado_identidad(CLIENTE) is EstadoIdentidad.VERIFICADA


def test_un_rechazo_trae_motivo_claro_y_permite_reintentar(solicitar, servicio):
    verificacion = solicitar("1020304057")

    assert verificacion.estado is EstadoVerificacion.RECHAZADA
    assert verificacion.motivo is MotivoRechazo.DATOS_NO_COINCIDEN
    assert verificacion.reintento_permitido is True
    assert servicio.estado_identidad(CLIENTE) is EstadoIdentidad.RECHAZADA
    # Reintentar es posible: el rechazo no deja la cuenta bloqueada.
    segunda = solicitar("1020304050")
    assert segunda.estado is EstadoVerificacion.APROBADA


def test_una_revision_manual_deja_la_cuenta_en_revision_y_bloquea_otra_solicitud(
    solicitar, servicio
):
    solicitar("1020304058")

    assert servicio.estado_identidad(CLIENTE) is EstadoIdentidad.EN_REVISION
    with pytest.raises(VerificacionEnCursoError):
        solicitar("1020304050")


def test_no_se_vuelve_a_verificar_una_identidad_verificada(solicitar, servicio):
    solicitar("1020304050")

    with pytest.raises(IdentidadYaVerificadaError):
        solicitar("1020304050")


def test_una_verificacion_en_proceso_se_resuelve_al_consultar(solicitar, servicio, reloj):
    verificacion = solicitar("1020304059")
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
    armar_servicio, almacen_auditoria
):
    proveedor = ProveedorKYCSimulado(caido=True)
    servicio = armar_servicio(proveedor)

    with pytest.raises(ProveedorKYCNoDisponibleError):
        servicio.solicitar(CLIENTE, canal="movil")

    assert servicio.estado_identidad(CLIENTE) is EstadoIdentidad.PENDIENTE_VERIFICACION
    registros = almacen_auditoria.todos()
    assert [r.evento.tipo for r in registros] == ["verificacion_identidad_proveedor_no_disponible"]
    assert registros[0].evento.resultado["proveedor"] == NOMBRE

    proveedor.caido = False
    assert servicio.solicitar(CLIENTE, canal="movil").estado is EstadoVerificacion.APROBADA


def test_un_fallo_inesperado_del_proveedor_no_deja_al_cliente_en_curso(armar_servicio):
    proveedor = ProveedorProgramado(RuntimeError("defecto del adaptador"))
    servicio = armar_servicio(proveedor)

    with pytest.raises(RuntimeError):
        servicio.solicitar(CLIENTE, canal="movil")

    assert servicio.estado_identidad(CLIENTE) is EstadoIdentidad.PENDIENTE_VERIFICACION
    with pytest.raises(RuntimeError):
        servicio.solicitar(CLIENTE, canal="movil")
    assert proveedor.llamadas == 2


# --- Dos solicitudes a la vez: la que llega mientras el proveedor responde no pasa -----------


def test_mientras_el_proveedor_responde_otra_solicitud_es_409_sin_llamarlo(armar_servicio):
    proveedor = ProveedorProgramado(ResultadoKYC(EstadoVerificacion.APROBADA, NOMBRE, "REF-1"))
    proveedor.seguir.clear()
    servicio = armar_servicio(proveedor)

    with ThreadPoolExecutor(max_workers=1) as hilos:
        primera = hilos.submit(servicio.solicitar, CLIENTE, canal="movil")
        assert proveedor.entro.wait(5)

        with pytest.raises(VerificacionEnCursoError):
            servicio.solicitar(CLIENTE, canal="movil")
        en_curso = servicio.consultar(CLIENTE)
        assert en_curso.estado is EstadoVerificacion.EN_PROCESO
        assert servicio.estado_identidad(CLIENTE) is EstadoIdentidad.PENDIENTE_VERIFICACION

        proveedor.seguir.set()
        resuelta = primera.result(timeout=5)

    assert proveedor.llamadas == 1
    assert resuelta.id_verificacion == en_curso.id_verificacion
    assert servicio.consultar(CLIENTE).estado is EstadoVerificacion.APROBADA


def test_de_varias_solicitudes_simultaneas_solo_una_llega_al_proveedor(armar_servicio):
    proveedor = ProveedorProgramado(ResultadoKYC(EstadoVerificacion.APROBADA, NOMBRE, "REF-1"))
    proveedor.seguir.clear()
    servicio = armar_servicio(proveedor)
    inicio = threading.Barrier(8)

    def pedir():
        inicio.wait(5)
        try:
            return servicio.solicitar(CLIENTE, canal="movil")
        except VerificacionEnCursoError as error:
            return error

    with ThreadPoolExecutor(max_workers=8) as hilos:
        intentos = [hilos.submit(pedir) for _ in range(8)]
        assert proveedor.entro.wait(5)
        pendientes = {*intentos}
        while len(pendientes) > 1:
            pendientes = {i for i in pendientes if not i.done()}
        proveedor.seguir.set()
        resultados = [i.result(timeout=5) for i in intentos]

    assert proveedor.llamadas == 1
    assert sum(isinstance(r, VerificacionEnCursoError) for r in resultados) == 7


# --- Reintentos: el rechazo decide si se puede volver al proveedor -----------------------


def _rechazo(motivo: MotivoRechazo, reintento: bool | None) -> ResultadoKYC:
    return ResultadoKYC(
        EstadoVerificacion.RECHAZADA, NOMBRE, "REF-1", motivo=motivo, reintento_permitido=reintento
    )


@pytest.mark.parametrize("reintento", [False, None])
def test_un_rechazo_definitivo_no_admite_otra_solicitud(armar_servicio, reintento):
    proveedor = ProveedorProgramado(_rechazo(MotivoRechazo.NO_SUPERADA, reintento))
    servicio = armar_servicio(proveedor)
    servicio.solicitar(CLIENTE, canal="movil")

    with pytest.raises(ReintentoNoPermitidoError):
        servicio.solicitar(CLIENTE, canal="movil")

    assert proveedor.llamadas == 1
    assert servicio.consultar(CLIENTE).estado is EstadoVerificacion.RECHAZADA


def test_datos_que_no_coinciden_no_se_reenvian_iguales_al_proveedor(armar_servicio):
    proveedor = ProveedorProgramado(_rechazo(MotivoRechazo.DATOS_NO_COINCIDEN, True))
    servicio = armar_servicio(proveedor)
    servicio.solicitar(CLIENTE, canal="movil")

    with pytest.raises(ReintentoSinCambiosError):
        servicio.solicitar(CLIENTE, canal="movil")

    assert proveedor.llamadas == 1
    assert servicio.consultar(CLIENTE).motivo is MotivoRechazo.DATOS_NO_COINCIDEN


def test_con_los_datos_corregidos_el_reintento_llega_al_proveedor(armar_servicio, titular, reloj):
    proveedor = ProveedorProgramado(_rechazo(MotivoRechazo.DATOS_NO_COINCIDEN, True))
    servicio = armar_servicio(proveedor)
    servicio.solicitar(CLIENTE, canal="movil")

    titular.numero_documento = "1020304051"
    reloj.avanzar(30)
    proveedor.resultado = ResultadoKYC(EstadoVerificacion.APROBADA, NOMBRE, "REF-2")
    servicio.solicitar(CLIENTE, canal="movil")

    assert proveedor.llamadas == 2
    assert servicio.estado_identidad(CLIENTE) is EstadoIdentidad.VERIFICADA


def test_otro_rechazo_reintentable_si_vuelve_al_proveedor_con_los_mismos_datos(armar_servicio):
    proveedor = ProveedorProgramado(_rechazo(MotivoRechazo.DOCUMENTO_NO_VALIDO, True))
    servicio = armar_servicio(proveedor)
    servicio.solicitar(CLIENTE, canal="movil")

    servicio.solicitar(CLIENTE, canal="movil")

    assert proveedor.llamadas == 2


# --- Criterio 9: todo intento queda en el log con resultado, proveedor, canal y tiempo ----


def test_cada_intento_queda_en_auditoria_sin_el_numero_de_documento(
    solicitar, servicio, almacen_auditoria
):
    solicitar("1020304057")

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
    solicitar, servicio, almacen_auditoria
):
    solicitar("1020304059")
    servicio.consultar(CLIENTE)

    assert [r.evento.tipo for r in almacen_auditoria.todos()] == [
        "verificacion_identidad_solicitada",
        "verificacion_identidad_resuelta",
    ]


# --- Contrato HTTP que consume el BFF móvil -------------------------------------------------


def test_solicitar_por_http_responde_202_con_la_forma_del_contrato(cliente, titular):
    titular.numero_documento = "1020304057"

    respuesta = cliente.post(f"{RUTA}/verificaciones")

    assert respuesta.status_code == 202
    cuerpo = respuesta.json()
    assert cuerpo["estado"] == "rechazada"
    assert cuerpo["motivo"] == "datos_no_coinciden"
    assert cuerpo["reintento_permitido"] is True
    assert cuerpo["id"].startswith("KYC-")
    assert cuerpo["actualizada_en"] == "2026-10-18T09:00:00Z"


def test_los_datos_salen_de_la_cuenta_aunque_llegue_un_cuerpo(cliente):
    respuesta = cliente.post(
        f"{RUTA}/verificaciones",
        json={
            "nombre_completo": "Otra Persona",
            "tipo_documento": "cedula_ciudadania",
            "numero_documento": "1020304057",
        },
    )

    assert respuesta.status_code == 202
    assert respuesta.json()["estado"] == "aprobada"


def test_repetir_la_solicitud_con_identidad_verificada_es_409_problem_json(cliente):
    cliente.post(f"{RUTA}/verificaciones")

    respuesta = cliente.post(f"{RUTA}/verificaciones")

    assert respuesta.status_code == 409
    assert respuesta.headers["content-type"].startswith("application/problem+json")
    assert respuesta.json()["title"] == "identidad_ya_verificada"


@pytest.mark.parametrize(
    ("documento", "reintento", "codigo"),
    [
        ("1020304057", True, "reintento_sin_cambios"),
        ("1020304050", False, "reintento_no_permitido"),
    ],
)
def test_un_reintento_que_no_aplica_es_409_con_su_codigo(
    armar_servicio, titular, documento, reintento, codigo
):
    motivo = MotivoRechazo.DATOS_NO_COINCIDEN if reintento else MotivoRechazo.NO_SUPERADA
    titular.numero_documento = documento
    servicio = armar_servicio(ProveedorProgramado(_rechazo(motivo, reintento)))
    app = crear_app()
    app.dependency_overrides[dependencias.obtener_servicio] = lambda: servicio
    cliente = TestClient(app)
    cliente.post(f"{RUTA}/verificaciones")

    respuesta = cliente.post(f"{RUTA}/verificaciones")

    assert respuesta.status_code == 409
    assert respuesta.json()["title"] == codigo


def test_la_ultima_verificacion_y_el_estado_de_identidad_por_http(cliente, titular):
    assert cliente.get(f"{RUTA}/verificaciones/ultima").status_code == 404
    assert cliente.get(f"{RUTA}/estado").json()["estado_identidad"] == "pendiente_verificacion"

    titular.numero_documento = "1020304059"
    cliente.post(f"{RUTA}/verificaciones", headers={"X-Canal": "movil"})

    ultima = cliente.get(f"{RUTA}/verificaciones/ultima").json()
    assert ultima["estado"] == "aprobada"
    assert cliente.get(f"{RUTA}/estado").json()["estado_identidad"] == "verificada"


def test_con_el_proveedor_caido_la_fachada_responde_503(cliente, proveedor):
    proveedor.caido = True

    respuesta = cliente.post(f"{RUTA}/verificaciones")

    assert respuesta.status_code == 503
    assert respuesta.json()["title"] == "proveedor_kyc_no_disponible"


def test_con_el_proveedor_caido_salud_lo_reporta(monkeypatch):
    monkeypatch.setenv(dependencias.VARIABLE_PROVEEDOR_CAIDO, "1")
    monkeypatch.delenv(auditoria_dependencias.VARIABLE_DSN, raising=False)
    dependencias.reiniciar()
    auditoria_dependencias.reiniciar()
    try:
        salud = TestClient(crear_app()).get("/salud")

        assert salud.status_code == 503
        assert (
            salud.json()["modulos"]["identidad"]["dependencias"]["proveedor_kyc"]["estado"]
            == "caido"
        )
    finally:
        dependencias.reiniciar()
        auditoria_dependencias.reiniciar()
