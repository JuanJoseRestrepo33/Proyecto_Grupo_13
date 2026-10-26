"""Casos de uso de la cuenta: registro, verificación del correo y sesión (MOB-F01, WEB-F13).

La cuenta es una sola para los dos canales. Lo que cambia por canal es la vigencia del token
de acceso: la web no renueva, la app sí.

Nada de lo que pasa por aquí llega en claro a un log: ni la contraseña (se guarda derivada),
ni el código (se guarda su resumen), ni los tokens (del refresco se guarda su resumen).
"""

from __future__ import annotations

import hashlib
import secrets
import threading
from collections.abc import Callable
from dataclasses import dataclass

from app.compartido.eventos import BusDeEventos, Evento
from app.compartido.reloj import Reloj

from ..dominio import credenciales
from ..dominio.cuenta import Cuenta, DatosDeRegistro, Registro, normalizar_correo
from ..dominio.errores import (
    CorreoYaRegistradoError,
    DocumentoYaRegistradoError,
    OtpBloqueadoError,
    OtpInvalidoError,
    OtpReenvioPrematuroError,
    OtpVencidoError,
    RegistroNoEncontradoError,
    RegistroYaVerificadoError,
)
from ..dominio.modelo import EstadoIdentidad
from ..dominio.puertos import (
    CanalDeCodigos,
    EmisorDeTokens,
    RegistroDeAccesos,
    RepositorioCuentas,
    RepositorioRefrescos,
    RepositorioRegistros,
)
from ..dominio.sesion import VIGENCIA_ACCESO, VIGENCIA_REFRESCO, Refresco, TokenEmitido

#: Nombre del evento que se publica al crear una cuenta (criterio 2 de WEB-F13).
EVENTO_CLIENTE_REGISTRADO = "ClientRegistered"


def resumen_de_refresco(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


@dataclass(frozen=True, slots=True)
class SesionAbierta:
    acceso: TokenEmitido
    refresco: str
    cuenta: Cuenta
    estado_identidad: EstadoIdentidad


class ServicioDeCuentas:
    def __init__(
        self,
        cuentas: RepositorioCuentas,
        registros: RepositorioRegistros,
        refrescos: RepositorioRefrescos,
        codigos: CanalDeCodigos,
        emisor: EmisorDeTokens,
        accesos: RegistroDeAccesos,
        bus: BusDeEventos,
        reloj: Reloj,
        estado_identidad: Callable[[str], EstadoIdentidad],
    ) -> None:
        self._cuentas = cuentas
        self._registros = registros
        self._refrescos = refrescos
        self._codigos = codigos
        self._emisor = emisor
        self._accesos = accesos
        self._bus = bus
        self._reloj = reloj
        self._estado_identidad = estado_identidad
        # Serializa la comprobación de correo y documento libres con el alta de la cuenta.
        self._cerrojo_altas = threading.Lock()
        self._cerrojo_registros = threading.Lock()

    # -- registro ---------------------------------------------------------------------

    def registrar(self, datos: DatosDeRegistro, canal: str) -> tuple[Registro, Cuenta]:
        """Crea la cuenta y envía el código al correo.

        La cuenta queda `pendiente_verificacion` y se publica `ClientRegistered`. La
        contraseña se guarda derivada con hash y sal, nunca en claro.
        """
        credenciales.exigir_contrasena_valida(datos.contrasena)
        correo = normalizar_correo(datos.correo)
        credencial = credenciales.derivar(datos.contrasena)
        ahora = self._reloj.ahora()
        with self._cerrojo_altas:
            if self._cuentas.por_correo(correo) is not None:
                raise CorreoYaRegistradoError("El correo ya tiene una cuenta")
            if self._cuentas.por_documento(datos.numero_documento) is not None:
                raise DocumentoYaRegistradoError("El documento ya tiene una cuenta")
            cuenta = Cuenta(
                id_cliente=self._cuentas.siguiente_id(ahora.year),
                nombre_completo=datos.nombre_completo.strip(),
                tipo_documento=datos.tipo_documento,
                numero_documento=datos.numero_documento,
                correo=correo,
                celular=datos.celular,
                credencial=credencial,
                version_terminos=datos.version_terminos,
                creada_en=ahora,
            )
            self._cuentas.guardar(cuenta)

        codigo = self._codigos.nuevo_codigo()
        registro = Registro.nuevo(
            self._registros.siguiente_id(ahora.year), cuenta.id_cliente, codigo, ahora
        )
        self._registros.guardar(registro)
        self._codigos.enviar(correo, codigo)

        self._accesos.registrar(
            "registro",
            id_cliente=cuenta.id_cliente,
            canal=canal,
            ocurrido_en=ahora,
            resultado={
                "id_registro": registro.id_registro,
                "version_terminos": datos.version_terminos,
            },
        )
        self._bus.publicar(
            Evento(
                nombre=EVENTO_CLIENTE_REGISTRADO,
                carga={
                    "id_cliente": cuenta.id_cliente,
                    "canal": canal,
                    "version_terminos": datos.version_terminos,
                },
                ocurrido_en=ahora,
            )
        )
        return registro, cuenta

    def estado_identidad(self, id_cliente: str) -> EstadoIdentidad:
        return self._estado_identidad(id_cliente)

    def verificar_correo(self, id_registro: str, codigo: str, canal: str) -> SesionAbierta:
        """Verifica el código y abre la primera sesión.

        Hay 3 intentos por código: al tercer fallo el código queda bloqueado y hay que pedir
        otro. La cuenta no se bloquea.
        """
        ahora = self._reloj.ahora()
        with self._cerrojo_registros:
            registro = self._registro(id_registro)
            if registro.verificado:
                raise RegistroYaVerificadoError("El correo ya está verificado")
            if registro.intentos_restantes == 0:
                raise OtpBloqueadoError("Se agotaron los intentos; pide otro código")
            if registro.vencido(ahora):
                raise OtpVencidoError("El código venció; pide otro")
            if not registro.acierta(codigo):
                registro = registro.con_intento_fallido()
                self._registros.guardar(registro)
                self._auditar_intento(registro, canal, acierto=False)
                if registro.intentos_restantes == 0:
                    raise OtpBloqueadoError("Se agotaron los intentos; pide otro código")
                raise OtpInvalidoError(
                    "El código no es correcto", intentos_restantes=registro.intentos_restantes
                )
            registro = registro.como_verificado()
            self._registros.guardar(registro)

        cuenta = self._cuentas.por_id(registro.id_cliente)
        assert cuenta is not None, "un registro siempre tiene su cuenta"
        cuenta = cuenta.con_correo_verificado()
        self._cuentas.guardar(cuenta)
        self._auditar_intento(registro, canal, acierto=True)
        return self._abrir_sesion(cuenta, canal)

    def reenviar_codigo(self, id_registro: str) -> Registro:
        """Envía un código nuevo e invalida el anterior. No antes de 30 segundos."""
        ahora = self._reloj.ahora()
        with self._cerrojo_registros:
            registro = self._registro(id_registro)
            if registro.verificado:
                raise RegistroYaVerificadoError("El correo ya está verificado")
            espera = registro.segundos_para_reenviar(ahora)
            if espera > 0:
                raise OtpReenvioPrematuroError(
                    "Se pidió un código hace muy poco", reintentar_en_segundos=espera
                )
            codigo = self._codigos.nuevo_codigo()
            registro = registro.con_codigo_nuevo(codigo, ahora)
            self._registros.guardar(registro)
        cuenta = self._cuentas.por_id(registro.id_cliente)
        assert cuenta is not None, "un registro siempre tiene su cuenta"
        self._codigos.enviar(cuenta.correo, codigo)
        return registro

    # -- interno ----------------------------------------------------------------------

    def _registro(self, id_registro: str) -> Registro:
        registro = self._registros.obtener(id_registro)
        if registro is None:
            raise RegistroNoEncontradoError("El registro no existe", id_registro=id_registro)
        return registro

    def _auditar_intento(self, registro: Registro, canal: str, *, acierto: bool) -> None:
        self._accesos.registrar(
            "verificacion_correo",
            id_cliente=registro.id_cliente,
            canal=canal,
            ocurrido_en=self._reloj.ahora(),
            resultado={
                "id_registro": registro.id_registro,
                "acierto": acierto,
                "intentos_restantes": registro.intentos_restantes,
            },
        )

    def _abrir_sesion(self, cuenta: Cuenta, canal: str) -> SesionAbierta:
        ahora = self._reloj.ahora()
        vigencia = VIGENCIA_ACCESO.get(canal, VIGENCIA_ACCESO["movil"])
        acceso = self._emisor.emitir_acceso(cuenta.id_cliente, canal, ahora, vigencia)
        refresco = secrets.token_urlsafe(32)
        self._refrescos.guardar(
            Refresco(
                resumen=resumen_de_refresco(refresco),
                id_cliente=cuenta.id_cliente,
                canal=canal,
                expira_en=ahora + VIGENCIA_REFRESCO,
            )
        )
        return SesionAbierta(
            acceso=acceso,
            refresco=refresco,
            cuenta=cuenta,
            estado_identidad=self._estado_identidad(cuenta.id_cliente),
        )
