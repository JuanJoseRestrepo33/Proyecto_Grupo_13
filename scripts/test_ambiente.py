import json
from datetime import UTC, datetime

import pytest

import ambiente as amb

PROYECTO = "proyecto-prueba"
REGION = "us-east1"
CONEXION_VIEJA = f"{PROYECTO}:{REGION}:solventa-auditoria"
BUCKET = f"gs://{PROYECTO}-solventa-evidencias"
ROLES_BUCKET = ("roles/storage.objectCreator", "roles/storage.objectViewer")


class NubeSimulada:
    """Interpreta los comandos de gcloud que usa ambiente.py sobre un estado en memoria.

    Los errores de "no encontrado" copian el texto real de cada comando de gcloud. `fallas` permite
    que un comando responda otro error (403, 503, instancia apagada) para probar que no se toma
    como "no existe".
    """

    # El texto real de gcloud para un recurso que no existe, por comando.
    NO_EXISTE = {
        ("iam", "service-accounts"): "NOT_FOUND: Unknown service account.",
        ("artifacts", "repositories"): "NOT_FOUND: Requested entity was not found.",
        ("artifacts", "packages"): "NOT_FOUND: Requested entity was not found.",
        ("sql", "databases"): "HTTPError 404: The Cloud SQL database does not exist.",
        ("secrets", "describe"): "NOT_FOUND: Secret [projects/1/secrets/x] not found.",
        (
            "secrets",
            "versions",
        ): "NOT_FOUND: Secret [projects/1/secrets/x] not found or has no versions.",
        ("storage", "buckets"): "gs://x not found: 404.",
        ("run", "services"): "Cannot find service [x]",
    }

    def __init__(self, completa: bool = False) -> None:
        self.llamadas: list[list[str]] = []
        self.valores: list[str] = []  # todo lo que llegó por stdin: nunca debe imprimirse
        self.fallas: dict[tuple[str, ...], str] = {}
        self.cuentas = {f"{c}@{PROYECTO}.iam.gserviceaccount.com" for c in amb.CUENTAS.values()}
        self.repositorio = True
        self.instancias: dict[str, str] = {}  # nombre → política de activación
        self.bases: set[tuple[str, str]] = set()
        self.usuarios: set[tuple[str, str]] = set()
        self.secretos: dict[str, dict] = {}
        self.buckets: dict[str, dict] = {}  # gs://… → ciclo de vida y roles de run-nucleo
        self.servicios: dict[str, dict] = {}
        self.paquetes: set[str] = set()
        if completa:
            self.completar()

    def completar(self, instancia: str = "solventa-auditoria") -> None:
        self.instancias[instancia] = "ALWAYS"
        self.bases.add((instancia, amb.BASE))
        self.usuarios.add((instancia, amb.DUENO))
        for nombre in amb.SECRETOS:
            self.secretos[nombre] = {"versiones": ["valor"], "instancia": None}
        for modulo in amb.MODULOS_BD:
            cadena = (
                f"postgresql://{modulo}:clave-{modulo}@/solventa"
                f"?host=/cloudsql/{PROYECTO}:{REGION}:{instancia}"
            )
            self.secretos[f"{modulo}-database-url"] = {
                "versiones": [cadena],
                "instancia": instancia,
            }
        self.buckets[BUCKET] = {"ciclo": amb.CICLO_BUCKET, "roles": set(ROLES_BUCKET)}
        conexion = f"{PROYECTO}:{REGION}:{instancia}"
        nucleo = {v: ("secreto", s) for v, s in amb.SECRETOS_NUCLEO.items()}
        nucleo.update(
            ALMACENAMIENTO_EVIDENCIAS="gcs",
            EVIDENCIAS_BUCKET=BUCKET[5:],
            POLIZAS_SEMILLA=amb.SEMILLA_POLIZAS,
        )
        self.servicios = {
            "solventa-nucleo": {"cloudsql": conexion, "env": nucleo},
            "solventa-bff": {"cloudsql": None, "env": {}},
            "solventa-web": {"cloudsql": None, "env": {}},
        }
        self.paquetes.update(amb.SERVICIOS)

    def comandos(self, *prefijo: str) -> list[list[str]]:
        return [c for c in self.llamadas if c[: len(prefijo)] == list(prefijo)]

    def __call__(self, args: list[str], entrada: str | None = None) -> amb.Salida:
        assert args[-2:] == [f"--project={PROYECTO}", "--quiet"]
        args = args[:-2]
        self.llamadas.append(args)
        if entrada is not None:
            self.valores.append(entrada)
        for prefijo, error in self.fallas.items():
            if tuple(args[: len(prefijo)]) == prefijo:
                return amb.Salida(1, error=f"ERROR: (gcloud.{'.'.join(args[:3])}) {error}")
        return self._responder(args, entrada)

    def _falta(self, args) -> amb.Salida:
        clave = (args[0], args[1] if args[0] != "secrets" or args[1] == "versions" else "describe")
        return amb.Salida(
            1,
            error=f"ERROR: (gcloud.{'.'.join(args[:3])}) {self.NO_EXISTE.get(clave, 'NOT_FOUND')}",
        )

    @staticmethod
    def _opcion(args, nombre, defecto=None):
        return next((a.split("=", 1)[1] for a in args if a.startswith(f"--{nombre}=")), defecto)

    @staticmethod
    def _pares(texto):
        return dict(par.split("=", 1) for par in texto.split(",")) if texto else {}

    def _responder(self, args, entrada):  # noqa: C901 - un caso por comando
        ok, falta = amb.Salida(0), self._falta(args)
        grupo, recurso, verbo = (args + ["", "", ""])[:3]
        objetivo = args[3] if len(args) > 3 else ""
        if (grupo, recurso, verbo) == ("iam", "service-accounts", "describe"):
            return ok if objetivo in self.cuentas else falta
        if (grupo, recurso) == ("artifacts", "repositories"):
            return ok if self.repositorio else falta
        if (grupo, recurso) == ("artifacts", "packages"):
            if objetivo not in self.paquetes:
                return falta
            if verbo == "delete":
                self.paquetes.discard(objetivo)
            return ok
        if (grupo, recurso) == ("sql", "instances"):
            if verbo == "list":
                filas = [
                    {"name": n, "state": "RUNNABLE", "settings": {"activationPolicy": p}}
                    for n, p in {**self.instancias, "otra-app": "ALWAYS"}.items()
                ]
                return amb.Salida(0, json.dumps(filas))
            if verbo == "create":
                self.instancias[objetivo] = "ALWAYS"
            elif verbo == "patch":
                self.instancias[objetivo] = self._opcion(args, "activation-policy")
            elif verbo == "delete":
                del self.instancias[objetivo]
            return amb.Salida(0, f"op-{verbo}-{objetivo}\n")
        if (grupo, recurso, verbo) == ("sql", "operations", "wait"):
            return ok
        if (grupo, recurso) == ("sql", "databases"):
            clave = (self._opcion(args, "instance"), objetivo)
            if verbo == "create":
                self.bases.add(clave)
                return ok
            return ok if clave in self.bases else falta
        if (grupo, recurso) == ("sql", "users"):
            instancia = self._opcion(args, "instance")
            if verbo == "list":
                nombres = [u for i, u in self.usuarios if i == instancia]
                return amb.Salida(0, "\n".join(nombres))
            self.usuarios.add((instancia, objetivo))
            return ok
        if grupo == "secrets":
            return self._secretos(args, recurso, verbo, entrada)
        if (grupo, recurso) == ("storage", "buckets"):
            return self._bucket(args, verbo, objetivo)
        if (grupo, recurso) == ("storage", "rm"):
            self.buckets.pop(args[3], None)
            return ok
        if (grupo, recurso) == ("run", "services"):
            return self._servicio(args, verbo, objetivo)
        if (grupo, recurso) == ("run", "deploy"):
            self.servicios[args[2]] = {
                "cloudsql": self._opcion(args, "add-cloudsql-instances"),
                "env": {
                    **{v: ("secreto", s.split(":")[0])
                       for v, s in self._pares(self._opcion(args, "set-secrets", "")).items()},
                    **self._pares(self._opcion(args, "set-env-vars", "")),
                },
            }  # fmt: skip
            return ok
        raise AssertionError(f"comando no simulado: {args}")

    def _bucket(self, args, verbo, objetivo):
        if verbo == "create":
            self.buckets[objetivo] = {"ciclo": None, "roles": set()}
            return amb.Salida(0)
        bucket = self.buckets.get(objetivo)
        if bucket is None:
            return self._falta(args)
        if verbo == "describe" and "--format=json(lifecycle_config)" in args:
            descripcion = {"lifecycle_config": bucket["ciclo"]} if bucket["ciclo"] else {}
            return amb.Salida(0, json.dumps(descripcion))
        if verbo == "update":
            with open(self._opcion(args, "lifecycle-file"), encoding="utf-8") as archivo:
                bucket["ciclo"] = json.load(archivo)
        elif verbo == "get-iam-policy":
            miembro = f"serviceAccount:run-nucleo@{PROYECTO}.iam.gserviceaccount.com"
            vinculos = [{"role": r, "members": [miembro]} for r in sorted(bucket["roles"])]
            vinculos.append(
                {"role": "roles/storage.legacyBucketOwner", "members": ["projectOwner:x"]}
            )
            return amb.Salida(0, json.dumps({"bindings": vinculos}))
        elif verbo == "add-iam-policy-binding":
            bucket["roles"].add(self._opcion(args, "role"))
        return amb.Salida(0)

    def _servicio(self, args, verbo, objetivo):
        if objetivo not in self.servicios:
            return self._falta(args)
        servicio = self.servicios[objetivo]
        if verbo == "delete":
            del self.servicios[objetivo]
            return amb.Salida(0)
        if verbo == "update":
            servicio["cloudsql"] = self._opcion(
                args, "set-cloudsql-instances", servicio["cloudsql"]
            )
            for v, s in self._pares(self._opcion(args, "update-secrets", "")).items():
                servicio["env"][v] = ("secreto", s.split(":")[0])
            servicio["env"].update(self._pares(self._opcion(args, "update-env-vars", "")))
            return amb.Salida(0)
        if "--format=value(status.url)" in args:
            return amb.Salida(0, f"https://{objetivo}-abc.run.app\n")
        variables = [
            {"name": v, "valueFrom": {"secretKeyRef": {"key": "latest", "name": x[1]}}}
            if isinstance(x, tuple)
            else {"name": v, "value": x}
            for v, x in servicio["env"].items()
        ]
        anotaciones = {"run.googleapis.com/cloudsql-instances": servicio["cloudsql"]}
        plantilla = {
            "metadata": {"annotations": anotaciones},
            "spec": {"containers": [{"env": variables}]},
        }
        return amb.Salida(0, json.dumps({"spec": {"template": plantilla}}))

    def _secretos(self, args, recurso, verbo, entrada):
        ok = amb.Salida(0)
        if recurso == "versions":
            nombre = self._opcion(args, "secret") if verbo != "add" else args[3]
            secreto = self.secretos.get(nombre)
            if verbo == "add":
                secreto["versiones"].append(entrada)
                return ok
            if not secreto or not secreto["versiones"]:
                return self._falta(args)
            return amb.Salida(0, secreto["versiones"][-1]) if verbo == "access" else ok
        nombre = verbo
        secreto = self.secretos.get(nombre)
        if recurso == "create":
            self.secretos[nombre] = {"versiones": [], "instancia": None}
            return ok
        if secreto is None:
            return self._falta(args)
        if recurso == "describe":
            if "--format=value(labels.instancia)" in args:
                return amb.Salida(0, (secreto["instancia"] or "") + "\n")
            return ok
        if recurso == "update":
            secreto["instancia"] = self._opcion(args, "update-labels").split("=", 1)[1]
        elif recurso == "delete":
            del self.secretos[nombre]
        return ok


class ConexionSimulada:
    def __init__(self, roles=()):
        self.roles = set(roles)
        self.sentencias: list[str] = []
        self.cerrada = False
        self._ultima = None

    def cursor(self):
        return self

    def execute(self, sql, parametros=None):
        self.sentencias.append(sql)
        self._ultima = parametros

    def fetchone(self):
        return (1,) if self._ultima and self._ultima[0] in self.roles else None

    def close(self):
        self.cerrada = True


@pytest.fixture
def entorno():
    return {"HUMO_CORREO": "humo@solventa.test", "HUMO_CLAVE": "clave-del-humo"}


def _ambiente(nube, entorno=None, conexion=None):
    conexiones = []

    def conectar(destino, usuario, clave):
        conexiones.append((destino, usuario, clave))
        return conexion if conexion is not None else ConexionSimulada()

    ambiente = amb.Ambiente(
        amb.Gcloud(PROYECTO, nube),
        REGION,
        entorno=entorno or {},
        conectar=conectar,
        generar_llaves=lambda: ("PRIVADA-PEM", "PUBLICA-PEM"),
        ahora=lambda: datetime(2026, 11, 2, 9, 30, tzinfo=UTC),
    )
    ambiente.conexiones = conexiones
    return ambiente


class TestAprovisionarDesdeCero:
    @pytest.fixture
    def nube(self):
        return NubeSimulada()

    def test_crea_todo_en_orden_de_dependencia(self, nube, entorno):
        conexion = ConexionSimulada()
        ambiente = _ambiente(nube, entorno, conexion)
        ambiente.aprovisionar()

        assert list(nube.instancias) == ["solventa-20261102-0930"]
        assert ("solventa-20261102-0930", "solventa") in nube.bases
        assert set(nube.secretos) == set(amb.SECRETOS)
        assert set(nube.buckets) == {BUCKET}
        assert [c[2] for c in nube.comandos("run", "deploy")] == list(amb.SERVICIOS)
        assert ambiente.servicios_creados == list(amb.SERVICIOS)
        assert conexion.cerrada
        assert [s.split(" PASSWORD")[0] for s in conexion.sentencias if "ROLE" in s] == [
            "CREATE ROLE cotizacion LOGIN",
            "CREATE ROLE siniestros LOGIN",
        ]

    def test_espera_a_que_cloud_sql_termine_de_crearse(self, nube, entorno):
        _ambiente(nube, entorno).aprovisionar()
        crear = nube.llamadas.index(nube.comandos("sql", "instances", "create")[0])
        esperar = nube.llamadas.index(nube.comandos("sql", "operations", "wait")[0])
        assert esperar == crear + 1
        assert nube.llamadas[esperar][3] == "op-create-solventa-20261102-0930"

    def test_cada_cadena_apunta_a_la_instancia_nueva_y_queda_etiquetada(self, nube, entorno):
        _ambiente(nube, entorno).aprovisionar()
        for modulo in amb.MODULOS_BD:
            secreto = nube.secretos[f"{modulo}-database-url"]
            assert secreto["instancia"] == "solventa-20261102-0930"
            assert secreto["versiones"][-1].startswith(f"postgresql://{modulo}:")
            assert secreto["versiones"][-1].endswith(
                f"@/solventa?host=/cloudsql/{PROYECTO}:{REGION}:solventa-20261102-0930"
            )

    def test_el_nucleo_queda_con_su_configuracion(self, nube, entorno):
        _ambiente(nube, entorno).aprovisionar()
        nucleo = nube.comandos("run", "deploy", "solventa-nucleo")[0]
        assert f"--service-account=run-nucleo@{PROYECTO}.iam.gserviceaccount.com" in nucleo
        assert "--min-instances=1" in nucleo and "--max-instances=1" in nucleo
        assert f"--image={amb.IMAGEN_INICIAL}" in nucleo
        secretos = next(a for a in nucleo if a.startswith("--set-secrets="))
        assert all(f"{v}={s}:latest" in secretos for v, s in amb.SECRETOS_NUCLEO.items())
        assert amb.SECRETO_CUENTA_HUMO not in secretos
        assert f"POLIZAS_SEMILLA={amb.SEMILLA_POLIZAS}" in next(
            a for a in nucleo if a.startswith("--set-env-vars=")
        )

    def test_el_bff_y_la_web_apuntan_al_anterior(self, nube, entorno):
        _ambiente(nube, entorno).aprovisionar()
        bff = nube.comandos("run", "deploy", "solventa-bff")[0]
        web = nube.comandos("run", "deploy", "solventa-web")[0]
        assert "--set-env-vars=NUCLEO_URL=https://solventa-nucleo-abc.run.app" in bff
        assert "--set-secrets=TOKENS_LLAVE_PUBLICA=tokens-llave-publica:latest" in bff
        assert "--set-env-vars=BFF_URL=https://solventa-bff-abc.run.app" in web
        assert not any(a.startswith("--set-secrets") for a in web)

    def test_los_secretos_solo_los_lee_quien_los_usa(self, nube, entorno):
        _ambiente(nube, entorno).aprovisionar()
        accesos = {
            (c[2], next(a for a in c if a.startswith("--member=")).split("@")[0])
            for c in nube.comandos("secrets", "add-iam-policy-binding")
        }
        assert ("tokens-llave-publica", "--member=serviceAccount:run-bff") in accesos
        assert ("tokens-llave-privada", "--member=serviceAccount:run-bff") not in accesos
        assert all(s != amb.SECRETO_CUENTA_HUMO for s, _ in accesos)

    def test_el_bucket_es_privado_y_sin_permiso_de_borrar(self, nube, entorno):
        _ambiente(nube, entorno).aprovisionar()
        (crear,) = nube.comandos("storage", "buckets", "create")
        assert "--public-access-prevention" in crear and "--uniform-bucket-level-access" in crear
        roles = [c[-1] for c in nube.comandos("storage", "buckets", "add-iam-policy-binding")]
        assert roles == ["--role=roles/storage.objectCreator", "--role=roles/storage.objectViewer"]

    def test_nunca_imprime_los_valores_de_los_secretos(self, nube, entorno, capsys):
        _ambiente(nube, entorno).aprovisionar()
        salida = capsys.readouterr().out
        assert nube.valores
        for valor in nube.valores:
            assert valor not in salida
        assert "clave-del-humo" not in salida

    def test_sin_la_cuenta_de_prueba_avisa_y_no_la_monta(self, nube):
        ambiente = _ambiente(nube)
        ambiente.aprovisionar()
        assert "humo-correo" not in nube.secretos
        assert any("HUMO_CORREO" in a for a in ambiente.informe.avisos)
        nucleo = nube.comandos("run", "deploy", "solventa-nucleo")[0]
        assert "HUMO_CORREO" not in next(a for a in nucleo if a.startswith("--set-secrets="))


class TestAprovisionarLoQueYaExiste:
    def test_con_todo_creado_no_crea_ni_cambia_nada(self, entorno):
        nube = NubeSimulada(completa=True)
        ambiente = _ambiente(nube, entorno)
        ambiente.aprovisionar()

        assert _escrituras(nube) == []
        assert not nube.comandos("secrets", "versions", "access")  # ni siquiera lee las claves
        assert ambiente.conexiones == []
        assert ambiente.servicios_creados == []
        assert sum("ya existe, se omite" in linea for linea in ambiente.informe.lineas) >= 10

    def test_enciende_cloud_sql_si_esta_apagada_y_sigue(self, entorno):
        nube = NubeSimulada(completa=True)
        nube.instancias["solventa-auditoria"] = "NEVER"
        _ambiente(nube, entorno).aprovisionar()
        assert nube.instancias == {"solventa-auditoria": "ALWAYS"}
        assert nube.comandos("sql", "operations", "wait")
        assert not nube.comandos("sql", "instances", "create")

    def test_solo_rehace_el_usuario_cuyo_secreto_falta(self, entorno):
        nube = NubeSimulada(completa=True)
        del nube.secretos["siniestros-database-url"]
        conexion = ConexionSimulada(roles={"cotizacion", "siniestros"})
        ambiente = _ambiente(nube, entorno, conexion)
        ambiente.aprovisionar()

        assert [s.split(" PASSWORD")[0] for s in conexion.sentencias if "ROLE" in s] == [
            "ALTER ROLE siniestros LOGIN"
        ]
        # Entra como el dueño con la clave de su secreto, sin cambiársela.
        assert ambiente.conexiones == [(CONEXION_VIEJA, "auditoria", "clave-auditoria")]
        assert not nube.comandos("sql", "users", "set-password")

    def test_si_cloud_sql_se_recreo_rehace_usuarios_y_mueve_el_nucleo(self, entorno):
        nube = NubeSimulada(completa=True)
        del nube.instancias["solventa-auditoria"]
        nube.instancias["solventa-20261101-0800"] = "ALWAYS"
        nube.bases.add(("solventa-20261101-0800", "solventa"))
        _ambiente(nube, entorno).aprovisionar()

        assert {nube.secretos[f"{m}-database-url"]["instancia"] for m in amb.MODULOS_BD} == {
            "solventa-20261101-0800"
        }
        assert nube.comandos("sql", "users", "create")[0][3] == "auditoria"
        assert nube.servicios["solventa-nucleo"]["cloudsql"].endswith(":solventa-20261101-0800")
        assert not nube.comandos("run", "deploy")

    def test_con_la_privada_sin_publica_deriva_la_publica(self, entorno, monkeypatch):
        nube = NubeSimulada(completa=True)
        del nube.secretos["tokens-llave-publica"]
        nube.secretos["tokens-llave-privada"]["versiones"] = ["PRIVADA-EXISTENTE"]
        monkeypatch.setattr(amb, "publica_de", lambda privada: f"PUBLICA-DE-{privada}")
        _ambiente(nube, entorno).aprovisionar()
        assert nube.secretos["tokens-llave-publica"]["versiones"] == [
            "PUBLICA-DE-PRIVADA-EXISTENTE"
        ]
        assert nube.secretos["tokens-llave-privada"]["versiones"] == ["PRIVADA-EXISTENTE"]

    def test_sin_las_cuentas_de_ejecucion_no_crea_nada(self, entorno, capsys):
        nube = NubeSimulada()
        nube.cuentas.clear()
        codigo = amb.main(
            ["aprovisionar", "--proyecto", PROYECTO, "--region", REGION], _ambiente(nube, entorno)
        )
        assert codigo == 1
        assert "::error::Falta la cuenta run-nucleo" in capsys.readouterr().out
        assert nube.instancias == {}


class TestVerificar:
    def test_ambiente_completo(self):
        ambiente = _ambiente(NubeSimulada(completa=True))
        assert ambiente.verificar() == 0
        assert ambiente.informe.avisos == []
        assert "  Ambiente completo" in ambiente.informe.lineas

    def test_solo_lee(self):
        nube = NubeSimulada()
        _ambiente(nube).verificar()
        verbos = {c[2] for c in nube.llamadas}
        assert verbos <= {"describe", "list"}

    def test_avisa_lo_que_falta_sin_fallar(self, capsys):
        nube = NubeSimulada(completa=True)
        del nube.secretos["humo-clave"]
        del nube.servicios["solventa-web"]
        codigo = amb.main(
            ["verificar", "--proyecto", PROYECTO, "--region", REGION], _ambiente(nube)
        )
        salida = capsys.readouterr().out
        assert codigo == 0
        assert "::warning::Falta el secreto humo-clave" in salida
        assert "::warning::Falta el servicio solventa-web" in salida

    def test_avisa_si_cloud_sql_esta_apagada(self):
        nube = NubeSimulada(completa=True)
        nube.instancias["solventa-auditoria"] = "NEVER"
        ambiente = _ambiente(nube)
        assert ambiente.verificar() == 0
        assert ambiente.informe.avisos == [
            "Cloud SQL solventa-auditoria está apagada: el núcleo no persiste nada"
        ]

    def test_avisa_si_un_secreto_apunta_a_otra_instancia(self):
        nube = NubeSimulada(completa=True)
        nube.secretos["cotizacion-database-url"]["instancia"] = "solventa-vieja"
        ambiente = _ambiente(nube)
        assert ambiente.verificar() == 1
        assert "cotizacion-database-url apunta a solventa-vieja" in ambiente.informe.avisos[0]

    def test_sin_instancia(self):
        ambiente = _ambiente(NubeSimulada())
        assert ambiente.verificar() > 0
        assert ambiente.informe.avisos[0].startswith("No hay instancia de Cloud SQL")


class TestDestruir:
    def test_exige_confirmar_con_el_id_del_proyecto(self, capsys):
        nube = NubeSimulada(completa=True)
        argumentos = ["destruir", "--proyecto", PROYECTO, "--region", REGION]
        assert amb.main([*argumentos, "--confirmar", "otro"], _ambiente(nube)) == 2
        assert amb.main(argumentos, _ambiente(nube)) == 2
        assert nube.servicios and nube.instancias
        assert "--confirmar debe ser el ID del proyecto" in capsys.readouterr().out

    def test_borra_lo_que_cuesta_y_conserva_el_acceso(self):
        nube = NubeSimulada(completa=True)
        argumentos = ["destruir", "--proyecto", PROYECTO, "--region", REGION]
        assert amb.main([*argumentos, "--confirmar", PROYECTO], _ambiente(nube)) == 0

        assert nube.servicios == {} and nube.instancias == {} and nube.secretos == {}
        assert nube.buckets == {} and nube.paquetes == set()
        assert len(nube.cuentas) == 3 and nube.repositorio
        borrados = [c for c in nube.llamadas if "delete" in c[:3] or c[:2] == ["storage", "rm"]]
        assert not any(c[0] == "iam" or c[1] == "repositories" for c in borrados)

    def test_borra_los_servicios_antes_que_la_base(self):
        nube = NubeSimulada(completa=True)
        _ambiente(nube).destruir()
        borrados = [c[3] for c in nube.llamadas if c[2] == "delete" and c[0] in ("run", "sql")]
        assert borrados == ["solventa-web", "solventa-bff", "solventa-nucleo", "solventa-auditoria"]

    def test_no_toca_instancias_de_otras_aplicaciones(self):
        nube = NubeSimulada(completa=True)
        _ambiente(nube).destruir()
        assert ["sql", "instances", "delete", "otra-app"] not in [c[:4] for c in nube.llamadas]

    def test_dos_veces_seguidas_la_segunda_no_borra_nada(self):
        nube = NubeSimulada(completa=True)
        _ambiente(nube).destruir()
        nube.llamadas.clear()
        ambiente = _ambiente(nube)
        ambiente.destruir()
        assert not [c for c in nube.llamadas if "delete" in c[:3]]
        assert all(not linea.startswith("-") for linea in ambiente.informe.lineas)


def test_un_error_de_gcloud_no_muestra_los_argumentos():
    def falla(args, entrada=None):
        return amb.Salida(1, error="ERROR: (gcloud.sql.users.create) PERMISSION_DENIED")

    gcloud = amb.Gcloud(PROYECTO, falla)
    with pytest.raises(amb.GcloudError) as error:
        gcloud("sql", "users", "create", "auditoria", "--password=secreta")
    assert "secreta" not in str(error.value)
    assert str(error.value) == (
        "gcloud sql users create: ERROR: (gcloud.sql.users.create) PERMISSION_DENIED"
    )


def test_tolerar_un_error_conocido():
    gcloud = amb.Gcloud(PROYECTO, lambda args, entrada=None: amb.Salida(1, error="ALREADY_EXISTS"))
    assert gcloud("secrets", "create", "x", tolerar="already_exists") == ""


def test_aprovisionar_informa_los_servicios_creados_al_workflow(tmp_path, entorno):
    salida = tmp_path / "salida"
    argumentos = ["aprovisionar", "--proyecto", PROYECTO, "--region", REGION]
    nube = NubeSimulada()
    assert amb.main([*argumentos, f"--github-output={salida}"], _ambiente(nube, entorno)) == 0
    assert salida.read_text() == f"servicios_creados={','.join(amb.SERVICIOS)}\n"


ESCRITURAS = ("create", "deploy", "add", "update", "patch", "delete", "set-password")


def _escrituras(nube):
    """Comandos que cambian algo; los permisos sobre secretos son idempotentes y no cuentan."""
    return [
        c
        for c in nube.llamadas
        if c[-1] != "--role=roles/secretmanager.secretAccessor"
        and (any(v in c[:3] for v in ESCRITURAS) or "add-iam-policy-binding" in c[:3])
    ]


class TestNoEncontradoContraOtrosErrores:
    """Hallazgo 3: solo un "no encontrado" significa que el recurso no existe."""

    @pytest.mark.parametrize(
        ("args", "error"),
        [
            (("run", "services", "describe", "x"), "Cannot find service [x]"),
            (("secrets", "describe", "x"), "NOT_FOUND: Secret [projects/1/secrets/x] not found."),
            (("storage", "buckets", "describe", "gs://x"), "gs://x not found: 404."),
            (("sql", "databases", "describe", "x"), "HTTPError 404: Not Found"),
            (("iam", "service-accounts", "describe", "x"), "NOT_FOUND: Unknown service account."),
        ],
    )
    def test_el_404_de_cada_comando_es_no_existe(self, args, error):
        gcloud = amb.Gcloud(PROYECTO, lambda a, e=None: amb.Salida(1, error=f"ERROR: {error}"))
        assert gcloud.existe(*args) is False

    @pytest.mark.parametrize(
        "error",
        [
            "ERROR: (gcloud.run.services.describe) 503 Service Unavailable",
            (
                "ERROR: (gcloud.secrets.describe) PERMISSION_DENIED: Permission denied on resource "
                "(or it may not exist)."
            ),
            (
                "ERROR: (gcloud.sql.databases.describe) HTTPError 400: Invalid request since "
                "instance is not running."
            ),
        ],
    )
    def test_otro_error_no_es_no_existe(self, error):
        gcloud = amb.Gcloud(PROYECTO, lambda a, e=None: amb.Salida(1, error=error))
        with pytest.raises(amb.GcloudError) as fallo:
            gcloud.existe("run", "services", "describe", "--password=secreta")
        assert "secreta" not in str(fallo.value)

    def test_un_503_al_consultar_un_servicio_no_lo_reemplaza(self, entorno, capsys):
        nube = NubeSimulada(completa=True)
        nube.fallas[("run", "services", "describe")] = "503 Service Unavailable"
        argumentos = ["aprovisionar", "--proyecto", PROYECTO, "--region", REGION]
        assert amb.main(argumentos, _ambiente(nube, entorno)) == 1
        assert not nube.comandos("run", "deploy")
        assert "::error::gcloud run services describe: " in capsys.readouterr().out

    def test_un_403_al_consultar_las_llaves_no_las_regenera(self, entorno):
        nube = NubeSimulada(completa=True)
        nube.fallas[("secrets", "versions", "describe")] = "PERMISSION_DENIED: Permission denied"
        with pytest.raises(amb.GcloudError):
            _ambiente(nube, entorno).aprovisionar()
        assert not nube.comandos("secrets", "versions", "add")

    def test_con_la_instancia_apagada_no_crea_otra_base(self, entorno):
        nube = NubeSimulada(completa=True)
        nube.fallas[("sql", "databases", "describe")] = (
            "HTTPError 400: Invalid request since instance is not running."
        )
        with pytest.raises(amb.GcloudError):
            _ambiente(nube, entorno).aprovisionar()
        assert not nube.comandos("sql", "databases", "create")

    def test_un_error_al_leer_la_etiqueta_de_un_secreto_no_lo_rota(self, entorno):
        nube = NubeSimulada(completa=True)
        nube.fallas[("secrets", "describe")] = "503 Service Unavailable"
        with pytest.raises(amb.GcloudError):
            _ambiente(nube, entorno).aprovisionar()
        assert not nube.comandos("secrets", "versions", "add")
        assert not nube.comandos("sql", "users")


class TestRotacionDeCredenciales:
    """Hallazgo 4: un secreto de la misma instancia no se rota, y uno rotado reinicia el núcleo."""

    def test_un_secreto_sin_etiqueta_de_la_misma_instancia_se_adopta_sin_rotar(self, entorno):
        nube = NubeSimulada(completa=True)
        nube.secretos["cotizacion-database-url"]["instancia"] = None
        ambiente = _ambiente(nube, entorno)
        ambiente.aprovisionar()

        assert nube.secretos["cotizacion-database-url"]["instancia"] == "solventa-auditoria"
        assert len(nube.secretos["cotizacion-database-url"]["versiones"]) == 1
        assert ambiente.conexiones == []
        assert not nube.comandos("run", "services", "update")
        assert any("se etiqueta sin rotar" in linea for linea in ambiente.informe.lineas)

    def test_un_secreto_sin_etiqueta_de_otra_instancia_se_rota(self, entorno):
        nube = NubeSimulada(completa=True)
        secreto = nube.secretos["cotizacion-database-url"]
        secreto["instancia"] = None
        secreto["versiones"] = ["postgresql://cotizacion:x@/solventa?host=/cloudsql/p:r:vieja"]
        _ambiente(nube, entorno).aprovisionar()
        assert len(secreto["versiones"]) == 2
        assert secreto["instancia"] == "solventa-auditoria"

    @pytest.mark.parametrize("caso", ["otra instancia", "faltante"])
    def test_una_clave_rotada_reinicia_el_nucleo(self, entorno, caso):
        nube = NubeSimulada(completa=True)
        if caso == "faltante":
            del nube.secretos["siniestros-database-url"]
        else:
            nube.secretos["siniestros-database-url"]["instancia"] = "solventa-vieja"
        _ambiente(nube, entorno, ConexionSimulada(roles={"siniestros"})).aprovisionar()

        (actualizacion,) = nube.comandos("run", "services", "update", "solventa-nucleo")
        assert "--update-env-vars=CREDENCIALES_ROTADAS=20261102T093000Z" in actualizacion
        assert not nube.comandos("run", "services", "update", "solventa-bff")

    def test_llaves_nuevas_reinician_el_nucleo_y_el_bff(self, entorno):
        nube = NubeSimulada(completa=True)
        del nube.secretos["tokens-llave-privada"]
        del nube.secretos["tokens-llave-publica"]
        _ambiente(nube, entorno).aprovisionar()
        actualizados = [c[3] for c in nube.comandos("run", "services", "update")]
        assert actualizados == ["solventa-nucleo", "solventa-bff"]


class TestReconciliarNucleoExistente:
    """Hallazgo 5: el núcleo que ya existe recibe los secretos y las variables que le falten."""

    def test_agrega_lo_que_falta_sin_borrar_lo_demas(self, entorno):
        nube = NubeSimulada(completa=True)
        env = nube.servicios["solventa-nucleo"]["env"]
        for variable in ("SINIESTROS_DATABASE_URL", "HUMO_CORREO", "ALMACENAMIENTO_EVIDENCIAS"):
            del env[variable]
        env["OTRA_VARIABLE"] = "se conserva"
        _ambiente(nube, entorno).aprovisionar()

        (actualizacion,) = nube.comandos("run", "services", "update", "solventa-nucleo")
        assert (
            "--update-secrets=SINIESTROS_DATABASE_URL=siniestros-database-url:latest,"
            "HUMO_CORREO=humo-correo:latest"
        ) in actualizacion
        assert "--update-env-vars=ALMACENAMIENTO_EVIDENCIAS=gcs" in actualizacion
        assert not any(a.startswith("--set-") for a in actualizacion)
        assert env["OTRA_VARIABLE"] == "se conserva"
        assert env["SINIESTROS_DATABASE_URL"] == ("secreto", "siniestros-database-url")

    def test_corrige_un_valor_distinto(self, entorno):
        nube = NubeSimulada(completa=True)
        nube.servicios["solventa-nucleo"]["env"]["POLIZAS_SEMILLA"] = "C:/Program Files/Git/opt/x"
        _ambiente(nube, entorno).aprovisionar()
        assert nube.servicios["solventa-nucleo"]["env"]["POLIZAS_SEMILLA"] == amb.SEMILLA_POLIZAS

    def test_monta_un_secreto_existente_aunque_no_haya_cuenta_de_prueba_en_el_entorno(self):
        nube = NubeSimulada(completa=True)
        del nube.servicios["solventa-nucleo"]["env"]["HUMO_CLAVE"]
        _ambiente(nube).aprovisionar()
        assert nube.servicios["solventa-nucleo"]["env"]["HUMO_CLAVE"] == ("secreto", "humo-clave")


class TestRepararBucket:
    """Hallazgo 6: un bucket que existe sin ciclo de vida ni permisos se repara."""

    def test_bucket_existente_sin_configurar_queda_reparado(self, entorno):
        nube = NubeSimulada(completa=True)
        nube.buckets[BUCKET] = {"ciclo": None, "roles": set()}
        ambiente = _ambiente(nube, entorno)
        ambiente.aprovisionar()

        assert nube.buckets[BUCKET] == {"ciclo": amb.CICLO_BUCKET, "roles": set(ROLES_BUCKET)}
        assert not nube.comandos("storage", "buckets", "create")
        assert any("se completó el ciclo de vida" in linea for linea in ambiente.informe.lineas)

        nube.llamadas.clear()
        _ambiente(nube, entorno).aprovisionar()
        assert _escrituras(nube) == []

    def test_solo_agrega_el_permiso_que_falta(self, entorno):
        nube = NubeSimulada(completa=True)
        nube.buckets[BUCKET]["roles"] = {"roles/storage.objectCreator"}
        _ambiente(nube, entorno).aprovisionar()
        roles = [c[-1] for c in nube.comandos("storage", "buckets", "add-iam-policy-binding")]
        assert roles == ["--role=roles/storage.objectViewer"]
        assert not nube.comandos("storage", "buckets", "update")


class TestVerificarSoloAvisa:
    """Hallazgo 7: un fallo al consultar no pone el CD en rojo; aprovisionar sí falla."""

    def test_si_falla_la_lista_de_instancias_verificar_devuelve_cero(self, capsys):
        nube = NubeSimulada(completa=True)
        nube.fallas[("sql", "instances", "list")] = "503 Service Unavailable"
        argumentos = ["verificar", "--proyecto", PROYECTO, "--region", REGION]
        codigo = amb.main(argumentos, _ambiente(nube))
        salida = capsys.readouterr().out
        assert codigo == 0
        assert "::warning::No se pudo verificar el ambiente: gcloud sql instances list: " in salida
        assert "::error::" not in salida

    @pytest.mark.parametrize("accion", ["aprovisionar", "destruir"])
    def test_aprovisionar_y_destruir_siguen_fallando(self, accion, entorno):
        nube = NubeSimulada(completa=True)
        nube.fallas[("sql", "instances", "list")] = "503 Service Unavailable"
        argumentos = [accion, "--proyecto", PROYECTO, "--region", REGION, "--confirmar", PROYECTO]
        assert amb.main(argumentos, _ambiente(nube, entorno)) == 1
