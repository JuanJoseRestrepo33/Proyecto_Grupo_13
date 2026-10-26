"""Pruebas del registro y la verificación del correo (MOB-F01, WEB-F13)."""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field, replace
from datetime import UTC, datetime, timedelta

import pytest

from app.compartido.eventos import BusEnProceso
from app.compartido.reloj import RelojFijo
from app.modulos.identidad.aplicacion.cuentas import EVENTO_CLIENTE_REGISTRADO, ServicioDeCuentas
from app.modulos.identidad.dominio import credenciales
from app.modulos.identidad.dominio.credenciales import ContrasenaDebilError
from app.modulos.identidad.dominio.cuenta import DatosDeRegistro
from app.modulos.identidad.dominio.errores import (
    ClienteNoEncontradoError,
    CorreoYaRegistradoError,
    CredencialesInvalidasError,
    DocumentoYaRegistradoError,
    OtpBloqueadoError,
    OtpInvalidoError,
    OtpReenvioPrematuroError,
    OtpVencidoError,
    RegistroNoEncontradoError,
    RegistroYaVerificadoError,
    TokenRefrescoInvalidoError,
)
from app.modulos.identidad.dominio.modelo import EstadoIdentidad, TipoDocumento
from app.modulos.identidad.dominio.sesion import SesionNoVigenteError
from app.modulos.identidad.infraestructura.codigos_simulados import (
    CanalDeCodigosSimulado,
    enmascarar,
)
from app.modulos.identidad.infraestructura.cuentas_memoria import (
    RepositorioDeCuentasEnMemoria,
    RepositorioDeRefrescosEnMemoria,
    RepositorioDeRegistrosEnMemoria,
)
from app.modulos.identidad.infraestructura.tokens_es256 import EmisorEs256

INSTANTE = datetime(2026, 10, 26, 15, 0, tzinfo=UTC)
CLAVE = "Solventa2026"

DATOS = DatosDeRegistro(
    nombre_completo="Ana María Rojas",
    tipo_documento=TipoDocumento.CEDULA_CIUDADANIA,
    numero_documento="1020304050",
    correo="Ana@Ejemplo.co",
    celular="3001234567",
    contrasena=CLAVE,
    version_terminos="2026-09",
)


@dataclass
class AccesosEnMemoria:
    sucesos: list[dict[str, object]] = field(default_factory=list)

    def registrar(self, suceso, *, id_cliente, canal, ocurrido_en, resultado) -> str:
        self.sucesos.append(
            {"suceso": suceso, "id_cliente": id_cliente, "canal": canal, "resultado": resultado}
        )
        return f"DEC-{len(self.sucesos)}"


@dataclass
class Entorno:
    servicio: ServicioDeCuentas
    reloj: RelojFijo
    codigos: CanalDeCodigosSimulado
    accesos: AccesosEnMemoria
    bus: BusEnProceso
    emisor: EmisorEs256
    cuentas: RepositorioDeCuentasEnMemoria


@pytest.fixture
def entorno() -> Entorno:
    reloj = RelojFijo(INSTANTE)
    codigos = CanalDeCodigosSimulado()
    accesos = AccesosEnMemoria()
    bus = BusEnProceso()
    emisor = EmisorEs256.efimero()
    cuentas = RepositorioDeCuentasEnMemoria()
    servicio = ServicioDeCuentas(
        cuentas=cuentas,
        registros=RepositorioDeRegistrosEnMemoria(),
        refrescos=RepositorioDeRefrescosEnMemoria(),
        codigos=codigos,
        emisor=emisor,
        accesos=accesos,
        bus=bus,
        reloj=reloj,
        estado_identidad=lambda _: EstadoIdentidad.PENDIENTE_VERIFICACION,
    )
    return Entorno(servicio, reloj, codigos, accesos, bus, emisor, cuentas)


def _codigo(entorno: Entorno) -> str:
    codigo = entorno.codigos.ultimo_codigo_para("ana@ejemplo.co")
    assert codigo is not None
    return codigo


def _otro_codigo(codigo: str) -> str:
    return f"{(int(codigo) + 1) % 1_000_000:06d}"


# -- registro -----------------------------------------------------------------------


def test_registrar_crea_la_cuenta_pendiente_y_envia_el_codigo(entorno):
    registro, cuenta = entorno.servicio.registrar(DATOS, "movil")

    assert cuenta.id_cliente == "CLI-2026-000001"
    assert registro.id_registro == "REG-2026-000001"
    assert cuenta.correo == "ana@ejemplo.co"
    assert cuenta.correo_verificado is False
    assert len(_codigo(entorno)) == 6
    assert registro.intentos_restantes == 3
    assert registro.expira_en == INSTANTE + timedelta(minutes=10)
    assert registro.reenvio_desde == INSTANTE + timedelta(seconds=30)


def test_la_contrasena_queda_con_hash_y_sal_nunca_en_claro(entorno):
    _, cuenta = entorno.servicio.registrar(DATOS, "movil")

    assert CLAVE not in cuenta.credencial
    assert credenciales.verificar(CLAVE, cuenta.credencial)
    assert CLAVE not in repr(DATOS)


def test_registrar_publica_client_registered(entorno):
    _, cuenta = entorno.servicio.registrar(DATOS, "movil")

    publicados = entorno.bus.publicados(EVENTO_CLIENTE_REGISTRADO)
    assert len(publicados) == 1
    assert publicados[0].carga == {
        "id_cliente": cuenta.id_cliente,
        "canal": "movil",
        "version_terminos": "2026-09",
    }


def test_el_registro_queda_auditado_sin_datos_secretos(entorno):
    _, cuenta = entorno.servicio.registrar(DATOS, "movil")

    (suceso,) = entorno.accesos.sucesos
    assert suceso["suceso"] == "registro"
    assert suceso["id_cliente"] == cuenta.id_cliente
    assert CLAVE not in str(suceso)
    assert _codigo(entorno) not in str(suceso)


def test_el_correo_se_compara_sin_mayusculas_ni_espacios(entorno):
    entorno.servicio.registrar(DATOS, "movil")

    with pytest.raises(CorreoYaRegistradoError):
        entorno.servicio.registrar(
            replace(DATOS, correo=" ANA@ejemplo.co ", numero_documento="99999"), "web"
        )


def test_un_documento_ya_registrado_se_rechaza(entorno):
    entorno.servicio.registrar(DATOS, "movil")

    with pytest.raises(DocumentoYaRegistradoError):
        entorno.servicio.registrar(replace(DATOS, correo="otra@ejemplo.co"), "movil")


def test_una_contrasena_debil_no_crea_la_cuenta(entorno):
    with pytest.raises(ContrasenaDebilError):
        entorno.servicio.registrar(replace(DATOS, contrasena="solo-letras"), "movil")

    assert entorno.cuentas.por_correo("ana@ejemplo.co") is None
    assert entorno.bus.publicados(EVENTO_CLIENTE_REGISTRADO) == ()


def test_dos_registros_simultaneos_del_mismo_correo_dejan_una_cuenta(entorno):
    def registrar(i: int) -> str:
        try:
            entorno.servicio.registrar(replace(DATOS, numero_documento=f"DOC{i:05d}"), "movil")
            return "ok"
        except CorreoYaRegistradoError:
            return "duplicado"

    with ThreadPoolExecutor(max_workers=4) as hilos:
        resultados = list(hilos.map(registrar, range(4)))

    assert resultados.count("ok") == 1


# -- verificación del correo ----------------------------------------------------------


def test_el_codigo_correcto_verifica_el_correo_y_abre_la_sesion(entorno):
    registro, cuenta = entorno.servicio.registrar(DATOS, "movil")

    sesion = entorno.servicio.verificar_correo(registro.id_registro, _codigo(entorno), "movil")

    assert sesion.cuenta.correo_verificado is True
    assert entorno.cuentas.por_id(cuenta.id_cliente).correo_verificado is True
    reclamos = entorno.emisor.validar_acceso(sesion.acceso.token, INSTANTE)
    assert (reclamos.id_cliente, reclamos.canal) == (cuenta.id_cliente, "movil")
    assert sesion.acceso.expira_en == INSTANTE + timedelta(minutes=15)
    assert sesion.refresco
    assert sesion.estado_identidad is EstadoIdentidad.PENDIENTE_VERIFICACION


def test_un_codigo_incorrecto_descuenta_intentos_y_al_tercero_bloquea(entorno):
    registro, _ = entorno.servicio.registrar(DATOS, "movil")
    malo = _otro_codigo(_codigo(entorno))

    for restantes in (2, 1):
        with pytest.raises(OtpInvalidoError) as error:
            entorno.servicio.verificar_correo(registro.id_registro, malo, "movil")
        assert error.value.detalles == {"intentos_restantes": restantes}
    with pytest.raises(OtpBloqueadoError):
        entorno.servicio.verificar_correo(registro.id_registro, malo, "movil")
    # Bloqueado, ni el correcto sirve: hay que pedir otro código.
    with pytest.raises(OtpBloqueadoError):
        entorno.servicio.verificar_correo(registro.id_registro, _codigo(entorno), "movil")


def test_cada_intento_de_verificacion_queda_auditado(entorno):
    registro, _ = entorno.servicio.registrar(DATOS, "movil")
    with pytest.raises(OtpInvalidoError):
        entorno.servicio.verificar_correo(
            registro.id_registro, _otro_codigo(_codigo(entorno)), "movil"
        )
    entorno.servicio.verificar_correo(registro.id_registro, _codigo(entorno), "movil")

    intentos = [
        s["resultado"]["acierto"]
        for s in entorno.accesos.sucesos
        if s["suceso"] == "verificacion_correo"
    ]
    assert intentos == [False, True]


def test_un_codigo_vencido_no_verifica(entorno):
    registro, _ = entorno.servicio.registrar(DATOS, "movil")
    entorno.reloj.avanzar(timedelta(minutes=10).total_seconds())

    with pytest.raises(OtpVencidoError):
        entorno.servicio.verificar_correo(registro.id_registro, _codigo(entorno), "movil")


def test_un_correo_verificado_no_se_verifica_dos_veces(entorno):
    registro, _ = entorno.servicio.registrar(DATOS, "movil")
    entorno.servicio.verificar_correo(registro.id_registro, _codigo(entorno), "movil")

    with pytest.raises(RegistroYaVerificadoError):
        entorno.servicio.verificar_correo(registro.id_registro, _codigo(entorno), "movil")
    with pytest.raises(RegistroYaVerificadoError):
        entorno.servicio.reenviar_codigo(registro.id_registro)


def test_un_registro_inexistente_es_404(entorno):
    with pytest.raises(RegistroNoEncontradoError):
        entorno.servicio.verificar_correo("REG-2026-999999", "123456", "movil")
    with pytest.raises(RegistroNoEncontradoError):
        entorno.servicio.reenviar_codigo("REG-2026-999999")


# -- reenvío del código -----------------------------------------------------------------


def test_no_se_reenvia_antes_de_30_segundos_y_dice_cuanto_falta(entorno):
    registro, _ = entorno.servicio.registrar(DATOS, "movil")
    entorno.reloj.avanzar(10)

    with pytest.raises(OtpReenvioPrematuroError) as error:
        entorno.servicio.reenviar_codigo(registro.id_registro)

    assert error.value.detalles == {"reintentar_en_segundos": 20}


def test_el_reenvio_invalida_el_codigo_anterior_y_repone_los_intentos(entorno):
    registro, _ = entorno.servicio.registrar(DATOS, "movil")
    anterior = _codigo(entorno)
    with pytest.raises(OtpInvalidoError):
        entorno.servicio.verificar_correo(registro.id_registro, _otro_codigo(anterior), "movil")
    entorno.reloj.avanzar(30)

    nuevo_registro = entorno.servicio.reenviar_codigo(registro.id_registro)
    nuevo = _codigo(entorno)

    assert nuevo_registro.intentos_restantes == 3
    assert nuevo_registro.expira_en == INSTANTE + timedelta(seconds=30, minutes=10)
    if nuevo != anterior:
        with pytest.raises(OtpInvalidoError):
            entorno.servicio.verificar_correo(registro.id_registro, anterior, "movil")
    assert entorno.servicio.verificar_correo(registro.id_registro, nuevo, "movil").refresco


def test_el_canal_de_codigos_enmascara_el_correo_y_valida_el_codigo_fijo():
    assert enmascarar("ana@ejemplo.co") == "a***@ejemplo.co"
    assert CanalDeCodigosSimulado("123456").nuevo_codigo() == "123456"
    with pytest.raises(ValueError):
        CanalDeCodigosSimulado("12ab")


# -- sesión (WEB-F13) -------------------------------------------------------------------


def _cuenta_verificada(entorno):
    registro, cuenta = entorno.servicio.registrar(DATOS, "movil")
    entorno.servicio.verificar_correo(registro.id_registro, _codigo(entorno), "movil")
    return cuenta


def test_la_cuenta_de_la_app_entra_en_la_web_con_el_mismo_correo(entorno):
    """Criterio 1: registrado en la app, entra a la web sin otro registro."""
    cuenta = _cuenta_verificada(entorno)

    sesion = entorno.servicio.iniciar_sesion(" ANA@ejemplo.co", CLAVE, "web")

    reclamos = entorno.emisor.validar_acceso(sesion.acceso.token, INSTANTE)
    assert (reclamos.id_cliente, reclamos.canal) == (cuenta.id_cliente, "web")
    assert sesion.acceso.expira_en == INSTANTE + timedelta(minutes=60)


def test_credenciales_invalidas_dan_el_mismo_error_exista_o_no_el_correo(entorno):
    """Criterio 3: el mensaje no revela si el correo existe."""
    _cuenta_verificada(entorno)

    with pytest.raises(CredencialesInvalidasError) as clave_mala:
        entorno.servicio.iniciar_sesion("ana@ejemplo.co", "OtraClave1", "web")
    with pytest.raises(CredencialesInvalidasError) as correo_desconocido:
        entorno.servicio.iniciar_sesion("nadie@ejemplo.co", CLAVE, "web")

    assert clave_mala.value.mensaje == correo_desconocido.value.mensaje
    assert clave_mala.value.detalles == correo_desconocido.value.detalles == {}


def test_cada_ingreso_queda_auditado_sin_el_correo_desconocido(entorno):
    cuenta = _cuenta_verificada(entorno)
    entorno.servicio.iniciar_sesion("ana@ejemplo.co", CLAVE, "web")
    with pytest.raises(CredencialesInvalidasError):
        entorno.servicio.iniciar_sesion("nadie@ejemplo.co", CLAVE, "movil")

    ingresos = [s for s in entorno.accesos.sucesos if s["suceso"] == "ingreso"]
    assert [(s["id_cliente"], s["resultado"]["acierto"]) for s in ingresos] == [
        (cuenta.id_cliente, True),
        (None, False),
    ]
    assert "nadie@ejemplo.co" not in str(ingresos)


def test_renovar_entrega_un_refresco_nuevo_y_el_anterior_deja_de_servir(entorno):
    _cuenta_verificada(entorno)
    primera = entorno.servicio.iniciar_sesion("ana@ejemplo.co", CLAVE, "movil")

    renovada = entorno.servicio.renovar_sesion(primera.refresco)

    assert renovada.refresco != primera.refresco
    assert entorno.emisor.validar_acceso(renovada.acceso.token, INSTANTE).canal == "movil"
    with pytest.raises(TokenRefrescoInvalidoError):
        entorno.servicio.renovar_sesion(primera.refresco)


def test_un_refresco_vencido_o_inventado_no_renueva(entorno):
    _cuenta_verificada(entorno)
    sesion = entorno.servicio.iniciar_sesion("ana@ejemplo.co", CLAVE, "movil")
    entorno.reloj.avanzar(timedelta(days=30).total_seconds())

    with pytest.raises(TokenRefrescoInvalidoError):
        entorno.servicio.renovar_sesion(sesion.refresco)
    with pytest.raises(TokenRefrescoInvalidoError):
        entorno.servicio.renovar_sesion("inventado")


def test_el_cliente_de_un_token_vigente(entorno):
    cuenta = _cuenta_verificada(entorno)
    sesion = entorno.servicio.iniciar_sesion("ana@ejemplo.co", CLAVE, "web")

    assert entorno.servicio.cliente_de_sesion(sesion.acceso.token) == entorno.servicio.cliente(
        cuenta.id_cliente
    )
    entorno.reloj.avanzar(timedelta(minutes=61).total_seconds())
    with pytest.raises(SesionNoVigenteError):
        entorno.servicio.cliente_de_sesion(sesion.acceso.token)


def test_un_cliente_inexistente_es_404(entorno):
    with pytest.raises(ClienteNoEncontradoError):
        entorno.servicio.cliente("CLI-2026-999999")


# -- cuenta de prueba de staging ----------------------------------------------------------


def test_la_cuenta_de_prueba_entra_sin_registrarse_y_no_se_duplica(entorno):
    primera = entorno.servicio.sembrar_cuenta_de_prueba("Humo@Solventa.co", "Humo2026x")
    segunda = entorno.servicio.sembrar_cuenta_de_prueba("humo@solventa.co", "Humo2026x")

    sesion = entorno.servicio.iniciar_sesion("humo@solventa.co", "Humo2026x", "web")

    assert segunda == primera
    assert primera.correo_verificado is True
    assert sesion.cuenta.id_cliente == primera.id_cliente
    assert entorno.bus.publicados(EVENTO_CLIENTE_REGISTRADO) == ()
