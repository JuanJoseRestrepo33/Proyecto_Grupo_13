import http.server
import threading

import pytest

import humo


def _respondiendo(estados: dict[str, int | None]):
    """Doble de `pedir` que responde según la ruta; registra lo que se pidió."""
    pedidas = []

    def pedir(metodo, url):
        pedidas.append((metodo, url))
        for ruta, estado in estados.items():
            if url.endswith(ruta):
                return estado
        return 404

    pedir.pedidas = pedidas
    return pedir


def test_cada_servicio_tiene_rutas():
    assert humo.SERVICIOS == ["bff", "nucleo", "web"]


def test_sin_fallas_cuando_todo_responde_lo_esperado():
    pedir = _respondiendo({"/v1/": 200})
    assert humo.fallas("nucleo", "https://nucleo.run.app", pedir) == []


def test_solo_pide_las_rutas_del_servicio():
    pedir = _respondiendo({"/salud": 200, "/productos": 401})
    humo.fallas("bff", "https://bff.run.app/", pedir)
    assert pedir.pedidas == [
        ("GET", "https://bff.run.app/salud"),
        ("GET", "https://bff.run.app/api/v1/web/cotizaciones/productos"),
    ]


@pytest.mark.parametrize("estado", [200, 401])
def test_ruta_protegida_acepta_401(estado):
    pedir = _respondiendo({"/": 200, "/productos": estado})
    assert humo.fallas("web", "https://web.run.app", pedir) == []


@pytest.mark.parametrize(
    ("estado", "mensaje"),
    [(502, "llegó 502"), (404, "llegó 404"), (None, "llegó sin respuesta")],
)
def test_informa_la_falla_sin_cuerpo(estado, mensaje):
    pedir = _respondiendo({"/": 200, "/productos": estado})
    (error,) = humo.fallas("web", "https://web.run.app", pedir)
    assert error == f"GET /api/v1/web/cotizaciones/productos: se esperaba 200 o 401, {mensaje}"


def test_reintenta_hasta_que_la_revision_arranca():
    estados = iter([None, 503, 200])
    esperas = []

    def pedir(metodo, url):
        return next(estados)

    assert humo.ejecutar("nucleo", "https://x", 5, 2, pedir, esperas.append) == []
    assert esperas == [2, 2]


def test_agota_los_intentos_y_devuelve_el_ultimo_error():
    esperas = []
    errores = humo.ejecutar("nucleo", "https://x", 3, 1, lambda m, u: 500, esperas.append)
    assert errores == ["GET /v1/: se esperaba 200, llegó 500"]
    assert esperas == [1, 1]


class _Manejador(http.server.BaseHTTPRequestHandler):
    def do_GET(self):  # noqa: N802 (nombre fijado por http.server)
        estado = {"/salud": 200, "/api/v1/web/cotizaciones/productos": 401}.get(self.path, 404)
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
    assert humo.pedir("GET", servidor + "/salud") == 200
    assert humo.pedir("GET", servidor + "/no-existe") == 404


def test_pedir_sin_respuesta_devuelve_none():
    assert humo.pedir("GET", "http://127.0.0.1:9/") is None


def test_main_no_imprime_cuerpos(servidor, capsys):
    assert humo.main(["--servicio", "bff", "--url", servidor, "--intentos", "1"]) == 0
    salida = capsys.readouterr().out
    assert "detalle interno" not in salida
    assert "2 rutas" in salida


def test_main_falla_con_anotacion_de_error(servidor, capsys):
    codigo = humo.main(["--servicio", "nucleo", "--url", servidor, "--intentos", "1"])
    assert codigo == 1
    assert capsys.readouterr().out.startswith("::error::Humo de nucleo: GET /v1/")
