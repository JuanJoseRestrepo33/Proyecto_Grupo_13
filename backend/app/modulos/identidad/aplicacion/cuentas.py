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
    ClienteNoEncontradoError,
    CorreoNoVerificadoError,
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
from ..dominio.modelo import DatosDeIdentidad, EstadoIdentidad, TipoDocumento
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

#: Datos fijos de la cuenta de prueba de *staging*. Solo cambian correo y contraseña. Su id
#: tampoco cambia entre revisiones: lo que ella dejó en Postgres sigue siendo suyo.
ID_CUENTA_PRUEBA = "CLI-2026-000001"
NOMBRE_CUENTA_PRUEBA = "Cuenta de prueba"
DOCUMENTO_CUENTA_PRUEBA = "PRUEBA0001"


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

    def sembrar_cuenta_de_prueba(self, correo: str, contrasena: str) -> Cuenta:
        """Crea, si no existe, la cuenta con correo verificado que usan el humo y las E2E.

        Las cuentas viven en memoria y se pierden con cada revisión del núcleo; sembrarla
        al arrancar la mantiene disponible sin registrarla a mano. No publica
        `ClientRegistered`: no es un cliente que se registró.
        """
        normalizado = normalizar_correo(correo)
        with self._cerrojo_altas:
            existente = self._cuentas.por_correo(normalizado)
            if existente is not None:
                return existente
            ahora = self._reloj.ahora()
            cuenta = Cuenta(
                id_cliente=ID_CUENTA_PRUEBA,
                nombre_completo=NOMBRE_CUENTA_PRUEBA,
                tipo_documento=TipoDocumento.PASAPORTE,
                numero_documento=DOCUMENTO_CUENTA_PRUEBA,
                correo=normalizado,
                celular="3000000000",
                credencial=credenciales.derivar(contrasena),
                version_terminos="prueba",
                creada_en=ahora,
                correo_verificado=True,
            )
            self._cuentas.guardar(cuenta)
        return cuenta

    # -- sesión -----------------------------------------------------------------------

    def iniciar_sesion(self, correo: str, contrasena: str, canal: str) -> SesionAbierta:
        """Abre la sesión con correo y contraseña, en cualquiera de los dos canales.

        Si el correo no existe se verifica igual contra una credencial señuelo y se responde
        el mismo error: ni el mensaje ni el tiempo revelan qué cuentas existen (criterio 3).
        """
        cuenta = self._cuentas.por_correo(normalizar_correo(correo))
        credencial = cuenta.credencial if cuenta else credenciales.credencial_senuelo()
        valida = credenciales.verificar(contrasena, credencial)
        id_cliente = cuenta.id_cliente if cuenta else None
        self._accesos.registrar(
            "ingreso",
            id_cliente=id_cliente,
            canal=canal,
            ocurrido_en=self._reloj.ahora(),
            resultado={"acierto": cuenta is not None and valida},
        )
        if cuenta is None or not valida:
            raise CredencialesInvalidasError("Correo o contraseña incorrectos")
        return self._abrir_sesion(cuenta, canal)

    def renovar_sesion(self, token_refresco: str) -> SesionAbierta:
        """Renueva con el token de refresco (ingreso biométrico en la app).

        El token es de un solo uso: renovar entrega uno nuevo y el anterior deja de servir,
        aunque alguien lo haya copiado (rotación).
        """
        refresco = self._refrescos.tomar(resumen_de_refresco(token_refresco))
        ahora = self._reloj.ahora()
        if refresco is None or not refresco.vigente(ahora):
            raise TokenRefrescoInvalidoError("El token de refresco no es válido")
        cuenta = self._cuentas.por_id(refresco.id_cliente)
        if cuenta is None:
            raise TokenRefrescoInvalidoError("El token de refresco no es válido")
        self._accesos.registrar(
            "renovacion",
            id_cliente=cuenta.id_cliente,
            canal=refresco.canal,
            ocurrido_en=ahora,
            resultado={"acierto": True},
        )
        return self._abrir_sesion(cuenta, refresco.canal)

    def cliente(self, id_cliente: str) -> Cuenta:
        cuenta = self._cuentas.por_id(id_cliente)
        if cuenta is None:
            raise ClienteNoEncontradoError("El cliente no existe", id_cliente=id_cliente)
        return cuenta

    def datos_de_identidad(self, id_cliente: str) -> DatosDeIdentidad:
        """Lo que el proveedor de KYC/AML necesita, tal como quedó en el registro.

        Solo para quien demostró su correo: quien abre sesión sin haber confirmado el OTP no
        puede verificar una identidad registrada con datos ajenos.
        """
        cuenta = self.cliente(id_cliente)
        if not cuenta.correo_verificado:
            raise CorreoNoVerificadoError(
                "El correo de la cuenta no está verificado", id_cliente=id_cliente
            )
        return DatosDeIdentidad(
            nombre_completo=cuenta.nombre_completo,
            tipo_documento=cuenta.tipo_documento,
            numero_documento=cuenta.numero_documento,
        )

    def cliente_de_sesion(self, token_acceso: str) -> Cuenta:
        """El cliente de un token de acceso vigente, o `SesionNoVigenteError`."""
        reclamos = self._emisor.validar_acceso(token_acceso, self._reloj.ahora())
        return self.cliente(reclamos.id_cliente)

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
