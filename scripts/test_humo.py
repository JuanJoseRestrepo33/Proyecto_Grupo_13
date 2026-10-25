import base64
import http.server
import json
import threading

import pytest

import humo

RUTAS_BFF_SESION = ("/api/v1/web/sesion", "/api/v1/movil/sesion")


def _respondiendo(estados: dict[str, int | None]):
    """Doble de `pedir` que responde según la ruta; registra lo que se pidió."""
    pedidas = []

    def pedir(metodo, url, encabezados):
        pedidas.append((metodo, url, encabezados))
        for ruta, estado in estados.items():
            if url.endswith(ruta):
                return estado
        return 404

    pedir.pedidas = pedidas
    return pedir


BFF_SANO = {"/salud": 200, "/productos": 401, "/web/sesion": 401, "/movil/sesion": 401}


def test_cada_servicio_tiene_rutas():
    assert humo.SERVICIOS == ["bff", "nucleo", "web"]


def test_sin_fallas_cuando_todo_responde_lo_esperado():
    resultado = humo.fallas("nucleo", "https://nucleo.run.app", _respondiendo({"/v1/": 200}))
    assert resultado == humo.Resultado([], [])


def test_solo_pide_las_rutas_del_servicio():
    pedir = _respondiendo(BFF_SANO)
    humo.fallas("bff", "https://bff.run.app/", pedir)
    urls = [url for _, url, _ in pedir.pedidas]
    assert urls[:2] == [
        "https://bff.run.app/salud",
        "https://bff.run.app/api/v1/web/cotizaciones/productos",
    ]
    assert all(url.startswith("https://bff.run.app/") for url in urls)


@pytest.mark.parametrize("estado", [200, 401])
def test_ruta_protegida_acepta_401(estado):
    pedir = _respondiendo({"/": 200, "/productos": estado})
    assert humo.fallas("web", "https://web.run.app", pedir).errores == []


@pytest.mark.parametrize(
    ("estado", "mensaje"),
    [(502, "llegó 502"), (404, "llegó 404"), (None, "llegó sin respuesta")],
)
def test_informa_la_falla_sin_cuerpo(estado, mensaje):
    pedir = _respondiendo({"/": 200, "/productos": estado})
    (error,) = humo.fallas("web", "https://web.run.app", pedir).errores
    assert error == f"GET /api/v1/web/cotizaciones/productos: se esperaba 200 o 401, {mensaje}"


class TestSesion:
    """WEB-F13 (#155): /sesion rechaza sin token y con un token firmado con otra llave."""

    def test_prueba_los_dos_routers_sin_token_y_con_token_ajeno(self):
        pedir = _respondiendo(BFF_SANO)
        humo.fallas("bff", "https://bff.run.app", pedir)
        sesion = [(url, enc) for _, url, enc in pedir.pedidas if url.endswith(RUTAS_BFF_SESION)]
        assert len(sesion) == 4
        assert {"Cookie": f"sesion={humo._AJENO}"} in [enc for _, enc in sesion]
        assert {"Authorization": f"Bearer {humo._AJENO}"} in [enc for _, enc in sesion]
        assert sum(1 for _, enc in sesion if enc == {}) == 2

    def test_el_token_ajeno_es_un_jwt_hs256_que_el_bff_no_puede_validar(self):
        encabezado, carga, firma = humo.token_ajeno().split(".")
        decodificar = lambda parte: json.loads(base64.urlsafe_b64decode(parte + "==="))  # noqa: E731
        assert decodificar(encabezado)["alg"] == "HS256"
        assert decodificar(carga)["sub"] == "humo"
        assert firma
        assert humo.token_ajeno() != humo.token_ajeno()

    @pytest.mark.parametrize("estado", [200, 404, 500])
    def test_mientras_esta_pendiente_una_falla_solo_avisa(self, estado):
        pedir = _respondiendo({**BFF_SANO, "/web/sesion": estado})
        resultado = humo.fallas("bff", "https://bff.run.app", pedir)
        assert resultado.errores == []
        assert len(resultado.avisos) == 2
        assert all("[pendiente de #152]" in aviso for aviso in resultado.avisos)
        assert "(token ajeno)" in resultado.avisos[1]

    def test_al_quitar_la_marca_la_falla_detiene_el_despliegue(self, monkeypatch):
        sin_pendientes = tuple(
            humo.Ruta(r.servicio, r.metodo, r.ruta, r.esperados, r.encabezados, None, r.caso)
            for r in humo.RUTAS
        )
        monkeypatch.setattr(humo, "RUTAS", sin_pendientes)
        pedir = _respondiendo({**BFF_SANO, "/movil/sesion": 200})
        resultado = humo.fallas("bff", "https://bff.run.app", pedir)
        assert resultado.errores == [
            "GET /api/v1/movil/sesion (sin token): se esperaba 401, llegó 200",
            "GET /api/v1/movil/sesion (token ajeno): se esperaba 401, llegó 200",
        ]


def test_reintenta_hasta_que_la_revision_arranca():
    estados = iter([None, 503, 200])
    esperas = []

    def pedir(metodo, url, encabezados):
        return next(estados)

    assert humo.ejecutar("nucleo", "https://x", 5, 2, pedir, esperas.append).errores == []
    assert esperas == [2, 2]


def test_no_reintenta_por_rutas_pendientes():
    esperas = []
    pedir = _respondiendo({**BFF_SANO, "/web/sesion": 404})
    resultado = humo.ejecutar("bff", "https://x", 5, 2, pedir, esperas.append)
    assert resultado.errores == []
    assert len(resultado.avisos) == 2
    assert esperas == []


def test_agota_los_intentos_y_devuelve_el_ultimo_error():
    esperas = []
    resultado = humo.ejecutar("nucleo", "https://x", 3, 1, lambda m, u, e: 500, esperas.append)
    assert resultado.errores == ["GET /v1/: se esperaba 200, llegó 500"]
    assert esperas == [1, 1]


class _Manejador(http.server.BaseHTTPRequestHandler):
    def do_GET(self):  # noqa: N802 (nombre fijado por http.server)
        rutas = {"/salud": 200, "/api/v1/web/cotizaciones/productos": 401}
        estado = 401 if self.path.endswith("/sesion") else rutas.get(self.path, 404)
        if self.path == "/eco" and self.headers.get("Authorization") == "Bearer x":
            estado = 204
        self.send_response(estado)
        self.end_headers()
        self.wfile.write(b"detalle interno que no se debe imprimir")

    def log_message(self, *args):
        pass


@pytest.fixture
def servidor():
    with http.server.HTTPServer(("127.0.0.1", 0), _Manejador) as srv:
        hilo = threading.Thread(target=srv.serve_forever, daemon=True)
        hilo.start()
        yield f"http://127.0.0.1:{srv.server_port}"
        srv.shutdown()


def test_pedir_devuelve_el_estado_incluso_en_errores_http(servidor):
    assert humo.pedir("GET", servidor + "/salud", {}) == 200
    assert humo.pedir("GET", servidor + "/no-existe", {}) == 404


def test_pedir_envia_los_encabezados(servidor):
    assert humo.pedir("GET", servidor + "/eco", {"Authorization": "Bearer x"}) == 204


def test_pedir_sin_respuesta_devuelve_none():
    assert humo.pedir("GET", "http://127.0.0.1:9/", {}) is None


def test_main_no_imprime_cuerpos(servidor, capsys):
    assert humo.main(["--servicio", "bff", "--url", servidor, "--intentos", "1"]) == 0
    salida = capsys.readouterr().out
    assert "detalle interno" not in salida
    assert "::warning::" not in salida
    assert "6 rutas probadas, sin errores" in salida


def test_main_falla_con_anotacion_de_error(servidor, capsys):
    codigo = humo.main(["--servicio", "nucleo", "--url", servidor, "--intentos", "1"])
    assert codigo == 1
    assert capsys.readouterr().out.startswith("::error::Humo de nucleo: GET /v1/")


def test_main_avisa_las_pendientes_sin_fallar(capsys, monkeypatch):
    monkeypatch.setattr(humo, "pedir", _respondiendo({**BFF_SANO, "/movil/sesion": 404}))
    assert humo.main(["--servicio", "bff", "--url", "https://x", "--intentos", "1"]) == 0
    salida = capsys.readouterr().out
    assert salida.count("::warning::Humo de bff: GET /api/v1/movil/sesion") == 2
