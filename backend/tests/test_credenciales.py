"""Pruebas de las contraseñas con hash y sal (WEB-F13, criterio 2)."""

from __future__ import annotations

import time

import pytest

from app.modulos.identidad.dominio import credenciales
from app.modulos.identidad.dominio.credenciales import (
    ContrasenaDebilError,
    credencial_senuelo,
    derivar,
    exigir_contrasena_valida,
    verificar,
)

CLAVE = "Solventa2026"


def test_la_credencial_no_contiene_la_contrasena():
    credencial = derivar(CLAVE)

    assert CLAVE not in credencial
    assert credencial.startswith("scrypt$16384$8$1$")


def test_la_misma_contrasena_da_credenciales_distintas_por_la_sal():
    assert derivar(CLAVE) != derivar(CLAVE)


def test_la_contrasena_correcta_coincide_y_otra_no():
    credencial = derivar(CLAVE)

    assert verificar(CLAVE, credencial)
    assert not verificar("Solventa2027", credencial)
    assert not verificar(CLAVE.lower(), credencial)


@pytest.mark.parametrize(
    "credencial",
    ["", "texto-plano", "bcrypt$1$2$3$a$b", "scrypt$no$es$numero$a$b", "scrypt$16384$8$1$$"],
)
def test_una_credencial_ilegible_nunca_coincide(credencial):
    assert not verificar(CLAVE, credencial)


def test_se_verifica_con_los_parametros_guardados_y_no_con_los_actuales(monkeypatch):
    """Subir el costo después no invalida las credenciales que ya existen."""
    vieja = derivar(CLAVE)
    monkeypatch.setattr(credenciales, "COSTO_N", 2**15)

    assert verificar(CLAVE, vieja)
    assert derivar(CLAVE).startswith("scrypt$32768$")


def test_la_credencial_senuelo_es_estable_y_no_coincide_con_nada_comun():
    assert credencial_senuelo() == credencial_senuelo()
    assert not verificar(CLAVE, credencial_senuelo())


def test_verificar_contra_el_senuelo_tarda_lo_mismo_que_contra_una_real():
    """Si el correo no existe se paga el mismo costo de scrypt: el tiempo no lo delata."""
    real = derivar(CLAVE)
    credencial_senuelo()

    def medir(credencial: str) -> float:
        inicio = time.perf_counter()
        for _ in range(3):
            verificar("otra-clave-1", credencial)
        return time.perf_counter() - inicio

    real_t, senuelo_t = medir(real), medir(credencial_senuelo())

    assert senuelo_t > real_t * 0.3
    assert senuelo_t < real_t * 3


@pytest.mark.parametrize("contrasena", ["Clave123", "ñandú2026", "a1" * 32])
def test_una_contrasena_valida_pasa(contrasena):
    exigir_contrasena_valida(contrasena)


@pytest.mark.parametrize(
    "contrasena", ["Corta1", "sinnumeros", "12345678", "a1" * 32 + "x", "________"]
)
def test_una_contrasena_debil_se_rechaza(contrasena):
    with pytest.raises(ContrasenaDebilError):
        exigir_contrasena_valida(contrasena)
