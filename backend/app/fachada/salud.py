"""Estado del núcleo, módulo por módulo y dependencia por dependencia (ARQ-01, criterio 4).

Un `/salud` que solo dice `ok` no sirve para operar: cuando algo falla hay que saber qué
módulo y qué dependencia. Aquí cada módulo reporta las suyas y el núcleo las agrega.
"""

from __future__ import annotations

from collections.abc import Sequence

from fastapi import APIRouter, Response, status

from app.compartido.modulo import DescriptorDeModulo, EstadoDeDependencia

# Orden de gravedad. `simulado` no es una falla: el adaptador responde, solo que no es real.
_GRAVEDAD = {"ok": 0, "simulado": 0, "degradado": 1, "caido": 2}
_POR_GRAVEDAD = ("ok", "degradado", "caido")


def _peor(estados: Sequence[str]) -> str:
    gravedad = max((_GRAVEDAD[e] for e in estados), default=0)
    return _POR_GRAVEDAD[gravedad]


def estado_del_modulo(descriptor: DescriptorDeModulo) -> dict[str, object]:
    dependencias = tuple(descriptor.dependencias())
    if not dependencias and not descriptor.publica_rutas:
        estado = "esqueleto"
    else:
        estado = _peor([d.estado for d in dependencias])
    return {
        "estado": estado,
        "historia": descriptor.historia,
        "dependencias": {
            d.nombre: {"estado": d.estado, "detalle": d.detalle} for d in dependencias
        },
    }


def _agregar_dependencias(
    modulos: Sequence[DescriptorDeModulo],
) -> dict[str, dict[str, object]]:
    """Una misma dependencia vista por varios módulos aparece una vez, con el peor estado."""
    vistas: dict[str, list[tuple[str, EstadoDeDependencia]]] = {}
    for modulo in modulos:
        for dependencia in modulo.dependencias():
            vistas.setdefault(dependencia.nombre, []).append((modulo.nombre, dependencia))
    agregadas: dict[str, dict[str, object]] = {}
    for nombre, reportes in sorted(vistas.items()):
        _, peor = max(reportes, key=lambda r: _GRAVEDAD[r[1].estado])
        agregadas[nombre] = {
            "estado": peor.estado,
            "detalle": peor.detalle,
            "usada_por": sorted(modulo for modulo, _ in reportes),
        }
    return agregadas


def informe_de_salud(modulos: Sequence[DescriptorDeModulo], version: str) -> dict[str, object]:
    por_modulo = {m.nombre: estado_del_modulo(m) for m in modulos}
    activos = [str(info["estado"]) for info in por_modulo.values() if info["estado"] != "esqueleto"]
    return {
        "estado": _peor(activos),
        "version": version,
        "modulos": por_modulo,
        "dependencias": _agregar_dependencias(modulos),
    }


def construir_router(modulos: Sequence[DescriptorDeModulo], version: str) -> APIRouter:
    router = APIRouter(tags=["operación"])

    @router.get("/salud", summary="Estado del servicio, por módulo y por dependencia")
    def salud(response: Response) -> dict[str, object]:
        informe = informe_de_salud(modulos, version)
        if informe["estado"] == "caido":
            response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
        return informe

    return router
