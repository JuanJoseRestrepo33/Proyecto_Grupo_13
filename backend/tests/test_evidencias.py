"""Pruebas de MOB-F05 (#177): almacenamiento de evidencias detrás de su puerto.

Las pruebas de contrato corren contra los dos adaptadores (memoria y Cloud Storage con un cliente
simulado), así ninguno se aparta de las reglas del puerto: no borra, no reemplaza y no revela datos
del cliente en el nombre del objeto.
"""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor

import pytest

from app.modulos.siniestros.api.dependencias import crear_almacen_de_evidencias
from app.modulos.siniestros.dominio.almacen_evidencias import (
    AlmacenamientoNoDisponibleError,
    AlmacenDeEvidencias,
    EvidenciaDuplicadaError,
    limpiar_nombre_original,
    nombre_del_objeto,
)
from app.modulos.siniestros.infraestructura.almacen_evidencias_gcs import AlmacenDeEvidenciasGcs
from app.modulos.siniestros.infraestructura.almacen_evidencias_memoria import (
    AlmacenDeEvidenciasEnMemoria,
)

FOTO = b"\xff\xd8\xff\xe0 bytes de una foto"


class FalloDeGcsError(Exception):
    """Como las excepciones de google.api_core: llevan el código HTTP en `code`."""

    def __init__(self, code: int) -> None:
        super().__init__(code)
        self.code = code


class ObjetoSimulado:
    def __init__(self, bucket: BucketSimulado, nombre: str) -> None:
        self._bucket, self.name = bucket, nombre
        self.metadata: dict[str, str] | None = None
        self.content_type: str | None = None

    def upload_from_string(self, datos: bytes, content_type: str, if_generation_match: int) -> None:
        self._bucket.solicitudes.append(("subir", self.name, if_generation_match))
        if self._bucket.falla:
            raise FalloDeGcsError(503)
        if if_generation_match == 0 and self.name in self._bucket.objetos:
            raise FalloDeGcsError(412)
        self.content_type = content_type
        self._bucket.objetos[self.name] = (bytes(datos), self)

    def download_as_bytes(self) -> bytes:
        if self._bucket.falla:
            raise FalloDeGcsError(503)
        return self._bucket.objetos[self.name][0]


class BucketSimulado:
    def __init__(self, nombre: str) -> None:
        self.nombre = nombre
        self.objetos: dict[str, tuple[bytes, ObjetoSimulado]] = {}
        self.solicitudes: list[tuple] = []
        self.falla = False

    def blob(self, nombre: str) -> ObjetoSimulado:
        return ObjetoSimulado(self, nombre)

    def get_blob(self, nombre: str) -> ObjetoSimulado | None:
        if self.falla:
            raise FalloDeGcsError(503)
        guardado = self.objetos.get(nombre)
        return guardado[1] if guardado else None


class ClienteSimulado:
    def __init__(self) -> None:
        self.buckets: dict[str, BucketSimulado] = {}

    def bucket(self, nombre: str) -> BucketSimulado:
        return self.buckets.setdefault(nombre, BucketSimulado(nombre))


@pytest.fixture
def cliente() -> ClienteSimulado:
    return ClienteSimulado()


@pytest.fixture(params=["memoria", "gcs"])
def almacen(request, cliente) -> AlmacenDeEvidencias:
    if request.param == "memoria":
        return AlmacenDeEvidenciasEnMemoria()
    return AlmacenDeEvidenciasGcs("evidencias-de-prueba", cliente)


class TestContratoDelPuerto:
    def test_guarda_y_lee_la_evidencia_con_sus_metadatos(self, almacen):
        guardada = almacen.guardar("sin-0001", "ev-1", "image/jpeg", FOTO, "choque.jpg")
        assert guardada.tamano == len(FOTO)
        leida = almacen.leer("sin-0001", "ev-1")
        assert leida is not None
        assert leida.contenido == FOTO
        assert leida.evidencia == guardada

    def test_leer_una_evidencia_que_no_existe_devuelve_none(self, almacen):
        assert almacen.leer("sin-0001", "no-existe") is None

    def test_no_reemplaza_una_evidencia_existente(self, almacen):
        almacen.guardar("sin-0001", "ev-1", "image/jpeg", FOTO)
        with pytest.raises(EvidenciaDuplicadaError):
            almacen.guardar("sin-0001", "ev-1", "image/png", b"otra cosa")
        assert almacen.leer("sin-0001", "ev-1").contenido == FOTO

    def test_la_misma_evidencia_en_otro_siniestro_es_otra(self, almacen):
        almacen.guardar("sin-0001", "ev-1", "image/jpeg", FOTO)
        almacen.guardar("sin-0002", "ev-1", "application/pdf", b"%PDF")
        assert almacen.leer("sin-0002", "ev-1").contenido == b"%PDF"

    def test_sin_nombre_original_no_guarda_metadato(self, almacen):
        almacen.guardar("sin-0001", "ev-1", "image/jpeg", FOTO)
        assert almacen.leer("sin-0001", "ev-1").evidencia.nombre_original is None

    def test_no_tiene_operacion_de_borrar(self, almacen):
        publicos = {m for m in dir(almacen) if not m.startswith("_")}
        assert publicos == {"guardar", "leer"}

    @pytest.mark.parametrize("identificador", ["", "../otro", "a/b", "con espacio", "x" * 65])
    def test_rechaza_identificadores_que_no_son_seguros_para_el_nombre(
        self, almacen, identificador
    ):
        with pytest.raises(ValueError):
            almacen.guardar(identificador, "ev-1", "image/jpeg", FOTO)
        with pytest.raises(ValueError):
            almacen.leer("sin-0001", identificador)


class TestNombres:
    def test_el_nombre_del_objeto_no_lleva_datos_del_cliente(self):
        assert nombre_del_objeto("sin-0001", "ev-1") == "siniestros/sin-0001/ev-1"

    @pytest.mark.parametrize(
        ("original", "limpio"),
        [
            ("choque.jpg", "choque.jpg"),
            ("C:\\Users\\ana\\Fotos\\choque.jpg", "choque.jpg"),
            ("/home/ana/acta.pdf", "acta.pdf"),
            ("acta\x00\n.pdf", "acta.pdf"),
            ("   ", None),
            (None, None),
            ("a" * 300 + ".jpg", "a" * 255),
        ],
    )
    def test_el_nombre_original_pierde_rutas_y_caracteres_de_control(self, original, limpio):
        assert limpiar_nombre_original(original) == limpio


class TestCloudStorage:
    def test_sube_con_precondicion_de_no_existir_y_con_el_tipo_de_contenido(self, cliente):
        almacen = AlmacenDeEvidenciasGcs("evidencias", cliente)
        almacen.guardar("sin-0001", "ev-1", "video/mp4", b"video", "C:\\x\\clip.mp4")
        bucket = cliente.buckets["evidencias"]
        assert bucket.solicitudes == [("subir", "siniestros/sin-0001/ev-1", 0)]
        _, objeto = bucket.objetos["siniestros/sin-0001/ev-1"]
        assert objeto.content_type == "video/mp4"
        assert objeto.metadata == {"nombre_original": "clip.mp4"}

    def test_un_fallo_de_cloud_storage_al_guardar_es_almacenamiento_no_disponible(self, cliente):
        almacen = AlmacenDeEvidenciasGcs("evidencias", cliente)
        cliente.bucket("evidencias").falla = True
        with pytest.raises(AlmacenamientoNoDisponibleError) as error:
            almacen.guardar("sin-0001", "ev-1", "image/jpeg", FOTO)
        assert error.value.__cause__.code == 503

    def test_un_fallo_de_cloud_storage_al_leer_es_almacenamiento_no_disponible(self, cliente):
        almacen = AlmacenDeEvidenciasGcs("evidencias", cliente)
        almacen.guardar("sin-0001", "ev-1", "image/jpeg", FOTO)
        cliente.bucket("evidencias").falla = True
        with pytest.raises(AlmacenamientoNoDisponibleError):
            almacen.leer("sin-0001", "ev-1")

    def test_sin_tipo_de_contenido_guardado_se_lee_como_binario(self, cliente):
        almacen = AlmacenDeEvidenciasGcs("evidencias", cliente)
        almacen.guardar("sin-0001", "ev-1", "image/jpeg", FOTO)
        _, objeto = cliente.bucket("evidencias").objetos["siniestros/sin-0001/ev-1"]
        objeto.content_type = None
        assert almacen.leer("sin-0001", "ev-1").evidencia.tipo_contenido == (
            "application/octet-stream"
        )


def test_memoria_no_duplica_con_escrituras_concurrentes():
    almacen = AlmacenDeEvidenciasEnMemoria()

    def guardar(_):
        try:
            almacen.guardar("sin-0001", "ev-1", "image/jpeg", FOTO)
            return "guardada"
        except EvidenciaDuplicadaError:
            return "duplicada"

    with ThreadPoolExecutor(max_workers=8) as hilos:
        resultados = list(hilos.map(guardar, range(16)))
    assert resultados.count("guardada") == 1


class TestComposicion:
    def test_por_defecto_usa_memoria(self):
        assert isinstance(crear_almacen_de_evidencias({}), AlmacenDeEvidenciasEnMemoria)
        assert isinstance(
            crear_almacen_de_evidencias({"ALMACENAMIENTO_EVIDENCIAS": "local"}),
            AlmacenDeEvidenciasEnMemoria,
        )

    def test_gcs_usa_el_bucket_de_la_variable(self, monkeypatch, cliente):
        creados = []

        def crear(bucket, cliente_=None):
            creados.append(bucket)
            return AlmacenDeEvidenciasGcs(bucket, cliente)

        monkeypatch.setattr(
            "app.modulos.siniestros.infraestructura.almacen_evidencias_gcs.AlmacenDeEvidenciasGcs",
            crear,
        )
        entorno = {"ALMACENAMIENTO_EVIDENCIAS": "gcs", "EVIDENCIAS_BUCKET": "mi-bucket"}
        assert isinstance(crear_almacen_de_evidencias(entorno), AlmacenDeEvidenciasGcs)
        assert creados == ["mi-bucket"]

    def test_gcs_sin_bucket_no_arranca_en_silencio_contra_memoria(self):
        with pytest.raises(RuntimeError, match="EVIDENCIAS_BUCKET"):
            crear_almacen_de_evidencias({"ALMACENAMIENTO_EVIDENCIAS": "gcs"})

    def test_un_tipo_desconocido_falla(self):
        with pytest.raises(RuntimeError, match="'s3'"):
            crear_almacen_de_evidencias({"ALMACENAMIENTO_EVIDENCIAS": "s3"})
