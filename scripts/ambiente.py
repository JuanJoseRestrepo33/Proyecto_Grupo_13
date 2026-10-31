#!/usr/bin/env python3
"""Ambiente de Google Cloud de Solventa: verificarlo, aprovisionarlo o destruirlo (WEB-F01, #165).

- `verificar`: solo lee. Lo corre el CD en cada despliegue y avisa (::warning::) lo que falta o
  está apagado, en segundos, sin crear nada.
- `aprovisionar`: crea lo que falta, en orden de dependencia, y **omite lo que ya existe**. Correrlo
  dos veces deja lo mismo. Lo que más tarda es crear Cloud SQL (unos 10 minutos); si ya existe, se
  omite y sigue con lo demás.
- `destruir`: borra todo lo que cuesta o guarda datos (servicios, Cloud SQL, secretos, bucket e
  imágenes) **menos el acceso**: Workload Identity, las cuentas de servicio y el repositorio de
  Artifact Registry se conservan, para poder volver a aprovisionar desde GitHub.

Nunca imprime contraseñas, llaves ni cadenas de conexión: se generan en memoria y llegan a Secret
Manager por la entrada estándar de gcloud.

Uso: ambiente.py {verificar,aprovisionar,destruir} --proyecto P --region R [--confirmar P]
"""

from __future__ import annotations

import argparse
import json
import os
import secrets
import shutil
import subprocess
import sys
import tempfile
import urllib.parse
from collections.abc import Callable, Mapping
from dataclasses import dataclass, field
from datetime import UTC, datetime

SERVICIOS = ("solventa-nucleo", "solventa-bff", "solventa-web")
CUENTAS = {"solventa-nucleo": "run-nucleo", "solventa-bff": "run-bff", "solventa-web": "run-web"}
REPOSITORIO = "solventa"
# Cloud SQL no deja reutilizar el nombre de una instancia borrada durante una semana: cada instancia
# nueva lleva la fecha, y se reconoce por el prefijo.
PREFIJO_INSTANCIA = "solventa-"
BASE = "solventa"
# El dueño de la base se crea con gcloud; los demás, con SQL y sin cloudsqlsuperuser.
DUENO = "auditoria"
MODULOS_BD = ("auditoria", "cotizacion", "siniestros")
SECRETOS_NUCLEO = {
    "AUDITORIA_DATABASE_URL": "auditoria-database-url",
    "COTIZACION_DATABASE_URL": "cotizacion-database-url",
    "SINIESTROS_DATABASE_URL": "siniestros-database-url",
    "TOKENS_LLAVE_PRIVADA": "tokens-llave-privada",
    "TOKENS_LLAVE_PUBLICA": "tokens-llave-publica",
    "HUMO_CORREO": "humo-correo",
    "HUMO_CLAVE": "humo-clave",
    "IDENTIDAD_OTP_FIJO": "identidad-otp-fijo",
}
SECRETOS_BFF = {"TOKENS_LLAVE_PUBLICA": "tokens-llave-publica"}
# El original de la cuenta de prueba: solo lo leen los administradores.
SECRETO_CUENTA_HUMO = "humo-cuenta-staging"
SECRETOS = (*dict.fromkeys(SECRETOS_NUCLEO.values()), SECRETO_CUENTA_HUMO)
SEMILLA_POLIZAS = "/opt/solventa/semillas/polizas-staging.json"
# Imagen de relleno del primer despliegue; el CD la reemplaza por la real.
IMAGEN_INICIAL = "us-docker.pkg.dev/cloudrun/container/hello"
CICLO_BUCKET = {"rule": [{"action": {"type": "Delete"}, "condition": {"age": 30}}]}


class GcloudError(RuntimeError):
    """Un comando de gcloud falló. El mensaje nunca incluye sus argumentos completos."""


class FaltaAccesoError(RuntimeError):
    """Falta algo del acceso, que este script no crea (docs/devops.md, pasos 3 y 4)."""


@dataclass(frozen=True)
class Salida:
    codigo: int
    texto: str = ""
    error: str = ""


Ejecutar = Callable[[list[str], str | None], Salida]


def ejecutar_gcloud(args: list[str], entrada: str | None = None) -> Salida:
    # En Windows gcloud es gcloud.cmd; en los runners, un ejecutable en el PATH.
    gcloud = shutil.which("gcloud") or "gcloud"
    r = subprocess.run([gcloud, *args], input=entrada, capture_output=True, text=True)
    return Salida(r.returncode, r.stdout, r.stderr)


# Cómo dice gcloud que un recurso no existe, en minúsculas. Comprobado con `describe` de solo
# lectura sobre recursos inexistentes:
#   secrets, iam service-accounts, artifacts → "NOT_FOUND: …"
#   run services                             → "Cannot find service [x]"
#   storage buckets                          → "gs://x not found: 404."
#   sql databases                            → "HTTPError 404: …"
# Cualquier otro fallo (permiso, cuota, 5xx, instancia de Cloud SQL apagada) no dice que el recurso
# falte, y actuar como si faltara podría reemplazar uno real.
NO_ENCONTRADO = ("not_found", "cannot find service", "not found: 404", "httperror 404")


def no_encontrado(error: str) -> bool:
    error = error.lower()
    return any(marcador in error for marcador in NO_ENCONTRADO)


class Gcloud:
    def __init__(self, proyecto: str, ejecutar: Ejecutar = ejecutar_gcloud) -> None:
        self.proyecto = proyecto
        self._ejecutar = ejecutar

    def intentar(self, *args: str, entrada: str | None = None) -> Salida:
        return self._ejecutar([*args, f"--project={self.proyecto}", "--quiet"], entrada)

    @staticmethod
    def fallo(args: tuple[str, ...], salida: Salida) -> GcloudError:
        # Solo el comando, nunca sus valores: pueden llevar contraseñas.
        ultima = (salida.error.strip().splitlines() or ["sin detalle"])[-1]
        return GcloudError(f"gcloud {' '.join(args[:3])}: {ultima[:300]}")

    def __call__(self, *args: str, entrada: str | None = None, tolerar: str = "") -> str:
        salida = self.intentar(*args, entrada=entrada)
        if salida.codigo and not (tolerar and tolerar in salida.error.lower()):
            raise self.fallo(args, salida)
        return salida.texto.strip()

    def existe(self, *args: str) -> bool:
        """True si existe; False solo si gcloud dice que no existe. Otro fallo es un error."""
        salida = self.intentar(*args)
        if salida.codigo == 0:
            return True
        if no_encontrado(salida.error):
            return False
        raise self.fallo(args, salida)


@dataclass
class Informe:
    lineas: list[str] = field(default_factory=list)
    avisos: list[str] = field(default_factory=list)

    def existe(self, recurso: str) -> None:
        self._decir(f"✓ {recurso}: ya existe, se omite")

    def creado(self, recurso: str) -> None:
        self._decir(f"+ {recurso}: creado")

    def borrado(self, recurso: str) -> None:
        self._decir(f"- {recurso}: borrado")

    def ausente(self, recurso: str) -> None:
        self._decir(f"· {recurso}: no existe")

    def nota(self, texto: str) -> None:
        self._decir(f"  {texto}")

    def aviso(self, texto: str) -> None:
        self.avisos.append(texto)
        self._decir(f"::warning::{texto}")

    def _decir(self, linea: str) -> None:
        self.lineas.append(linea)
        print(linea, flush=True)


Conectar = Callable[[str, str, str], object]


def conectar_postgres(conexion: str, usuario: str, clave: str):  # pragma: no cover - red real
    from google.cloud.sql.connector import Connector

    conector = Connector()
    con = conector.connect(conexion, "pg8000", user=usuario, password=clave, db=BASE)
    con.autocommit = True
    return con


def generar_par_de_llaves() -> tuple[str, str]:  # pragma: no cover - usa openssl
    privada = subprocess.run(
        ["openssl", "genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-256"],
        capture_output=True,
        text=True,
        check=True,
    ).stdout
    return privada, publica_de(privada)


def publica_de(privada: str) -> str:  # pragma: no cover - usa openssl
    return subprocess.run(
        ["openssl", "pkey", "-pubout"], input=privada, capture_output=True, text=True, check=True
    ).stdout


@dataclass
class Ambiente:
    gcloud: Gcloud
    region: str
    informe: Informe = field(default_factory=Informe)
    entorno: Mapping[str, str] = field(default_factory=lambda: os.environ)
    # Inyectables para las pruebas; sin ellos, la base, openssl y el reloj reales.
    conectar: Conectar | None = None
    generar_llaves: Callable[[], tuple[str, str]] | None = None
    ahora: Callable[[], datetime] | None = None
    servicios_creados: list[str] = field(default_factory=list)
    # Servicios que ya existían y leen un secreto que recibió una versión nueva: Cloud Run lee
    # `latest` al arrancar, así que sin una revisión nueva seguirían con el valor anterior.
    reiniciar: set[str] = field(default_factory=set)

    @property
    def proyecto(self) -> str:
        return self.gcloud.proyecto

    @property
    def bucket(self) -> str:
        return f"{self.proyecto}-solventa-evidencias"

    def cuenta(self, nombre: str) -> str:
        return f"{nombre}@{self.proyecto}.iam.gserviceaccount.com"

    def conexion(self, instancia: str) -> str:
        return f"{self.proyecto}:{self.region}:{instancia}"

    # --- Consultas -------------------------------------------------------------------------------

    def instancias(self) -> list[dict]:
        texto = self.gcloud(
            "sql", "instances", "list", "--format=json(name,state,settings.activationPolicy)"
        )
        todas = json.loads(texto or "[]")
        return [i for i in todas if i["name"].startswith(PREFIJO_INSTANCIA)]

    def instancia(self) -> dict | None:
        return next(iter(sorted(self.instancias(), key=lambda i: i["name"])), None)

    def secreto_existe(self, nombre: str) -> bool:
        return self.gcloud.existe("secrets", "versions", "describe", "latest", f"--secret={nombre}")

    def instancia_del_secreto(self, nombre: str) -> str | None:
        """La etiqueta `instancia` del secreto: "" si no la tiene y None si el secreto no existe."""
        args = ("secrets", "describe", nombre, "--format=value(labels.instancia)")
        salida = self.gcloud.intentar(*args)
        if salida.codigo == 0:
            return salida.texto.strip()
        if no_encontrado(salida.error):
            return None
        raise self.gcloud.fallo(args, salida)

    def servicio_existe(self, servicio: str) -> bool:
        return self.gcloud.existe(
            "run", "services", "describe", servicio, f"--region={self.region}"
        )

    def url(self, servicio: str) -> str:
        return self.gcloud(
            "run", "services", "describe", servicio, f"--region={self.region}",
            "--format=value(status.url)",
        )  # fmt: skip

    # --- Escrituras ------------------------------------------------------------------------------

    def agregar_version(self, nombre: str, valor: str, etiquetas: str = "") -> None:
        """Crea el secreto si falta y le agrega una versión, por la entrada estándar."""
        if not self.gcloud.existe("secrets", "describe", nombre):
            self.gcloud("secrets", "create", nombre, "--replication-policy=automatic")
        self.gcloud("secrets", "versions", "add", nombre, "--data-file=-", entrada=valor)
        if etiquetas:
            self.gcloud("secrets", "update", nombre, f"--update-labels={etiquetas}")

    def esperar(self, operacion: str) -> None:
        self.gcloud("sql", "operations", "wait", operacion, "--timeout=1800")

    # --- Verificar -------------------------------------------------------------------------------

    def verificar(self) -> int:
        faltan = 0
        for cuenta in CUENTAS.values():
            if not self.gcloud.existe("iam", "service-accounts", "describe", self.cuenta(cuenta)):
                faltan += 1
                self.informe.aviso(f"Falta la cuenta de servicio {cuenta} (acceso, paso 4)")
        instancia = self.instancia()
        if instancia is None:
            faltan += 1
            self.informe.aviso("No hay instancia de Cloud SQL: corre el workflow Aprovisionar")
        elif instancia["settings"]["activationPolicy"] != "ALWAYS":
            self.informe.aviso(
                f"Cloud SQL {instancia['name']} está apagada: el núcleo no persiste nada"
            )
        else:
            self.informe.existe(f"Cloud SQL {instancia['name']}")
        for nombre in SECRETOS:
            if not self.secreto_existe(nombre):
                faltan += 1
                self.informe.aviso(f"Falta el secreto {nombre}: corre el workflow Aprovisionar")
        if instancia is not None:
            for modulo in MODULOS_BD:
                secreto = f"{modulo}-database-url"
                etiqueta = self.instancia_del_secreto(secreto)
                if etiqueta and etiqueta != instancia["name"]:
                    faltan += 1
                    self.informe.aviso(f"{secreto} apunta a {etiqueta}, no a {instancia['name']}")
        if not self.gcloud.existe("storage", "buckets", "describe", f"gs://{self.bucket}"):
            faltan += 1
            self.informe.aviso("Falta el bucket de evidencias: corre el workflow Aprovisionar")
        for servicio in SERVICIOS:
            if not self.servicio_existe(servicio):
                faltan += 1
                self.informe.aviso(f"Falta el servicio {servicio}: corre el workflow Aprovisionar")
        if not faltan:
            self.informe.nota("Ambiente completo")
        return faltan

    # --- Aprovisionar ----------------------------------------------------------------------------

    def aprovisionar(self) -> None:
        self._acceso()
        instancia = self._instancia()
        self._base(instancia)
        self._usuarios(instancia)
        self._llaves()
        self._cuenta_de_prueba()
        self._accesos_a_secretos()
        self._bucket()
        self._servicios(instancia)

    def _acceso(self) -> None:
        for cuenta in CUENTAS.values():
            if not self.gcloud.existe("iam", "service-accounts", "describe", self.cuenta(cuenta)):
                raise FaltaAccesoError(f"Falta la cuenta {cuenta}; ver docs/devops.md, paso 4")
        if not self.gcloud.existe(
            "artifacts", "repositories", "describe", REPOSITORIO, f"--location={self.region}"
        ):
            raise FaltaAccesoError(f"Falta el repositorio {REPOSITORIO} de Artifact Registry")
        self.informe.existe("Cuentas de ejecución y repositorio de imágenes")

    def _instancia(self) -> str:
        instancia = self.instancia()
        if instancia is None:
            ahora = self.ahora() if self.ahora else datetime.now(UTC)
            nombre = f"{PREFIJO_INSTANCIA}{ahora:%Y%m%d-%H%M}"
            self.informe.nota(f"Creando Cloud SQL {nombre} (unos 10 minutos)…")
            operacion = self.gcloud(
                "sql", "instances", "create", nombre, "--database-version=POSTGRES_15",
                "--tier=db-f1-micro", "--edition=ENTERPRISE", f"--region={self.region}",
                "--storage-size=10", "--availability-type=zonal", "--no-backup",
                "--async", "--format=value(name)",
            )  # fmt: skip
            self.esperar(operacion)
            self.informe.creado(f"Cloud SQL {nombre}")
            return nombre
        nombre = instancia["name"]
        if instancia["settings"]["activationPolicy"] != "ALWAYS":
            operacion = self.gcloud(
                "sql", "instances", "patch", nombre, "--activation-policy=ALWAYS",
                "--async", "--format=value(name)",
            )  # fmt: skip
            self.esperar(operacion)
            self.informe.nota(f"Cloud SQL {nombre} estaba apagada: encendida")
        else:
            self.informe.existe(f"Cloud SQL {nombre}")
        return nombre

    def _base(self, instancia: str) -> None:
        if self.gcloud.existe("sql", "databases", "describe", BASE, f"--instance={instancia}"):
            self.informe.existe(f"Base {BASE}")
            return
        self.gcloud("sql", "databases", "create", BASE, f"--instance={instancia}")
        self.informe.creado(f"Base {BASE}")

    def _cadena(self, usuario: str, clave: str, instancia: str) -> str:
        socket = f"/cloudsql/{self.conexion(instancia)}"
        return f"postgresql://{usuario}:{clave}@/{BASE}?host={socket}"

    def _usuarios(self, instancia: str) -> None:
        """Un usuario y un secreto por módulo; solo se rehacen los de otra instancia."""
        pendientes = [m for m in MODULOS_BD if not self._cadena_vigente(m, instancia)]
        for modulo in MODULOS_BD:
            if modulo not in pendientes:
                self.informe.existe(f"Usuario {modulo} y su secreto")
        if not pendientes:
            return
        if DUENO in pendientes:
            clave_dueno = secrets.token_urlsafe(32)
            usuarios = self.gcloud(
                "sql", "users", "list", f"--instance={instancia}", "--format=value(name)"
            ).split()
            verbo = "set-password" if DUENO in usuarios else "create"
            self.gcloud(
                "sql", "users", verbo, DUENO, f"--instance={instancia}", f"--password={clave_dueno}"
            )
            self._guardar_cadena(DUENO, clave_dueno, instancia)
        else:
            cadena = self.gcloud(
                "secrets", "versions", "access", "latest", f"--secret={DUENO}-database-url"
            )
            clave_dueno = urllib.parse.unquote(urllib.parse.urlsplit(cadena).password or "")
        otros = [m for m in pendientes if m != DUENO]
        if not otros:
            return
        conectar = self.conectar or conectar_postgres
        con = conectar(self.conexion(instancia), DUENO, clave_dueno)
        try:
            cursor = con.cursor()
            for modulo in otros:
                clave = secrets.token_urlsafe(32)
                cursor.execute("SELECT 1 FROM pg_roles WHERE rolname = %s", (modulo,))
                verbo = "ALTER" if cursor.fetchone() else "CREATE"
                # token_urlsafe solo usa [A-Za-z0-9_-]: seguro entre comillas simples.
                cursor.execute(f"{verbo} ROLE {modulo} LOGIN PASSWORD '{clave}'")
                cursor.execute(f"GRANT CONNECT ON DATABASE {BASE} TO {modulo}")
                # Solo para que el núcleo cree su esquema al arrancar (CREATE SCHEMA IF NOT EXISTS).
                cursor.execute(f"GRANT CREATE ON DATABASE {BASE} TO {modulo}")
                self._guardar_cadena(modulo, clave, instancia)
        finally:
            con.close()

    def _cadena_vigente(self, modulo: str, instancia: str) -> bool:
        """El secreto del módulo apunta a esta instancia.

        Uno sin etiqueta (creado a mano) cuya cadena ya usa el socket de esta instancia se adopta:
        se etiqueta sin rotar la contraseña. La cadena se lee pero nunca se imprime.
        """
        secreto = f"{modulo}-database-url"
        etiqueta = self.instancia_del_secreto(secreto)
        if etiqueta == instancia:
            return True
        if etiqueta != "":  # falta, o es de otra instancia
            return False
        args = ("secrets", "versions", "access", "latest", f"--secret={secreto}")
        salida = self.gcloud.intentar(*args)
        if salida.codigo != 0:
            if no_encontrado(salida.error):  # existe, pero sin versiones
                return False
            raise self.gcloud.fallo(args, salida)
        consulta = urllib.parse.parse_qs(urllib.parse.urlsplit(salida.texto.strip()).query)
        if consulta.get("host") != [f"/cloudsql/{self.conexion(instancia)}"]:
            return False
        self.gcloud("secrets", "update", secreto, f"--update-labels=instancia={instancia}")
        self.informe.nota(f"{secreto} ya apuntaba a {instancia}: se etiqueta sin rotar la clave")
        return True

    def _guardar_cadena(self, modulo: str, clave: str, instancia: str) -> None:
        self.agregar_version(
            f"{modulo}-database-url",
            self._cadena(modulo, clave, instancia),
            etiquetas=f"instancia={instancia}",
        )
        self.reiniciar.add("solventa-nucleo")
        self.informe.creado(f"Usuario {modulo} y su secreto")

    def _llaves(self) -> None:
        privada_existe = self.secreto_existe("tokens-llave-privada")
        publica_existe = self.secreto_existe("tokens-llave-publica")
        if privada_existe and publica_existe:
            self.informe.existe("Llaves de firma de tokens")
            return
        if privada_existe:
            privada = self.gcloud(
                "secrets", "versions", "access", "latest", "--secret=tokens-llave-privada"
            )
            publica = publica_de(privada)
        else:
            # Un par nuevo: los tokens emitidos con el anterior dejan de valer.
            privada, publica = (self.generar_llaves or generar_par_de_llaves)()
            self.agregar_version("tokens-llave-privada", privada)
        self.agregar_version("tokens-llave-publica", publica)
        self.reiniciar.update({"solventa-nucleo", "solventa-bff"})
        self.informe.creado("Llaves de firma de tokens")

    def _cuenta_de_prueba(self) -> None:
        correo = self.entorno.get("HUMO_CORREO", "")
        clave = self.entorno.get("HUMO_CLAVE", "")
        valores = {
            SECRETO_CUENTA_HUMO: json.dumps({"correo": correo, "clave": clave}),
            "humo-correo": correo,
            "humo-clave": clave,
        }
        for nombre, valor in valores.items():
            if self.secreto_existe(nombre):
                self.informe.existe(f"Secreto {nombre}")
            elif not (correo and clave):
                self.informe.aviso(f"Sin HUMO_CORREO y HUMO_CLAVE no se crea {nombre}")
            else:
                self.agregar_version(nombre, valor)
                self.informe.creado(f"Secreto {nombre}")
        if self.secreto_existe("identidad-otp-fijo"):
            self.informe.existe("Secreto identidad-otp-fijo")
        else:
            # 6 dígitos al azar, no un 123456 adivinable.
            self.agregar_version("identidad-otp-fijo", f"{secrets.randbelow(10**6):06d}")
            self.informe.creado("Secreto identidad-otp-fijo")

    def _accesos_a_secretos(self) -> None:
        """Acceso por secreto, no por proyecto. Agregar un permiso que ya existe no cambia nada."""
        accesos = [(s, "run-nucleo") for s in dict.fromkeys(SECRETOS_NUCLEO.values())]
        accesos += [(s, "run-bff") for s in SECRETOS_BFF.values()]
        for secreto, cuenta in accesos:
            if self.secreto_existe(secreto):
                self.gcloud(
                    "secrets", "add-iam-policy-binding", secreto,
                    f"--member=serviceAccount:{self.cuenta(cuenta)}",
                    "--role=roles/secretmanager.secretAccessor",
                )  # fmt: skip
        self.informe.nota("Accesos a los secretos al día")

    def _bucket(self) -> None:
        """Crea el bucket si falta y, exista o no, repara su ciclo de vida y sus permisos."""
        destino = f"gs://{self.bucket}"
        creado = not self.gcloud.existe("storage", "buckets", "describe", destino)
        if creado:
            self.gcloud(
                "storage", "buckets", "create", destino, f"--location={self.region}",
                "--default-storage-class=STANDARD", "--uniform-bucket-level-access",
                "--public-access-prevention",
            )  # fmt: skip
        reparado = self._configurar_bucket(destino)
        if creado:
            self.informe.creado(f"Bucket {self.bucket}")
        elif reparado:
            self.informe.nota(f"Bucket {self.bucket}: ya existía; se completó {reparado}")
        else:
            self.informe.existe(f"Bucket {self.bucket}")

    def _configurar_bucket(self, destino: str) -> str:
        """Aplica solo lo que falte; devuelve qué se completó, o "" si ya estaba."""
        completado = []
        formato = "--format=json(lifecycle_config)"
        actual = json.loads(self.gcloud("storage", "buckets", "describe", destino, formato) or "{}")
        if actual.get("lifecycle_config") != CICLO_BUCKET:
            with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as ciclo:
                json.dump(CICLO_BUCKET, ciclo)
            try:
                self.gcloud(
                    "storage", "buckets", "update", destino, f"--lifecycle-file={ciclo.name}",
                    "--update-labels=historia=mob-f05",
                )  # fmt: skip
            finally:
                os.unlink(ciclo.name)
            completado.append("el ciclo de vida")
        politica = json.loads(
            self.gcloud("storage", "buckets", "get-iam-policy", destino, "--format=json") or "{}"
        )
        miembro = f"serviceAccount:{self.cuenta('run-nucleo')}"
        # Crear y leer, sin borrar: la evidencia de un siniestro no se elimina desde la aplicación.
        for rol in ("roles/storage.objectCreator", "roles/storage.objectViewer"):
            if any(
                b.get("role") == rol and miembro in b.get("members", [])
                for b in politica.get("bindings", [])
            ):
                continue
            self.gcloud(
                "storage", "buckets", "add-iam-policy-binding", destino,
                f"--member={miembro}", f"--role={rol}",
            )  # fmt: skip
            completado.append(rol)
        return ", ".join(completado)

    def _servicios(self, instancia: str) -> None:
        conexion = self.conexion(instancia)
        for servicio in SERVICIOS:
            if self.servicio_existe(servicio):
                self.informe.existe(f"Servicio {servicio}")
                self._reconciliar(servicio, conexion)
                continue
            self.gcloud(*self._primer_despliegue(servicio, conexion))
            self.servicios_creados.append(servicio)
            self.informe.creado(f"Servicio {servicio} (imagen de relleno hasta el próximo CD)")

    def _configuracion_nucleo(self) -> tuple[dict[str, str], dict[str, str]]:
        """Secretos (variable → secreto) y variables que debe tener el núcleo."""
        montados = {v: s for v, s in SECRETOS_NUCLEO.items() if self.secreto_existe(s)}
        variables = {
            "ALMACENAMIENTO_EVIDENCIAS": "gcs",
            "EVIDENCIAS_BUCKET": self.bucket,
            "POLIZAS_SEMILLA": SEMILLA_POLIZAS,
        }
        return montados, variables

    def _reconciliar(self, servicio: str, conexion: str) -> None:
        """Agrega al servicio existente lo que le falte, sin tocar lo demás (`--update-*`).

        Si no falta nada, no lo actualiza: cada actualización crea una revisión nueva.
        """
        secretos: dict[str, str] = {}
        variables: dict[str, str] = {}
        otros: list[str] = []
        if servicio == "solventa-nucleo":
            descripcion = self.gcloud(
                "run", "services", "describe", servicio, f"--region={self.region}",
                "--format=json(spec.template)",
            )  # fmt: skip
            plantilla = json.loads(descripcion or "{}").get("spec", {}).get("template", {})
            contenedor = (plantilla.get("spec", {}).get("containers") or [{}])[0]
            actuales = {e["name"]: e for e in contenedor.get("env", [])}
            esperados, valores = self._configuracion_nucleo()
            for variable, secreto in esperados.items():
                referencia = actuales.get(variable, {}).get("valueFrom", {}).get("secretKeyRef", {})
                if referencia.get("name") != secreto:
                    secretos[variable] = secreto
            for variable, valor in valores.items():
                if actuales.get(variable, {}).get("value") != valor:
                    variables[variable] = valor
            anotaciones = plantilla.get("metadata", {}).get("annotations", {})
            if anotaciones.get("run.googleapis.com/cloudsql-instances") != conexion:
                otros.append(f"--set-cloudsql-instances={conexion}")
        if servicio in self.reiniciar:
            # Una revisión nueva para que lea la versión nueva de sus secretos.
            ahora = self.ahora() if self.ahora else datetime.now(UTC)
            variables["CREDENCIALES_ROTADAS"] = f"{ahora:%Y%m%dT%H%M%SZ}"
        if not (secretos or variables or otros):
            return
        args = ["run", "services", "update", servicio, f"--region={self.region}", *otros]
        if secretos:
            args.append(
                "--update-secrets=" + ",".join(f"{v}={s}:latest" for v, s in secretos.items())
            )
        if variables:
            args.append("--update-env-vars=" + ",".join(f"{v}={x}" for v, x in variables.items()))
        self.gcloud(*args)
        cambios = [*secretos, *variables, *(["Cloud SQL"] if otros else [])]
        self.informe.nota(f"{servicio}: se actualizó {', '.join(cambios)}")

    def _primer_despliegue(self, servicio: str, conexion: str) -> list[str]:
        """El primer despliegue fija la cuenta, las variables y los secretos; el CD los conserva."""
        args = [
            "run", "deploy", servicio, f"--image={IMAGEN_INICIAL}", f"--region={self.region}",
            f"--service-account={self.cuenta(CUENTAS[servicio])}", "--allow-unauthenticated",
        ]  # fmt: skip
        if servicio == "solventa-nucleo":
            montados, variables = self._configuracion_nucleo()
            # Una sola instancia mientras haya módulos con estado en memoria.
            args += [
                "--min-instances=1",
                "--max-instances=1",
                f"--add-cloudsql-instances={conexion}",
            ]
        elif servicio == "solventa-bff":
            montados = SECRETOS_BFF
            variables = {"NUCLEO_URL": self.url("solventa-nucleo")}
        else:
            montados, variables = {}, {"BFF_URL": self.url("solventa-bff")}
        if montados:
            args.append("--set-secrets=" + ",".join(f"{v}={s}:latest" for v, s in montados.items()))
        args.append("--set-env-vars=" + ",".join(f"{v}={x}" for v, x in variables.items()))
        return args

    # --- Destruir --------------------------------------------------------------------------------

    def destruir(self) -> None:
        """Borra en orden inverso. Lo que ya no existe se omite; el acceso se conserva."""
        for servicio in reversed(SERVICIOS):
            if self.servicio_existe(servicio):
                self.gcloud("run", "services", "delete", servicio, f"--region={self.region}")
                self.informe.borrado(f"Servicio {servicio}")
            else:
                self.informe.ausente(f"Servicio {servicio}")
        instancias = self.instancias()
        for instancia in instancias:
            operacion = self.gcloud(
                "sql", "instances", "delete", instancia["name"], "--async", "--format=value(name)"
            )
            self.esperar(operacion)
            self.informe.borrado(f"Cloud SQL {instancia['name']}")
        if not instancias:
            self.informe.ausente("Cloud SQL")
        for nombre in SECRETOS:
            if self.gcloud.existe("secrets", "describe", nombre):
                self.gcloud("secrets", "delete", nombre)
                self.informe.borrado(f"Secreto {nombre}")
            else:
                self.informe.ausente(f"Secreto {nombre}")
        destino = f"gs://{self.bucket}"
        if self.gcloud.existe("storage", "buckets", "describe", destino):
            # Borra los objetos y después el bucket.
            self.gcloud("storage", "rm", "--recursive", destino)
            self.informe.borrado(f"Bucket {self.bucket}")
        else:
            self.informe.ausente(f"Bucket {self.bucket}")
        for servicio in SERVICIOS:
            ubicacion = (f"--repository={REPOSITORIO}", f"--location={self.region}")
            if self.gcloud.existe("artifacts", "packages", "describe", servicio, *ubicacion):
                self.gcloud("artifacts", "packages", "delete", servicio, *ubicacion)
                self.informe.borrado(f"Imágenes de {servicio}")
            else:
                self.informe.ausente(f"Imágenes de {servicio}")
        self.informe.nota(
            "Se conservan Workload Identity, las cuentas de servicio y el repositorio de imágenes"
        )


def main(argv: list[str] | None = None, ambiente: Ambiente | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("accion", choices=["verificar", "aprovisionar", "destruir"])
    parser.add_argument("--proyecto", required=True)
    parser.add_argument("--region", required=True)
    parser.add_argument("--confirmar", default="", help="destruir: el ID del proyecto")
    parser.add_argument(
        "--github-output", default=os.environ.get("GITHUB_OUTPUT", ""), help=argparse.SUPPRESS
    )
    args = parser.parse_args(argv)
    ambiente = ambiente or Ambiente(Gcloud(args.proyecto), args.region)

    try:
        if args.accion == "verificar":
            ambiente.verificar()
        elif args.accion == "aprovisionar":
            ambiente.aprovisionar()
            if args.github_output:
                with open(args.github_output, "a", encoding="utf-8") as salida:
                    creados = ",".join(ambiente.servicios_creados)
                    salida.write(f"servicios_creados={creados}\n")
        else:
            if args.confirmar != args.proyecto:
                print("::error::Para destruir, --confirmar debe ser el ID del proyecto.")
                return 2
            ambiente.destruir()
    except (GcloudError, FaltaAccesoError) as error:
        if args.accion == "verificar":
            # Verificar solo avisa: un fallo al consultar no debe poner el CD en rojo.
            print(f"::warning::No se pudo verificar el ambiente: {error}")
            return 0
        print(f"::error::{error}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
