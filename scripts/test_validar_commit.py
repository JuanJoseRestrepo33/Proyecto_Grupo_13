import subprocess

import pytest

import validar_commit as vc


@pytest.mark.parametrize(
    "mensaje",
    [
        "feat(consentimiento): revocar consentimiento desde la web (WEB-F02)",
        "ci: pipeline de integración continua (ARQ-04)",
        "fix(mobile)!: cambiar contrato de onboarding\n\nRefs: MOB-F01",
        "docs: guía de contribución\n\nCloses #12",
        "feat(web): cotizador por pasos (WEB-F01-01)",
        "Merge pull request #90 from equipo/feature/ARQ-04",
        "# comentario de git\nchore(deps): actualizar fastapi (#45)",
        "chore(release): v0.1.0",
        "chore(release): v0.1.1 integrar hotfix en develop",
    ],
)
def test_mensajes_validos(mensaje):
    assert vc.errores(mensaje) == []


@pytest.mark.parametrize(
    ("mensaje", "fragmento"),
    [
        ("arreglos varios", "Conventional Commits"),
        ("Feat: algo (ARQ-04)", "Conventional Commits"),
        ("feat: sin historia", "historia"),
        ("chore(release): sin versión", "historia"),
        ("feat(Web): alcance en mayúscula (WEB-F01)", "Conventional Commits"),
        (f"feat: {'x' * 100} (ARQ-04)", "caracteres"),
        ("\n\n", "vacío"),
    ],
)
def test_mensajes_invalidos(mensaje, fragmento):
    assert any(fragmento in error for error in vc.errores(mensaje))


def test_cli_mensaje(capsys):
    assert vc.main(["--mensaje", "ci: pipeline (ARQ-04)"]) == 0
    assert vc.main(["--mensaje", "pipeline"]) == 1
    assert "Formato esperado" in capsys.readouterr().err


def test_cli_archivo(tmp_path):
    archivo = tmp_path / "COMMIT_EDITMSG"
    archivo.write_text("test: validador de commits (ARQ-04)\n", encoding="utf-8")
    assert vc.main([str(archivo)]) == 0


def test_cli_rango_ignora_merges(tmp_path, monkeypatch):
    def git(*args):
        subprocess.run(["git", *args], cwd=tmp_path, check=True, capture_output=True)

    git("init", "-q", "-b", "main")
    git("config", "user.email", "ci@example.com")
    git("config", "user.name", "CI")
    git("commit", "-q", "--allow-empty", "-m", "chore: inicio (ARQ-04)")
    git("checkout", "-q", "-b", "rama")
    git("commit", "-q", "--allow-empty", "-m", "feat: algo (ARQ-01)")
    git("commit", "-q", "--allow-empty", "-m", "cambios sin convención")
    monkeypatch.chdir(tmp_path)

    commits = vc.commits_del_rango("main..rama")

    assert [m.strip() for _, m in commits] == ["cambios sin convención", "feat: algo (ARQ-01)"]
    assert vc.main(["--rango", "main..rama"]) == 1
