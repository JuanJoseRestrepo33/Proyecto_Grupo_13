# Núcleo transaccional de Solventa

Monolito modular con fachada versionada. Una carpeta por capacidad de negocio bajo
`app/modulos/`, y una única interfaz pública en `app/fachada/v1/` que es lo que consumen
los BFF y los socios embebidos.

Estado: **WEB-F01 · cotización**. Los demás módulos del diseño (identidad, consentimiento,
perfilamiento, suscripción, pólizas, siniestros, pagos, socios) todavía no existen.

## Arrancar

```bash
cd backend
python3.12 -m venv .venv && source .venv/bin/activate
pip install -e '.[dev]'
uvicorn app.main:app --reload
```

| Ruta | Para qué |
|---|---|
| http://127.0.0.1:8000/docs | API navegable (OpenAPI) |
| http://127.0.0.1:8000/v1/ | Índice de la fachada. El CD lo usa como prueba de humo |
| http://127.0.0.1:8000/salud | Estado del servicio |

## Verificar lo mismo que el CI

```bash
ruff check . && ruff format --check .   # lint y formato
lint-imports                            # frontera entre módulos
pytest --cov=app --cov-report=term-missing
```

## Estructura

```
app/
├── compartido/        Tipos sin reglas de negocio: dinero, reloj, errores
├── fachada/v1/        Interfaz pública versionada. Agrega los routers de los módulos
└── modulos/
    └── cotizacion/
        ├── dominio/           Entidades y reglas. No conoce FastAPI ni la base de datos
        ├── aplicacion/        Casos de uso. Orquesta, no calcula
        ├── infraestructura/   Implementaciones de los puertos
        └── api/               Rutas HTTP y DTO del contrato v1
```

La dirección de las dependencias no es una convención: está declarada como contratos de
import-linter en `pyproject.toml` y el pipeline falla si alguien la rompe.

| Contrato | Qué impide |
|---|---|
| `api → aplicación → dominio` | Que el dominio importe la capa web |
| `infraestructura → dominio` | Que el dominio dependa de cómo se persiste |
| El dominio no conoce frameworks | Que FastAPI o pydantic se filtren a las reglas de negocio |
| El kernel no depende de módulos | Que `compartido/` acumule lógica de negocio |

## API v1 · cotización

| Método | Ruta | Qué hace |
|---|---|---|
| `GET` | `/v1/cotizacion/productos` | Catálogo. `?solo_web=false` incluye vida hipotecario |
| `POST` | `/v1/cotizacion/cotizaciones` | Solicita una cotización y devuelve la oferta (201) |
| `GET` | `/v1/cotizacion/cotizaciones/{id}` | Consulta la cotización y su estado |
| `POST` | `/v1/cotizacion/cotizaciones/{id}/recotizar` | Nueva cotización con los mismos datos (201) |
| `GET` | `/v1/cotizacion/cotizaciones/{id}/explicacion` | Factores del precio y versión de regla |

Los errores se devuelven como `application/problem+json` (RFC 9457).

```bash
curl -s -X POST localhost:8000/v1/cotizacion/cotizaciones \
  -H 'content-type: application/json' \
  -d '{"producto":"auto","asegurado":{"edad":34,"ciudad":"Bogotá"}}' | jq
```

### Reglas implementadas

1. **Sin consentimiento vigente, precio estándar.** Un cliente anónimo o sin
   consentimiento cotiza a la tarifa de catálogo y el ahorro es cero. El consentimiento se
   verifica **antes** de consultar el perfil, nunca al revés: si fuera al revés, una
   revocación seguiría produciendo una consulta de datos financieros (RC-04).
2. **La oferta vence a los 5 minutos.** Consultarla después sigue funcionando y devuelve
   `estado: "vencida"`, porque el canal necesita leer el estado para ofrecer recotizar.
   Quien no puede *tomar* una oferta vencida es suscripción (WEB-F03).
3. **Recotizar no reescribe.** Crea una cotización nueva que referencia la anterior por
   `id_cotizacion_origen`. Una oferta emitida nunca se modifica.
4. **Toda cotización deja una decisión auditada** con `version_regla`,
   `variables_entrada`, `id_cotizacion` e `id_consentimiento`. Con eso se recalcula la
   prima y da el mismo número (RC-08). Hay una prueba que lo verifica.
5. **El proveedor degradado no deja al cliente sin oferta.** Si el perfil viene de caché,
   el precio es el mismo y la base queda marcada como `open_finance_en_cache` (RC-03).

### Versionado del contrato

Dentro de `v1` solo se agregan campos opcionales. Quitar un campo, renombrarlo o cambiar
su tipo obliga a publicar `v2` y a mantener `v1` en pie (RC-05, RC-06).

## Lo que todavía es provisional

| Pieza | Hoy | Quién la reemplaza |
|---|---|---|
| Persistencia | Repositorio en memoria | Esquema relacional del módulo |
| Consentimiento | `ConsentimientoSimulado` | Módulo de consentimiento (WEB-F02) |
| Perfil de riesgo | `PerfilamientoSimulado` | Adaptador de Open Finance con circuit breaker (ARQ-02) |
| Log de auditoría | En memoria, append-only | Tabla con cadena de hash en PostgreSQL (ARQ-03) |

Las cuatro se cambian en un solo archivo: `app/modulos/cotizacion/api/dependencias.py`.
Ni el dominio ni los casos de uso se enteran. Esa es la prueba de que RC-05 se sostiene.
