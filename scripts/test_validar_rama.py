import pytest

import validar_rama as vr


@pytest.mark.parametrize(
    ("origen", "destino"),
    [
        ("feature/WEB-F02-consentimiento-web", "develop"),
        ("feature/MOB-F01-onboarding-biometrico", "develop"),
        ("fix/ARQ-03-cadena-hash", "develop"),
        ("docs/ARQ-04-flujo-de-trabajo", "develop"),
        ("ci/ARQ-04-despliegue", "develop"),
        ("feature/12-ajuste-tablero", "develop"),
        ("feature/WEB-F01-01-cotizador", "develop"),
        ("dependabot/github_actions/actions/checkout-5", "develop"),
        ("release/v0.1.0", "main"),
        ("release/v1.0.0", "main"),
        ("hotfix/v0.1.1", "main"),
        ("release/v0.1.0", "develop"),
        ("hotfix/v0.1.1", "develop"),
        ("main", "develop"),
        ("fix/WEB-F02-texto-legal", "release/v0.1.0"),
        ("docs/ARQ-04-notas-release", "release/v0.2.0"),
    ],
)
def test_flujos_permitidos(origen, destino):
    assert vr.errores(origen, destino) == []


@pytest.mark.parametrize(
    ("origen", "destino"),
    [
        ("feature/WEB-F02-consentimiento-web", "main"),
        ("develop", "main"),
        ("fix/ARQ-03-cadena-hash", "main"),
        ("feature/consentimiento", "develop"),
        ("feature/WEB-F02-Consentimiento", "develop"),
        ("mi-rama", "develop"),
        ("release/v0.1.1", "main"),
        ("release/0.1.0", "main"),
        ("hotfix/v0.1.0", "main"),
        ("feature/WEB-F02-consentimiento-web", "release/v0.1.0"),
        ("develop", "release/v0.1.0"),
        ("feature/WEB-F02-algo", "feature/WEB-F01-otra"),
    ],
)
def test_flujos_rechazados(origen, destino):
    assert vr.errores(origen, destino)


def test_cli(capsys):
    assert vr.main(["--origen", "release/v0.1.0", "--destino", "main"]) == 0
    assert vr.main(["--origen", "develop", "--destino", "main"]) == 1
    assert "Flujos permitidos" in capsys.readouterr().err
