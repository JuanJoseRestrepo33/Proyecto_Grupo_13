import base64
import http.server
import json
import threading

import pytest

import humo

RUTAS_BFF_SESION = ("/api/v1/web/sesion", "/api/v1/movil/sesion")
CATALOGO = json.dumps(
    [{"codigo": c} for c in ("cotizacion", "suscripcion", "prevencion_fraude")]
).encode()
POLIZAS = json.dumps([{"numero": "POL-1", "estado": "vigente"}]).encode()
CREDENCIALES = humo.Credenciales("humo@solventa.test", "clave-de-prueba")
AUTENTICADAS = ("/movil/consentimientos/finalidades", "/movil/polizas")


def _respondiendo(estados: dict[str, int | None | humo.Respuesta]):
    """Doble de `pedir` que responde según el final de la ruta; registra lo que se pidió."""
    pedidas = []

    def pedir(metodo, url, encabezados, datos=None):
        pedidas.append((metodo, url, encabezados, datos))
        for ruta, estado in estados.items():
            if url.endswith(ruta):
                return estado if isinstance(estado, humo.Respuesta) else humo.Respuesta(estado)
        return humo.Respuesta(404)

    pedir.pedidas = pedidas
    return pedir


BFF_SANO = {
    "/salud": 200,
    "/productos": 401,
    "/web/sesion": 401,
    "/movil/sesion": 401,
    "/consentimientos/finalidades": 401,
    "/consentimientos": 401,
    "/revocacion": 401,
    "/renovacion": 401,
    "/evidencias": 401,
    "/polizas": 401,
    "/siniestros": 401,
    "/siniestros/humo-no-existe": 401,
}
SESION = humo.Respuesta(200, json.dumps({"token_acceso": "tok-123"}).encode())


def _con_sesion(pedir, cuerpos: dict[str, bytes]):
    """Envuelve `pedir`: con el token de la cuenta de prueba, las rutas de `cuerpos` dan 200."""

    def pedir_con_sesion(metodo, url, encabezados, datos=None):
        if encabezados.get("Authorization") == "Bearer tok-123":
            for ruta, cuerpo in cuerpos.items():
                if url.endswith(ruta):
                    pedir.pedidas.append((metodo, url, encabezados, datos))
                    return humo.Respuesta(200, cuerpo)
        return pedir(metodo, url, encabezados, datos)

    return pedir_con_sesion


@pytest.fixture
def con_pendiente(monkeypatch):
    """Una fila pendiente, como las que agrega una parte Cloud antes que el código que la cumple."""
    fila = humo.Ruta("bff", "GET", "/api/v1/web/futura", frozenset({401}), pendiente="#999")
    monkeypatch.setattr(humo, "RUTAS", (*humo.RUTAS, fila))
    return fila


def _urls(pedir):
    return [url for _, url, _, _ in pedir.pedidas]


def test_cada_servicio_tiene_rutas():
    assert humo.SERVICIOS == ["bff", "nucleo", "web"]


def test_sin_fallas_cuando_todo_responde_lo_esperado():
    resultado = humo.fallas("nucleo", "https://nucleo.run.app", _respondiendo({"/v1/": 200}))
    assert resultado == humo.Resultado([], [])


def test_solo_pide_las_rutas_del_servicio():
    pedir = _respondiendo(BFF_SANO)
    humo.fallas("bff", "https://bff.run.app/", pedir)
    assert _urls(pedir)[:2] == [
        "https://bff.run.app/salud",
        "https://bff.run.app/api/v1/web/cotizaciones/productos",
    ]
    assert all(url.startswith("https://bff.run.app/") for url in _urls(pedir))


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
        sesion = [enc for _, url, enc, _ in pedir.pedidas if url.endswith(RUTAS_BFF_SESION)]
        assert len(sesion) == 4
        assert {"Cookie": f"sesion={humo._AJENO}"} in sesion
        assert {"Authorization": f"Bearer {humo._AJENO}"} in sesion
        assert sesion.count({}) == 2

    def test_el_token_ajeno_es_un_jwt_hs256_que_el_bff_no_puede_validar(self):
        encabezado, carga, firma = humo.token_ajeno().split(".")
        decodificar = lambda parte: json.loads(base64.urlsafe_b64decode(parte + "==="))  # noqa: E731
        assert decodificar(encabezado)["alg"] == "HS256"
        assert decodificar(carga)["sub"] == "humo"
        assert firma
        assert humo.token_ajeno() != humo.token_ajeno()

    def test_aceptar_un_token_ajeno_detiene_el_despliegue(self):
        pedir = _respondiendo({**BFF_SANO, "/movil/sesion": 200})
        resultado = humo.fallas("bff", "https://bff.run.app", pedir)
        assert resultado.errores == [
            "GET /api/v1/movil/sesion (sin token): se esperaba 401, llegó 200",
            "GET /api/v1/movil/sesion (token ajeno): se esperaba 401, llegó 200",
        ]


class TestPendientes:
    """Una fila pendiente se prueba igual, pero su falla solo avisa hasta quitarle la marca."""

    def test_no_quedan_filas_pendientes(self):
        assert [r.ruta for r in humo.RUTAS if r.pendiente] == []

    @pytest.mark.parametrize("estado", [200, 404, 500])
    def test_mientras_esta_pendiente_una_falla_solo_avisa(self, con_pendiente, estado):
        pedir = _respondiendo({**BFF_SANO, "/futura": estado})
        resultado = humo.fallas("bff", "https://bff.run.app", pedir)
        assert resultado.errores == []
        assert resultado.avisos[-1] == (
            f"GET /api/v1/web/futura: se esperaba 401, llegó {estado} [pendiente de #999]"
        )

    def test_una_pendiente_que_ya_cumple_no_avisa(self, con_pendiente):
        pedir = _respondiendo({**BFF_SANO, "/futura": 401})
        avisos = humo.fallas("bff", "https://bff.run.app", pedir).avisos
        assert not [a for a in avisos if "/futura" in a]


class TestConsentimientoMovil:
    """MOB-F02 (#161): rutas de consentimiento del router móvil en staging."""

    def test_prueba_las_cinco_operaciones_sin_token(self):
        pedir = _respondiendo(BFF_SANO)
        humo.fallas("bff", "https://bff.run.app", pedir)
        consentimiento = [
            (metodo, url.removeprefix("https://bff.run.app"), enc)
            for metodo, url, enc, _ in pedir.pedidas
            if "/movil/consentimientos" in url
        ]
        assert consentimiento == [
            ("GET", "/api/v1/movil/consentimientos/finalidades", {}),
            ("GET", "/api/v1/movil/consentimientos", {}),
            ("POST", "/api/v1/movil/consentimientos", {}),
            ("POST", "/api/v1/movil/consentimientos/humo-no-existe/revocacion", {}),
            ("POST", "/api/v1/movil/consentimientos/humo-no-existe/renovacion", {}),
        ]

    def test_sin_credenciales_omite_la_fila_autenticada_con_aviso(self):
        resultado = humo.fallas("bff", "https://bff.run.app", _respondiendo(BFF_SANO))
        assert resultado.errores == []
        assert resultado.avisos == [
            f"GET /api/v1{ruta} (con sesión): se omite, faltan HUMO_CORREO y HUMO_CLAVE"
            for ruta in AUTENTICADAS
        ]

    def test_con_sesion_el_catalogo_trae_las_tres_finalidades(self):
        pedir = _respondiendo({**BFF_SANO, "/sesiones": SESION})
        con_sesion = _con_sesion(pedir, {"/finalidades": CATALOGO, "/polizas": POLIZAS})

        resultado = humo.fallas("bff", "https://bff.run.app", con_sesion, CREDENCIALES)
        assert resultado == humo.Resultado([], [])

        (ingreso,) = [p for p in pedir.pedidas if p[1].endswith("/movil/sesiones")]
        assert ingreso[0] == "POST"
        assert json.loads(ingreso[3]) == {
            "correo": "humo@solventa.test",
            "contrasena": "clave-de-prueba",
        }

    def test_un_catalogo_incompleto_es_una_falla(self):
        incompleto = json.dumps([{"codigo": "cotizacion"}]).encode()
        pedir = _respondiendo({**BFF_SANO, "/sesiones": SESION})
        con_sesion = _con_sesion(pedir, {"/finalidades": incompleto, "/polizas": POLIZAS})

        errores = humo.fallas("bff", "https://x", con_sesion, CREDENCIALES).errores
        assert errores == [
            "GET /api/v1/movil/consentimientos/finalidades (con sesión): "
            "faltan finalidades: prevencion_fraude, suscripcion"
        ]

    @pytest.mark.parametrize(
        "respuesta",
        [humo.Respuesta(401), humo.Respuesta(200, b"no es json"), humo.Respuesta(None)],
    )
    def test_si_no_puede_iniciar_sesion_lo_informa_sin_credenciales(self, respuesta):
        pedir = _respondiendo({**BFF_SANO, "/sesiones": respuesta})
        errores = humo.fallas("bff", "https://x", pedir, CREDENCIALES).errores
        assert errores == [
            f"GET /api/v1{ruta} (con sesión): no se pudo iniciar sesión" for ruta in AUTENTICADAS
        ]
        assert "clave-de-prueba" not in " ".join(errores)

    @pytest.mark.parametrize("cuerpo", [b"[]", b'{"codigo": 1}', b"nada"])
    def test_validar_catalogo(self, cuerpo):
        assert humo._tres_finalidades(cuerpo)
        assert humo._tres_finalidades(CATALOGO) is None

    def test_las_credenciales_salen_del_ambiente_y_no_se_imprimen(self):
        entorno = {"HUMO_CORREO": "a@b.co", "HUMO_CLAVE": "secreta"}
        credenciales = humo.credenciales_del_ambiente(entorno)
        assert credenciales == humo.Credenciales("a@b.co", "secreta")
        assert "secreta" not in repr(credenciales)
        assert humo.credenciales_del_ambiente({"HUMO_CORREO": "a@b.co"}) is None
        assert humo.credenciales_del_ambiente({}) is None


class TestSiniestros:
    """WEB-F05 (#170) y MOB-F05 (#177): pólizas, siniestros y evidencias exigen sesión."""

    def test_pide_las_rutas_de_los_dos_routers_sin_token(self):
        pedir = _respondiendo(BFF_SANO)
        humo.fallas("bff", "https://bff.run.app", pedir)
        siniestros = [
            (metodo, url.removeprefix("https://bff.run.app"), enc)
            for metodo, url, enc, _ in pedir.pedidas
            if url.endswith("/polizas") or "/siniestros" in url
        ]
        assert siniestros == [
            (metodo, f"{prefijo}{ruta}", {})
            for prefijo in ("/api/v1/web", "/api/v1/movil")
            for metodo, ruta in (
                ("GET", "/polizas"),
                ("GET", "/siniestros"),
                ("POST", "/siniestros"),
                ("GET", "/siniestros/humo-no-existe"),
                ("GET", "/siniestros/humo-no-existe/evidencias"),
                ("POST", "/siniestros/humo-no-existe/evidencias"),
            )
        ]

    def test_el_humo_no_crea_siniestros(self):
        pedir = _respondiendo({**BFF_SANO, "/sesiones": SESION})
        humo.fallas("bff", "https://x", _con_sesion(pedir, {}), CREDENCIALES)
        assert all(datos is None for _, url, _, datos in pedir.pedidas if "/siniestros" in url)

    @pytest.mark.parametrize("estado", [200, 404, 500])
    def test_una_ruta_sin_proteger_o_ausente_detiene_el_despliegue(self, estado):
        pedir = _respondiendo({**BFF_SANO, "/siniestros": estado})
        errores = humo.fallas("bff", "https://bff.run.app", pedir).errores
        assert errores == [
            f"{metodo} /api/v1/{router}/siniestros ({caso}): se esperaba 401, llegó {estado}"
            for router, caso in (("web", "sin cookie"), ("movil", "sin token"))
            for metodo in ("GET", "POST")
        ]

    @pytest.mark.parametrize(
        ("cuerpo", "problema"),
        [
            (b"[]", "la cuenta de prueba no tiene pólizas vigentes"),
            (b'[{"estado": "vencida"}]', "la cuenta de prueba no tiene pólizas vigentes"),
            (b"no es json", "la respuesta no es una lista de pólizas"),
            (b'[{"numero": "sin estado"}]', "la respuesta no es una lista de pólizas"),
        ],
    )
    def test_la_cuenta_de_prueba_debe_ver_una_poliza_vigente(self, cuerpo, problema):
        pedir = _respondiendo({**BFF_SANO, "/sesiones": SESION})
        con_sesion = _con_sesion(pedir, {"/finalidades": CATALOGO, "/polizas": cuerpo})
        errores = humo.fallas("bff", "https://x", con_sesion, CREDENCIALES).errores
        assert errores == [f"GET /api/v1/movil/polizas (con sesión): {problema}"]


def test_reintenta_hasta_que_la_revision_arranca():
    estados = iter([None, 503, 200])
    esperas = []

    def pedir(metodo, url, encabezados, datos=None):
        return humo.Respuesta(next(estados))

    assert humo.ejecutar("nucleo", "https://x", 5, 2, pedir, esperas.append).errores == []
    assert esperas == [2, 2]


def test_no_reintenta_por_rutas_pendientes(con_pendiente):
    esperas = []
    pedir = _respondiendo({**BFF_SANO, "/futura": 404})
    resultado = humo.ejecutar("bff", "https://x", 5, 2, pedir, esperas.append)
    assert resultado.errores == []
    assert esperas == []


def test_agota_los_intentos_y_devuelve_el_ultimo_error():
    esperas = []

    def falla(metodo, url, encabezados, datos=None):
        return humo.Respuesta(500)

    resultado = humo.ejecutar("nucleo", "https://x", 3, 1, falla, esperas.append)
    assert resultado.errores == ["GET /v1/: se esperaba 200, llegó 500"]
    assert esperas == [1, 1]


class _Manejador(http.server.BaseHTTPRequestHandler):
    def _responder(self):
        rutas = {"/salud": 200, "/api/v1/web/cotizaciones/productos": 401}
        protegida = (
            self.path.endswith(("/sesion", "/evidencias", "/polizas"))
            or "/consentimientos" in self.path
            or "/siniestros" in self.path
        )
        estado = 401 if protegida else rutas.get(self.path, 404)
        if self.path == "/eco" and self.headers.get("Authorization") == "Bearer x":
            estado = 204
        if self.path == "/eco-cuerpo":
            largo = int(self.headers.get("Content-Length", 0))
            self.send_response(200)
            self.end_headers()
            self.wfile.write(self.rfile.read(largo))
            return
        self.send_response(estado)
        self.end_headers()
        self.wfile.write(b"detalle interno que no se debe imprimir")

    do_GET = _responder  # noqa: N815 (nombre fijado por http.server)
    do_POST = _responder  # noqa: N815

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
    assert humo.pedir("GET", servidor + "/salud", {}).estado == 200
    assert humo.pedir("GET", servidor + "/no-existe", {}).estado == 404


def test_pedir_envia_encabezados_y_cuerpo(servidor):
    assert humo.pedir("GET", servidor + "/eco", {"Authorization": "Bearer x"}).estado == 204
    respuesta = humo.pedir("POST", servidor + "/eco-cuerpo", {}, b'{"a": 1}')
    assert respuesta == humo.Respuesta(200, b'{"a": 1}')


def test_pedir_sin_respuesta_devuelve_none():
    assert humo.pedir("GET", "http://127.0.0.1:9/", {}).estado is None


def test_main_no_imprime_cuerpos(servidor, capsys, monkeypatch):
    monkeypatch.delenv("HUMO_CORREO", raising=False)
    assert humo.main(["--servicio", "bff", "--url", servidor, "--intentos", "1"]) == 0
    salida = capsys.readouterr().out
    assert "detalle interno" not in salida
    assert "se omite, faltan HUMO_CORREO y HUMO_CLAVE" in salida
    rutas = sum(r.servicio == "bff" for r in humo.RUTAS)
    assert f"{rutas} rutas probadas, sin errores" in salida


def test_main_falla_con_anotacion_de_error(servidor, capsys):
    codigo = humo.main(["--servicio", "nucleo", "--url", servidor, "--intentos", "1"])
    assert codigo == 1
    assert capsys.readouterr().out.startswith("::error::Humo de nucleo: GET /v1/")


def test_main_avisa_las_pendientes_sin_fallar(capsys, monkeypatch, con_pendiente):
    monkeypatch.setattr(humo, "pedir", _respondiendo({**BFF_SANO, "/futura": 404}))
    assert humo.main(["--servicio", "bff", "--url", "https://x", "--intentos", "1"]) == 0
    salida = capsys.readouterr().out
    assert "::warning::Humo de bff: GET /api/v1/web/futura" in salida


def test_main_usa_las_credenciales_del_ambiente_sin_imprimirlas(capsys, monkeypatch):
    monkeypatch.setenv("HUMO_CORREO", "humo@solventa.test")
    monkeypatch.setenv("HUMO_CLAVE", "clave-muy-secreta")
    monkeypatch.setattr(humo, "pedir", _respondiendo({**BFF_SANO, "/sesiones": 401}))
    assert humo.main(["--servicio", "bff", "--url", "https://x", "--intentos", "1"]) == 1
    salida = capsys.readouterr().out
    assert "no se pudo iniciar sesión" in salida
    assert "clave-muy-secreta" not in salida
    assert "humo@solventa.test" not in salida
