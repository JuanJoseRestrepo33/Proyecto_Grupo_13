"""Cuentas, registros, refrescos y verificaciones en PostgreSQL (ARQ-05, #136), esquema `identidad`.

En memoria, cada réplica del núcleo tenía sus propias cuentas: una sesión abierta en una no
existía en la otra, y por eso el núcleo corría con una sola instancia. Con identidad en Cloud
SQL todas las réplicas ven las mismas cuentas, los mismos códigos de verificación y los mismos
tokens de refresco, y las cuentas sobreviven a cada despliegue.

- El correo y el documento son únicos en la base, no solo en el cerrojo de la réplica: dos
  altas simultáneas en réplicas distintas no pueden crear la misma cuenta, y la que pierde
  recibe el mismo rechazo de negocio que da la comprobación previa.
- `tomar` un refresco es un `DELETE … RETURNING`: es de un solo uso aunque dos réplicas lo
  presenten a la vez.
- Los identificadores salen de secuencias. La de cuentas arranca por encima de los que dio la
  versión en memoria (que usaba la hora de inicio), para no reutilizar ninguno: un token
  vigente abriría la cuenta de otra persona.
- El esquema es idempotente y un *advisory lock* serializa su preparación entre réplicas.
"""

from __future__ import annotations

from collections.abc import Mapping
from contextlib import AbstractContextManager
from datetime import datetime

import psycopg
from psycopg.types.json import Jsonb

from app.compartido.conexiones import conexion as conexion_del_pool

from ..dominio.cuenta import Cuenta, Registro
from ..dominio.errores import CorreoYaRegistradoError, DocumentoYaRegistradoError
from ..dominio.modelo import (
    DatosDeIdentidad,
    EstadoVerificacion,
    MotivoRechazo,
    TipoDocumento,
    VerificacionIdentidad,
)
from ..dominio.sesion import Refresco

#: Por encima de cualquier id que haya dado la versión en memoria (hora Unix de inicio).
PRIMER_CONSECUTIVO_CUENTAS = 1_800_000_000

TABLAS = f"""
SELECT pg_advisory_xact_lock(4501136);

CREATE SCHEMA IF NOT EXISTS identidad;

CREATE SEQUENCE IF NOT EXISTS identidad.consecutivo_cuentas START WITH {PRIMER_CONSECUTIVO_CUENTAS};
CREATE SEQUENCE IF NOT EXISTS identidad.consecutivo_registros START WITH 1;

CREATE TABLE IF NOT EXISTS identidad.cuentas (
    id_cliente         TEXT         PRIMARY KEY,
    nombre_completo    TEXT         NOT NULL,
    tipo_documento     TEXT         NOT NULL,
    numero_documento   TEXT         NOT NULL UNIQUE,
    correo             TEXT         NOT NULL UNIQUE,
    celular            TEXT         NOT NULL,
    credencial         TEXT         NOT NULL,
    version_terminos   TEXT         NOT NULL,
    creada_en          TIMESTAMPTZ  NOT NULL,
    correo_verificado  BOOLEAN      NOT NULL
);

CREATE TABLE IF NOT EXISTS identidad.registros (
    id_registro         TEXT         PRIMARY KEY,
    id_cliente          TEXT         NOT NULL,
    resumen_codigo      TEXT         NOT NULL,
    expira_en           TIMESTAMPTZ  NOT NULL,
    reenvio_desde       TIMESTAMPTZ  NOT NULL,
    intentos_restantes  INTEGER      NOT NULL,
    verificado          BOOLEAN      NOT NULL
);

CREATE TABLE IF NOT EXISTS identidad.refrescos (
    resumen     TEXT         PRIMARY KEY,
    id_cliente  TEXT         NOT NULL,
    canal       TEXT         NOT NULL,
    expira_en   TIMESTAMPTZ  NOT NULL
);

CREATE TABLE IF NOT EXISTS identidad.verificaciones (
    id_verificacion  TEXT         PRIMARY KEY,
    id_cliente       TEXT         NOT NULL,
    solicitada_en    TIMESTAMPTZ  NOT NULL,
    datos            JSONB        NOT NULL
);

CREATE INDEX IF NOT EXISTS verificaciones_por_cliente
    ON identidad.verificaciones (id_cliente, solicitada_en DESC);
"""

COLUMNAS_CUENTA = """
    id_cliente, nombre_completo, tipo_documento, numero_documento, correo, celular, credencial,
    version_terminos, creada_en, correo_verificado
"""

GUARDAR_CUENTA = f"""
INSERT INTO identidad.cuentas ({COLUMNAS_CUENTA}) VALUES (
    %(id_cliente)s, %(nombre_completo)s, %(tipo_documento)s, %(numero_documento)s, %(correo)s,
    %(celular)s, %(credencial)s, %(version_terminos)s, %(creada_en)s, %(correo_verificado)s
)
ON CONFLICT (id_cliente) DO UPDATE SET
    nombre_completo = EXCLUDED.nombre_completo, celular = EXCLUDED.celular,
    credencial = EXCLUDED.credencial, version_terminos = EXCLUDED.version_terminos,
    correo_verificado = EXCLUDED.correo_verificado
"""

CUENTA_POR = "SELECT {columnas} FROM identidad.cuentas WHERE {campo} = %(valor)s"

GUARDAR_REGISTRO = """
INSERT INTO identidad.registros (
    id_registro, id_cliente, resumen_codigo, expira_en, reenvio_desde, intentos_restantes,
    verificado
) VALUES (
    %(id_registro)s, %(id_cliente)s, %(resumen_codigo)s, %(expira_en)s, %(reenvio_desde)s,
    %(intentos_restantes)s, %(verificado)s
)
ON CONFLICT (id_registro) DO UPDATE SET
    resumen_codigo = EXCLUDED.resumen_codigo, expira_en = EXCLUDED.expira_en,
    reenvio_desde = EXCLUDED.reenvio_desde, intentos_restantes = EXCLUDED.intentos_restantes,
    verificado = EXCLUDED.verificado
"""

GUARDAR_REFRESCO = """
INSERT INTO identidad.refrescos (resumen, id_cliente, canal, expira_en)
VALUES (%(resumen)s, %(id_cliente)s, %(canal)s, %(expira_en)s)
ON CONFLICT (resumen) DO UPDATE SET expira_en = EXCLUDED.expira_en
"""

TOMAR_REFRESCO = """
DELETE FROM identidad.refrescos WHERE resumen = %(resumen)s
RETURNING resumen, id_cliente, canal, expira_en
"""

GUARDAR_VERIFICACION = """
INSERT INTO identidad.verificaciones (id_verificacion, id_cliente, solicitada_en, datos)
VALUES (%(id_verificacion)s, %(id_cliente)s, %(solicitada_en)s, %(datos)s)
ON CONFLICT (id_verificacion) DO UPDATE SET datos = EXCLUDED.datos
"""

ULTIMA_VERIFICACION = """
SELECT datos FROM identidad.verificaciones WHERE id_cliente = %(id_cliente)s
ORDER BY solicitada_en DESC LIMIT 1
"""


class _Base:
    """Conexión del pool y preparación perezosa del esquema, como en los demás módulos."""

    def __init__(self, dsn: str, tiempo_espera_conexion: int = 2) -> None:
        self._dsn = dsn
        self._tiempo_espera = tiempo_espera_conexion
        self._preparado = False

    def _abrir(self) -> AbstractContextManager[psycopg.Connection]:
        return conexion_del_pool(self._dsn, self._tiempo_espera)

    def _conectar(self) -> AbstractContextManager[psycopg.Connection]:
        if not self._preparado:
            self.preparar()
        return self._abrir()

    def preparar(self) -> None:
        with self._abrir() as conexion:
            conexion.execute(TABLAS)
        self._preparado = True

    def verificar_conexion(self) -> None:
        with self._abrir() as conexion:
            conexion.execute("SELECT 1")


def _a_cuenta(fila: Mapping[str, object]) -> Cuenta:
    return Cuenta(
        id_cliente=str(fila["id_cliente"]),
        nombre_completo=str(fila["nombre_completo"]),
        tipo_documento=TipoDocumento(fila["tipo_documento"]),
        numero_documento=str(fila["numero_documento"]),
        correo=str(fila["correo"]),
        celular=str(fila["celular"]),
        credencial=str(fila["credencial"]),
        version_terminos=str(fila["version_terminos"]),
        creada_en=fila["creada_en"],  # type: ignore[arg-type]
        correo_verificado=bool(fila["correo_verificado"]),
    )


#: Restricciones `UNIQUE` de `identidad.cuentas` (nombre por defecto de PostgreSQL) y el
#: rechazo de negocio que corresponde a cada una.
RECHAZO_POR_RESTRICCION = {
    "cuentas_correo_key": (CorreoYaRegistradoError, "El correo ya tiene una cuenta"),
    "cuentas_numero_documento_key": (
        DocumentoYaRegistradoError,
        "El documento ya tiene una cuenta",
    ),
}


class RepositorioDeCuentasPostgres(_Base):
    def guardar(self, cuenta: Cuenta) -> None:
        """Inserta o actualiza la cuenta.

        El cerrojo de altas de `ServicioDeCuentas` solo serializa dentro de una réplica: dos
        altas simultáneas con el mismo correo o documento en réplicas distintas pasan las dos
        la comprobación previa, y la segunda choca con la restricción `UNIQUE`. Ese choque se
        traduce al mismo rechazo de negocio (409) que da la comprobación, en vez de un 500.
        """
        try:
            self._insertar_o_actualizar(cuenta)
        except psycopg.errors.UniqueViolation as error:
            rechazo = RECHAZO_POR_RESTRICCION.get(error.diag.constraint_name or "")
            if rechazo is None:
                raise
            tipo, mensaje = rechazo
            raise tipo(mensaje) from error

    def _insertar_o_actualizar(self, cuenta: Cuenta) -> None:
        with self._conectar() as conexion:
            conexion.execute(
                GUARDAR_CUENTA,
                {
                    "id_cliente": cuenta.id_cliente,
                    "nombre_completo": cuenta.nombre_completo,
                    "tipo_documento": str(cuenta.tipo_documento),
                    "numero_documento": cuenta.numero_documento,
                    "correo": cuenta.correo,
                    "celular": cuenta.celular,
                    "credencial": cuenta.credencial,
                    "version_terminos": cuenta.version_terminos,
                    "creada_en": cuenta.creada_en,
                    "correo_verificado": cuenta.correo_verificado,
                },
            )

    def _una(self, campo: str, valor: str) -> Cuenta | None:
        consulta = CUENTA_POR.format(columnas=COLUMNAS_CUENTA, campo=campo)
        with self._conectar() as conexion:
            fila = conexion.execute(consulta, {"valor": valor}).fetchone()
        return _a_cuenta(fila) if fila else None

    def por_id(self, id_cliente: str) -> Cuenta | None:
        return self._una("id_cliente", id_cliente)

    def por_correo(self, correo: str) -> Cuenta | None:
        return self._una("correo", correo)

    def por_documento(self, numero_documento: str) -> Cuenta | None:
        return self._una("numero_documento", numero_documento)

    def siguiente_id(self, anio: int) -> str:
        with self._conectar() as conexion:
            fila = conexion.execute(
                "SELECT nextval('identidad.consecutivo_cuentas') AS n"
            ).fetchone()
        return f"CLI-{anio}-{int(fila['n']):06d}"  # type: ignore[index]


class RepositorioDeRegistrosPostgres(_Base):
    def guardar(self, registro: Registro) -> None:
        with self._conectar() as conexion:
            conexion.execute(
                GUARDAR_REGISTRO,
                {
                    "id_registro": registro.id_registro,
                    "id_cliente": registro.id_cliente,
                    "resumen_codigo": registro.resumen_codigo,
                    "expira_en": registro.expira_en,
                    "reenvio_desde": registro.reenvio_desde,
                    "intentos_restantes": registro.intentos_restantes,
                    "verificado": registro.verificado,
                },
            )

    def obtener(self, id_registro: str) -> Registro | None:
        with self._conectar() as conexion:
            fila = conexion.execute(
                "SELECT * FROM identidad.registros WHERE id_registro = %(id)s",
                {"id": id_registro},
            ).fetchone()
        if not fila:
            return None
        return Registro(
            id_registro=str(fila["id_registro"]),
            id_cliente=str(fila["id_cliente"]),
            resumen_codigo=str(fila["resumen_codigo"]),
            expira_en=fila["expira_en"],
            reenvio_desde=fila["reenvio_desde"],
            intentos_restantes=int(fila["intentos_restantes"]),
            verificado=bool(fila["verificado"]),
        )

    def siguiente_id(self, anio: int) -> str:
        with self._conectar() as conexion:
            fila = conexion.execute(
                "SELECT nextval('identidad.consecutivo_registros') AS n"
            ).fetchone()
        return f"REG-{anio}-{int(fila['n']):06d}"  # type: ignore[index]


class RepositorioDeRefrescosPostgres(_Base):
    def guardar(self, refresco: Refresco) -> None:
        with self._conectar() as conexion:
            conexion.execute(
                GUARDAR_REFRESCO,
                {
                    "resumen": refresco.resumen,
                    "id_cliente": refresco.id_cliente,
                    "canal": refresco.canal,
                    "expira_en": refresco.expira_en,
                },
            )

    def tomar(self, resumen: str) -> Refresco | None:
        with self._conectar() as conexion:
            fila = conexion.execute(TOMAR_REFRESCO, {"resumen": resumen}).fetchone()
        if not fila:
            return None
        return Refresco(
            resumen=str(fila["resumen"]),
            id_cliente=str(fila["id_cliente"]),
            canal=str(fila["canal"]),
            expira_en=fila["expira_en"],
        )


def _verificacion_a_json(v: VerificacionIdentidad) -> dict[str, object]:
    return {
        "id_verificacion": v.id_verificacion,
        "id_cliente": v.id_cliente,
        "datos": {
            "nombre_completo": v.datos.nombre_completo,
            "tipo_documento": str(v.datos.tipo_documento),
            "numero_documento": v.datos.numero_documento,
        },
        "canal": v.canal,
        "solicitada_en": v.solicitada_en.isoformat(),
        "actualizada_en": v.actualizada_en.isoformat(),
        "estado": str(v.estado),
        "proveedor": v.proveedor,
        "referencia_proveedor": v.referencia_proveedor,
        "motivo": str(v.motivo) if v.motivo else None,
        "reintento_permitido": v.reintento_permitido,
    }


def _verificacion_de_json(d: Mapping[str, object]) -> VerificacionIdentidad:
    datos = d["datos"]
    assert isinstance(datos, Mapping)
    motivo = d.get("motivo")
    return VerificacionIdentidad(
        id_verificacion=str(d["id_verificacion"]),
        id_cliente=str(d["id_cliente"]),
        datos=DatosDeIdentidad(
            nombre_completo=str(datos["nombre_completo"]),
            tipo_documento=TipoDocumento(datos["tipo_documento"]),
            numero_documento=str(datos["numero_documento"]),
        ),
        canal=str(d["canal"]),
        solicitada_en=datetime.fromisoformat(str(d["solicitada_en"])),
        actualizada_en=datetime.fromisoformat(str(d["actualizada_en"])),
        estado=EstadoVerificacion(d["estado"]),
        proveedor=str(d["proveedor"]),
        referencia_proveedor=d.get("referencia_proveedor"),  # type: ignore[arg-type]
        motivo=MotivoRechazo(motivo) if motivo else None,
        reintento_permitido=d.get("reintento_permitido"),  # type: ignore[arg-type]
    )


class RepositorioDeVerificacionesPostgres(_Base):
    def guardar(self, verificacion: VerificacionIdentidad) -> None:
        with self._conectar() as conexion:
            conexion.execute(
                GUARDAR_VERIFICACION,
                {
                    "id_verificacion": verificacion.id_verificacion,
                    "id_cliente": verificacion.id_cliente,
                    "solicitada_en": verificacion.solicitada_en,
                    "datos": Jsonb(_verificacion_a_json(verificacion)),
                },
            )

    def quitar(self, id_verificacion: str) -> None:
        with self._conectar() as conexion:
            conexion.execute(
                "DELETE FROM identidad.verificaciones WHERE id_verificacion = %(id)s",
                {"id": id_verificacion},
            )

    def ultima_del_cliente(self, id_cliente: str) -> VerificacionIdentidad | None:
        with self._conectar() as conexion:
            fila = conexion.execute(ULTIMA_VERIFICACION, {"id_cliente": id_cliente}).fetchone()
        return _verificacion_de_json(fila["datos"]) if fila else None
