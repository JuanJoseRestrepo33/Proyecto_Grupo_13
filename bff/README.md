# BFF de Solventa

*Backend for Frontend* de los dos canales, en un solo servicio con dos routers
([ADR 0001](../docs/adr/0001-bff-unico-dos-routers.md)):

| Prefijo | Contrato | Cliente |
|---|---|---|
| `/api/v1/web/` | [`contratos/bff-web`](../contratos/bff-web/openapi.yaml) | La web, a través del proxy de nginx de `solventa-web` (mismo origen) |
| `/api/v1/movil/` | [`contratos/bff-movil`](../contratos/bff-movil/openapi.yaml) | La app Android, directo |

El BFF orquesta el núcleo por HTTP (`NUCLEO_URL`) y nunca importa su código. Las diferencias
entre un contrato de canal y la API `/v1` del núcleo se resuelven aquí, no en los clientes.

## Arrancar

```bash
cd bff
python3.12 -m venv .venv && source .venv/bin/activate
pip install -e '.[dev]'
uvicorn app.main:app --reload --port 8001
```

Con el núcleo corriendo en el puerto 8000, el BFF no necesita más configuración para atender
en local.

| Variable | Sin ella | Con ella |
|---|---|---|
| `PORT` | El contenedor escucha en 8000 | Escucha en ese puerto. Cloud Run la inyecta |
| `NUCLEO_URL` | Apunta a `http://127.0.0.1:8000` | URL del núcleo, sin barra final |

## Verificar lo mismo que el CI

```bash
ruff check . && ruff format --check .   # lint y formato
pytest --cov=app --cov-report=term-missing
```
