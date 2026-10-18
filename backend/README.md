# Núcleo transaccional de Solventa

Monolito modular con fachada versionada. Una carpeta por capacidad de negocio bajo
`app/modulos/`, y una única interfaz pública en `app/fachada/v1/` que es lo que consumen
los BFF y los socios embebidos.

Estado: existe un módulo por cada capacidad de negocio del diseño (ARQ-01) más el módulo
transversal de **auditoría** (ARQ-03). Publican rutas **cotización** (WEB-F01) y
**auditoría**; los otros ocho son esqueletos con las cuatro capas creadas desde la
plantilla, a la espera de su historia. `GET /v1/` dice cuáles publican y
`GET /salud` en qué estado está cada uno.

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
| http://127.0.0.1:8000/v1/ | Índice de la fachada: capacidades publicadas y mapa de módulos. El CD lo usa como prueba de humo |
| http://127.0.0.1:8000/salud | Estado del núcleo, módulo por módulo y dependencia por dependencia. Responde 503 si alguna dependencia está caída |

## Verificar lo mismo que el CI

```bash
ruff check . && ruff format --check .   # lint y formato
lint-imports                            # frontera entre módulos
pytest --cov=app --cov-report=term-missing
```

## Estructura

```
app/
├── compartido/        Tipos sin reglas de negocio: dinero, reloj, errores, contrato de módulo
├── fachada/
│   ├── registro.py    Descubre los módulos de app/modulos y toma su descriptor MODULO
│   ├── salud.py       /salud: agrega el estado que reporta cada módulo
│   └── v1/            Interfaz pública versionada. Agrega los routers de los módulos
└── modulos/           Un paquete por capacidad de negocio, todos con la misma forma
    ├── cotizacion/    WEB-F01 · publica rutas
    ├── auditoria/     ARQ-03 · publica rutas; los demás módulos le escriben en proceso
    ├── consentimiento/ WEB-F02 · esqueleto
    ├── identidad/ perfilamiento/ suscripcion/ polizas/ siniestros/ pagos/ socios/
    └── <modulo>/
        ├── __init__.py        Interfaz pública del módulo y su descriptor MODULO
        ├── dominio/           Entidades y reglas. No conoce FastAPI ni la base de datos
        ├── aplicacion/        Casos de uso. Orquesta, no calcula
        ├── infraestructura/   Implementaciones de los puertos
        └── api/               Rutas HTTP y DTO del contrato v1
```

### Cómo se registra un módulo

La fachada no tiene una lista de módulos. Al arrancar recorre `app/modulos/`, importa cada
paquete y toma su `MODULO: DescriptorDeModulo` (nombre, historia, router y una función que
reporta sus dependencias). Un paquete sin descriptor rompe el arranque con un mensaje que
dice qué falta, en vez de quedar fuera en silencio.

Por eso un módulo nuevo no toca ningún archivo existente:

```bash
python scripts/nuevo_modulo.py garantias --historia WEB-F10 \
  --descripcion "Garantías extendidas para dispositivos"
lint-imports && pytest        # ya aplica los contratos y ya aparece en /v1/ y /salud
```

### Frontera entre módulos

La dirección de las dependencias no es una convención: está declarada como contratos de
import-linter en `pyproject.toml`, aplica a todos los módulos por comodín (`app.modulos.*`)
y el pipeline falla si alguien la rompe, señalando el archivo y la línea.

| Contrato | Qué impide |
|---|---|
| Un módulo no importa las entrañas de otro | Que `consentimiento` importe `cotizacion.dominio.modelo`. Importar `app.modulos.cotizacion` (su `__init__`, la interfaz pública) sí está permitido |
| `api → aplicación → dominio` | Que el dominio importe la capa web |
| `infraestructura → dominio` | Que el dominio dependa de cómo se persiste |
| El dominio no conoce frameworks | Que FastAPI o pydantic se filtren a las reglas de negocio |
| El kernel no depende de módulos ni de la fachada | Que `compartido/` acumule lógica de negocio |

### Qué reporta `/salud`

Cada módulo declara sus dependencias con un estado: `ok`, `simulado`, `degradado` o
`caido`. `simulado` existe porque en este prototipo casi todo responde desde un adaptador
simulado, y el estado del núcleo no debe hacer creer que ya está conectado a algo real.
Un módulo sin rutas ni dependencias se reporta como `esqueleto`. El estado global es el
peor de los módulos activos; `caido` devuelve 503 para que el orquestador lo saque de
rotación.

```json
{
  "estado": "ok",
  "modulos": {
    "cotizacion": {"estado": "ok", "historia": "WEB-F01", "dependencias": {"consentimiento": {"estado": "simulado", "detalle": "Adaptador simulado hasta WEB-F02"}}},
    "consentimiento": {"estado": "esqueleto", "historia": "WEB-F02", "dependencias": {}}
  },
  "dependencias": {"consentimiento": {"estado": "simulado", "usada_por": ["cotizacion"]}}
}
```

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
| Log de auditoría | **Ya es el módulo real** (`app/modulos/auditoria`, ARQ-03) | En memoria sin `AUDITORIA_DATABASE_URL`; PostgreSQL con ella |

Las tres primeras se cambian en un solo archivo: `app/modulos/cotizacion/api/dependencias.py`.
Ni el dominio ni los casos de uso se enteran. Esa es la prueba de que RC-05 se sostiene.
La cuarta ya se cambió ahí mismo, y fue el único archivo de cotización que tocó ARQ-03
además de agregar `id_cliente` al puerto.

## Módulo de auditoría (ARQ-03)

Registro inmutable y verificable de decisiones de pricing y suscripción y de eventos de
consentimiento (RC-05, RC-08). Los módulos escriben en proceso a través de la interfaz
pública (`app.modulos.auditoria.obtener_servicio().registrar(evento)`); por HTTP solo se
lee.

| Método | Ruta | Qué hace |
|---|---|---|
| `GET` | `/v1/auditoria/registros/{id_decision}` | Reconstruye una decisión tal como quedó registrada |
| `GET` | `/v1/auditoria/integridad` | Recorre la cadena completa y señala los eslabones rotos |

### Cómo se protege

Cada registro lleva el hash del anterior y su propio hash cubre el evento **y su número
de secuencia**. Con PostgreSQL hay tres capas, y cada una tiene una prueba que intenta
romperla:

| Capa | Qué impide | Quién la salta |
|---|---|---|
| Permisos del rol `solventa_app` | `UPDATE`, `DELETE`, `TRUNCATE` desde la aplicación | Un administrador |
| Triggers `BEFORE UPDATE/DELETE/TRUNCATE` | Lo mismo, con cualquier rol | Un administrador que los desactive |
| Cadena de hash | Nada: no impide, **detecta**. La verificación señala exactamente el registro alterado | Nadie sin reescribir toda la cadena |

La secuencia y el hash anterior se asignan dentro de un `pg_advisory_xact_lock`, en la
misma transacción que el `INSERT`. Es la corrección que salió de EXP-04: asignarlos fuera
del cerrojo producía falsos eslabones rotos con escritores concurrentes. Hay una prueba de
veinte escrituras simultáneas contra la base real.

### Configuración

| Variable | Efecto |
|---|---|
| `AUDITORIA_DATABASE_URL` | DSN de PostgreSQL. El esquema `auditoria` y el rol se crean al arrancar (idempotente). Sin ella, el log vive en memoria y `/salud` lo reporta como `simulado` |

La escritura se difiere a un hilo propio (`ColaDeEscritura`) para no cargar la latencia de
la cotización: 0,81 ms síncrona frente a 0,01 ms encolada, medido en EXP-04. El costo es
que un fallo de escritura no llega al llamador, solo al log de la aplicación; `/salud`
muestra cuántas escrituras hay pendientes.

Para correr las pruebas de la base en local:

```bash
docker run -d --name auditoria-pg -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=solventa -p 5432:5432 postgres:15-alpine
AUDITORIA_DATABASE_URL=postgresql://postgres:postgres@localhost:5432/solventa pytest
```

Sin la variable, esas pruebas se saltan y el resto corre en memoria.
