"""Prueba de humo del BFF móvil simulado con Mockoon (WEB-F13, #154).

Recorre el contrato de registro, OTP, sesión e identidad contra un mockoon-cli ya encendido y
termina con código 1 si alguna respuesta no es la esperada. Pensado para correr con los tiempos
cortos que fija `mockoon.sh prueba`:

    MOCKOON_ESPERA_REENVIO=1 MOCKOON_VIGENCIA_OTP=3 MOCKOON_TTL_ACCESO=2

Uso: python3 probar_mockoon.py [URL_BASE]   (por defecto http://localhost:3001/api/v1/movil/)
"""

import json
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:3001/api/v1/movil/"
fallas = []


def llamar(metodo, ruta, cuerpo=None, token=None):
    datos = None if cuerpo is None else json.dumps(cuerpo).encode()
    peticion = urllib.request.Request(BASE + ruta, data=datos, method=metodo)
    peticion.add_header("Content-Type", "application/json")
    if token:
        peticion.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(peticion, timeout=5) as respuesta:
            return respuesta.status, json.loads(respuesta.read() or "null"), respuesta.headers
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read() or "null"), error.headers


def esperar(descripcion, obtenido, esperado):
    marca = "ok " if obtenido == esperado else "MAL"
    print(f"  [{marca}] {descripcion}: {obtenido}")
    if obtenido != esperado:
        fallas.append(f"{descripcion}: se esperaba {esperado}, llegó {obtenido}")


def clave(estado, cuerpo):
    return estado, (cuerpo or {}).get("codigo")


def registrar(documento, correo, nombre='Juan "JP" Pérez'):
    return llamar("POST", "registros", {
        "nombre_completo": nombre, "tipo_documento": "cedula_ciudadania", "numero_documento": documento,
        "correo": correo, "celular": "3109876543", "contrasena": "Clave2026", "version_terminos": "2026-09",
    })


def codigo_en_buzon(correo):
    estado, cuerpo, _ = llamar("GET", f"__pruebas/buzon?correo={correo}")
    assert estado == 200, cuerpo
    return cuerpo["codigo"]


def verificar(id_registro, codigo):
    return llamar("POST", f"registros/{id_registro}/otp/verificacion", {"codigo": codigo})


def documento(ultimo_digito):
    return f"10{uuid.uuid4().int % 10**7:07d}{ultimo_digito}"


def main():
    sufijo = uuid.uuid4().hex[:6]

    print("Registro")
    correo = f"Juan.{sufijo}@Ejemplo.co"
    doc = documento(0)
    estado, registro, _ = registrar(doc, correo)
    esperar("crear cuenta", (estado, registro["estado_identidad"]), (201, "pendiente_verificacion"))
    esperar("correo repetido (sin distinguir mayúsculas)",
            clave(*registrar(documento(1), correo.lower())[:2]), (409, "correo_ya_registrado"))
    esperar("documento repetido", clave(*registrar(doc, f"otro.{sufijo}@ejemplo.co")[:2]),
            (409, "documento_ya_registrado"))
    esperar("correo inválido", clave(*registrar(documento(2), "malo")[:2]), (422, "solicitud_invalida"))

    print("OTP")
    rid = registro["id"]
    esperar("código incorrecto", clave(*verificar(rid, "000000")[:2]), (422, "otp_invalido"))
    estado, cuerpo, _ = verificar(rid, "000001")
    esperar("intentos restantes", (estado, cuerpo.get("intentos_restantes")), (422, 1))
    esperar("tercer fallo bloquea", clave(*verificar(rid, "000002")[:2]), (409, "otp_bloqueado"))
    esperar("bloqueado aunque el código sea bueno", clave(*verificar(rid, codigo_en_buzon(correo))[:2]),
            (409, "otp_bloqueado"))
    estado, cuerpo, encabezados = llamar("POST", f"registros/{rid}/otp/reenvio")
    if estado == 429:
        esperar("reenvío prematuro trae Retry-After", encabezados.get("Retry-After") is not None, True)
        time.sleep(int(encabezados["Retry-After"]) + 0.2)
        estado, cuerpo, _ = llamar("POST", f"registros/{rid}/otp/reenvio")
    esperar("reenvío devuelve los intentos", (estado, cuerpo.get("intentos_restantes")), (202, 3))
    esperar("registro inexistente", clave(*verificar("reg-no-existe", "123456")[:2]),
            (404, "registro_no_encontrado"))
    estado, sesion, _ = verificar(rid, codigo_en_buzon(correo))
    esperar("código correcto abre sesión", (estado, sesion["cliente"]["nombre_completo"]), (200, 'Juan "JP" Pérez'))
    esperar("verificar de nuevo", clave(*verificar(rid, "123456")[:2]), (409, "registro_ya_verificado"))

    print("Identidad (documento terminado en 0: aprobada)")
    token = sesion["token_acceso"]
    esperar("sin verificación aún", clave(*llamar("GET", "identidad/verificacion", token=token)[:2]),
            (404, "verificacion_no_encontrada"))
    estado, kyc, _ = llamar("POST", "identidad/verificacion", token=token)
    esperar("solicitar KYC", (estado, kyc["estado"]), (202, "aprobada"))
    esperar("identidad ya verificada", clave(*llamar("POST", "identidad/verificacion", token=token)[:2]),
            (409, "identidad_ya_verificada"))
    esperar("la sesión refleja la identidad", llamar("GET", "sesion", token=token)[1]["estado_identidad"],
            "verificada")
    for digito, resultado in (("7", "rechazada"), ("8", "en_revision"), ("9", "en_proceso")):
        otro = f"kyc{digito}.{sufijo}@ejemplo.co"
        _, reg, _ = registrar(documento(digito), otro)
        _, s, _ = verificar(reg["id"], codigo_en_buzon(otro))
        estado, kyc, _ = llamar("POST", "identidad/verificacion", token=s["token_acceso"])
        esperar(f"documento terminado en {digito}", (estado, kyc["estado"]), (202, resultado))
        if digito == "7":
            esperar("rechazo con motivo", kyc.get("motivo"), "datos_no_coinciden")
        if digito == "9":
            consulta = llamar("GET", "identidad/verificacion", token=s["token_acceso"])[1]
            esperar("en_proceso se aprueba en la siguiente consulta", consulta["estado"], "aprobada")

    print("Ingreso y renovación")
    esperar("misma cuenta: entra sin registrarse de nuevo",
            llamar("POST", "sesiones", {"correo": correo, "contrasena": "Clave2026"})[0], 200)
    errada = clave(*llamar("POST", "sesiones", {"correo": correo, "contrasena": "Errada2026"})[:2])
    inexistente = clave(*llamar("POST", "sesiones", {"correo": f"nadie.{sufijo}@x.co", "contrasena": "Clave2026"})[:2])
    esperar("contraseña errada", errada, (401, "credenciales_invalidas"))
    esperar("correo inexistente da el mismo error", inexistente, errada)
    refresco = sesion["token_refresco"]
    estado, renovada, _ = llamar("POST", "sesiones/renovacion", {"token_refresco": refresco})
    esperar("renovar rota el token", (estado, renovada["token_refresco"] != refresco), (200, True))
    esperar("reutilizar el token anterior", clave(*llamar("POST", "sesiones/renovacion",
                                                          {"token_refresco": refresco})[:2]),
            (401, "token_refresco_invalido"))
    esperar("sin token", clave(*llamar("GET", "sesion")[:2]), (401, "sesion_no_vigente"))
    acceso = renovada["token_acceso"]
    esperar("token vigente", llamar("GET", "sesion", token=acceso)[0], 200)

    print("Vencimientos (tiempos de MOCKOON_*)")
    ttl = 2.2
    time.sleep(ttl)
    esperar("token de acceso vencido", clave(*llamar("GET", "sesion", token=acceso)[:2]), (401, "sesion_no_vigente"))
    esperar("el refresco sigue sirviendo",
            llamar("POST", "sesiones/renovacion", {"token_refresco": renovada["token_refresco"]})[0], 200)
    otro = f"vence.{sufijo}@ejemplo.co"
    _, reg, _ = registrar(documento(3), otro)
    # El mock compara en segundos enteros: un segundo de margen sobre MOCKOON_VIGENCIA_OTP=3.
    time.sleep(4.2)
    esperar("OTP vencido", clave(*verificar(reg["id"], codigo_en_buzon(otro))[:2]), (409, "otp_vencido"))

    print()
    if fallas:
        print(f"{len(fallas)} fallas:\n- " + "\n- ".join(fallas))
        sys.exit(1)
    print("Todo en orden.")


if __name__ == "__main__":
    main()
