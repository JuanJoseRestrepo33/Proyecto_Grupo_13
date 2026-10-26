"""Pruebas del reporte de siniestros (WEB-F05): validación, vigencia e idempotencia."""

from __future__ import annotations

import json
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from datetime import UTC, date, datetime, timedelta

import pytest
from fastapi.testclient import TestClient

from app.compartido.reloj import RelojFijo
from app.main import crear_app
from app.modulos.polizas.api import dependencias as polizas_dependencias
from app.modulos.siniestros.api import dependencias
from app.modulos.siniestros.aplicacion.servicio import ServicioDeSiniestros
from app.modulos.siniestros.dominio.errores import (
    ClaveIdempotenciaEnUsoError,
    EventoFueraDeVigenciaError,
    FechaFuturaError,
    PolizaNoEncontradaError,
    SiniestroNoEncontradoError,
)
from app.modulos.siniestros.dominio.modelo import (
    DatosDeReporte,
    EstadoSiniestro,
    SiguientePaso,
    TipoEvento,
)
from app.modulos.siniestros.dominio.puertos import VigenciaDePoliza
from app.modulos.siniestros.infraestructura.repositorio_memoria import RepositorioEnMemoria

INSTANTE = datetime(2026, 10, 26, 15, 0, tzinfo=UTC)
HOY = INSTANTE.date()
CLIENTE = "CLI-2026-000318"
VIGENTE = VigenciaDePoliza(
    "POL-VIGENTE", "auto", HOY - timedelta(days=30), HOY + timedelta(days=335)
)
VENCIDA = VigenciaDePoliza(
    "POL-VENCIDA", "hogar", HOY - timedelta(days=395), HOY - timedelta(days=30)
)

DATOS = DatosDeReporte(
    numero_poliza="POL-VIGENTE",
    tipo_evento=TipoEvento.ACCIDENTE_VEHICULO,
    ocurrido_el=HOY - timedelta(days=2),
    descripcion="Choque leve en la calle 80 al frenar en un semáforo.",
    monto_estimado=1_500_000,
)


class PolizasFalsas:
    def __init__(self, *polizas: VigenciaDePoliza, de: str = CLIENTE) -> None:
        self._polizas = {p.numero: p for p in polizas}
        self._de = de

    def poliza_del_cliente(self, numero: str, id_cliente: str) -> VigenciaDePoliza | None:
        return self._polizas.get(numero) if id_cliente == self._de else None


@pytest.fixture
def reloj() -> RelojFijo:
    return RelojFijo(INSTANTE)


@pytest.fixture
def servicio(reloj) -> ServicioDeSiniestros:
    return ServicioDeSiniestros(
        repositorio=RepositorioEnMemoria(), polizas=PolizasFalsas(VIGENTE, VENCIDA), reloj=reloj
    )


# -- reporte ----------------------------------------------------------------------------


def test_reportar_deja_un_siniestro_reportado_con_referencia(servicio):
    """Criterio 1: queda en estado reportado, con referencia y siguiente paso."""
    siniestro, nuevo = servicio.reportar(CLIENTE, DATOS, "web", "clave-0001")

    assert nuevo is True
    assert siniestro.referencia == "SIN-2026-000001"
    assert siniestro.estado is EstadoSiniestro.REPORTADO
    assert siniestro.siguiente_paso is SiguientePaso.ADJUNTAR_EVIDENCIA
    assert siniestro.canal == "web"
    assert siniestro.reportado_en == INSTANTE


def test_el_mismo_siniestro_se_ve_desde_los_dos_canales(servicio):
    siniestro, _ = servicio.reportar(CLIENTE, DATOS, "movil", "clave-0001")

    assert servicio.del_cliente(CLIENTE) == (siniestro,)
    assert servicio.obtener(CLIENTE, siniestro.id_siniestro) == siniestro


def test_una_fecha_futura_se_rechaza(servicio):
    """Criterio 2."""
    with pytest.raises(FechaFuturaError):
        servicio.reportar(CLIENTE, replace(DATOS, ocurrido_el=HOY + timedelta(days=1)), "web")


def test_un_evento_de_hoy_se_acepta(servicio):
    siniestro, _ = servicio.reportar(CLIENTE, replace(DATOS, ocurrido_el=HOY), "web")

    assert siniestro.datos.ocurrido_el == HOY


def test_una_poliza_ajena_o_inexistente_es_404(reloj):
    servicio = ServicioDeSiniestros(
        RepositorioEnMemoria(), PolizasFalsas(VIGENTE, de="CLI-OTRO"), reloj
    )

    with pytest.raises(PolizaNoEncontradaError):
        servicio.reportar(CLIENTE, DATOS, "web")
    with pytest.raises(PolizaNoEncontradaError):
        servicio.reportar("CLI-OTRO", replace(DATOS, numero_poliza="POL-NADA"), "web")


def test_una_poliza_vencida_admite_un_evento_ocurrido_en_su_vigencia(servicio):
    """Criterio 3, primera mitad."""
    dentro = replace(DATOS, numero_poliza="POL-VENCIDA", ocurrido_el=VENCIDA.fin)

    siniestro, _ = servicio.reportar(CLIENTE, dentro, "web")

    assert siniestro.datos.numero_poliza == "POL-VENCIDA"


@pytest.mark.parametrize(
    "ocurrido_el", [VENCIDA.fin + timedelta(days=1), VENCIDA.inicio - timedelta(days=1)]
)
def test_un_evento_fuera_de_vigencia_no_procede_y_no_deja_registro(servicio, ocurrido_el):
    """Criterio 3, segunda mitad."""
    fuera = replace(DATOS, numero_poliza="POL-VENCIDA", ocurrido_el=ocurrido_el)

    with pytest.raises(EventoFueraDeVigenciaError) as error:
        servicio.reportar(CLIENTE, fuera, "web")

    assert error.value.detalles["vigente_hasta"] == VENCIDA.fin.isoformat()
    assert servicio.del_cliente(CLIENTE) == ()


# -- idempotencia (criterio 4) ----------------------------------------------------------


def test_la_misma_clave_devuelve_la_misma_referencia(servicio):
    primero, _ = servicio.reportar(CLIENTE, DATOS, "web", "clave-0001")
    segundo, nuevo = servicio.reportar(CLIENTE, DATOS, "web", "clave-0001")

    assert nuevo is False
    assert segundo.referencia == primero.referencia
    assert len(servicio.del_cliente(CLIENTE)) == 1


def test_la_misma_clave_con_otros_datos_es_409(servicio):
    servicio.reportar(CLIENTE, DATOS, "web", "clave-0001")

    with pytest.raises(ClaveIdempotenciaEnUsoError):
        servicio.reportar(
            CLIENTE, replace(DATOS, descripcion="Otra cosa distinta."), "web", "clave-0001"
        )


def test_la_misma_clave_de_otro_cliente_es_otro_siniestro(reloj):
    servicio = ServicioDeSiniestros(RepositorioEnMemoria(), PolizasFalsasParaTodos(VIGENTE), reloj)

    uno, _ = servicio.reportar(CLIENTE, DATOS, "web", "clave-0001")
    otro, _ = servicio.reportar("CLI-OTRO", DATOS, "web", "clave-0001")

    assert uno.referencia != otro.referencia


def test_sin_clave_cada_reporte_es_nuevo(servicio):
    servicio.reportar(CLIENTE, DATOS, "web")
    servicio.reportar(CLIENTE, DATOS, "web")

    assert len(servicio.del_cliente(CLIENTE)) == 2


def test_un_doble_envio_simultaneo_deja_un_solo_siniestro(servicio):
    with ThreadPoolExecutor(max_workers=8) as hilos:
        referencias = set(
            hilos.map(
                lambda _: servicio.reportar(CLIENTE, DATOS, "web", "clave-doble")[0].referencia,
                range(16),
            )
        )

    assert len(referencias) == 1
    assert len(servicio.del_cliente(CLIENTE)) == 1


def test_un_siniestro_ajeno_o_inexistente_es_404(servicio):
    siniestro, _ = servicio.reportar(CLIENTE, DATOS, "web")

    with pytest.raises(SiniestroNoEncontradoError):
        servicio.obtener("CLI-OTRO", siniestro.id_siniestro)
    with pytest.raises(SiniestroNoEncontradoError):
        servicio.obtener(CLIENTE, "SIN-NADA")


def test_la_huella_ignora_espacios_alrededor_de_la_descripcion():
    assert DATOS.huella() == replace(DATOS, descripcion=f"  {DATOS.descripcion} ").huella()


class PolizasFalsasParaTodos(PolizasFalsas):
    def poliza_del_cliente(self, numero: str, id_cliente: str) -> VigenciaDePoliza | None:
        return self._polizas.get(numero)


# -- HTTP con el módulo de pólizas real ---------------------------------------------------

SOLICITUD = {
    "numero_poliza": "POL-2026-000101-000318",
    "tipo_evento": "accidente_vehiculo",
    "ocurrido_el": (date.today() - timedelta(days=1)).isoformat(),
    "descripcion": "Choque leve en la calle 80 al frenar.",
}


@pytest.fixture
def nucleo(tmp_path, monkeypatch) -> TestClient:
    semilla = tmp_path / "polizas.json"
    semilla.write_text(
        json.dumps(
            [
                {
                    "numero": "POL-2026-000101",
                    "id_cliente": "*",
                    "producto": "auto",
                    "desde_dias": -30,
                    "hasta_dias": 335,
                }
            ]
        ),
        encoding="utf-8",
    )
    monkeypatch.setenv(polizas_dependencias.VARIABLE_SEMILLA, str(semilla))
    polizas_dependencias.reiniciar()
    dependencias.reiniciar()
    yield TestClient(crear_app())
    dependencias.reiniciar()
    polizas_dependencias.reiniciar()


RUTA = f"/v1/siniestros/clientes/{CLIENTE}/siniestros"


def test_reportar_por_http_valida_contra_las_polizas_del_nucleo(nucleo):
    respuesta = nucleo.post(
        RUTA, json=SOLICITUD, headers={"X-Canal": "movil", "Idempotency-Key": "clave-http-1"}
    )

    assert respuesta.status_code == 201
    cuerpo = respuesta.json()
    assert cuerpo["referencia"].startswith("SIN-")
    assert cuerpo["estado"] == "reportado"
    assert cuerpo["siguiente_paso"] == "adjuntar_evidencia"
    assert cuerpo["canal"] == "movil"


def test_repetir_con_la_misma_clave_responde_200_con_la_misma_referencia(nucleo):
    cabeceras = {"Idempotency-Key": "clave-http-2"}
    primera = nucleo.post(RUTA, json=SOLICITUD, headers=cabeceras).json()

    segunda = nucleo.post(RUTA, json=SOLICITUD, headers=cabeceras)

    assert segunda.status_code == 200
    assert segunda.json()["referencia"] == primera["referencia"]
    assert len(nucleo.get(RUTA).json()) == 1


def test_la_poliza_de_otro_cliente_es_404_por_http(nucleo):
    respuesta = nucleo.post(RUTA, json={**SOLICITUD, "numero_poliza": "POL-2026-000101-000999"})

    assert respuesta.status_code == 404
    assert respuesta.json()["title"] == "poliza_no_encontrada"


def test_una_fecha_futura_es_400_fecha_futura(nucleo):
    manana = (date.today() + timedelta(days=2)).isoformat()

    respuesta = nucleo.post(RUTA, json={**SOLICITUD, "ocurrido_el": manana})

    assert respuesta.status_code == 400
    assert respuesta.json()["title"] == "fecha_futura"


@pytest.mark.parametrize(
    "cambio",
    [
        {"descripcion": "corta"},
        {"tipo_evento": "terremoto_marciano"},
        {"numero_poliza": ""},
        {"monto_estimado": -1},
    ],
)
def test_los_campos_obligatorios_se_validan(nucleo, cambio):
    assert nucleo.post(RUTA, json={**SOLICITUD, **cambio}).status_code == 422


def test_el_detalle_de_un_siniestro_por_http(nucleo):
    creado = nucleo.post(RUTA, json=SOLICITUD).json()

    detalle = nucleo.get(f"{RUTA}/{creado['id']}")
    ajeno = nucleo.get(f"/v1/siniestros/clientes/CLI-OTRO/siniestros/{creado['id']}")

    assert detalle.json() == creado
    assert ajeno.status_code == 404
    assert ajeno.json()["title"] == "siniestro_no_encontrado"
