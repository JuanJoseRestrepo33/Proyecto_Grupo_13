# Núcleo transaccional de Solventa

Monolito modular con fachada versionada. Una carpeta por capacidad de negocio bajo
`app/modulos/`, y una única interfaz pública en `app/fachada/v1/` que es lo que consumen
los BFF y los socios embebidos.

Estado: existe un módulo por cada capacidad de negocio del diseño (ARQ-01) más el módulo
transversal de **auditoría** (ARQ-03). Publican rutas **cotización** (WEB-F01),
**consentimiento** (WEB-F02) y **auditoría**; **perfilamiento** tiene su caché y escucha
revocaciones; los demás son esqueletos con las cuatro capas creadas desde la plantilla, a
la espera de su historia. `GET /v1/` dice cuáles publican y
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

Toda la configuración entra por variables de entorno; no hay archivos de configuración por ambiente.

| Variable | Sin ella | Con ella |
|---|---|---|
| `PORT` | El contenedor escucha en 8000 | Escucha en ese puerto. Cloud Run la inyecta |
| `TOKENS_LLAVE_PRIVADA` | Llave efímera al arrancar: los tokens solo los valida este proceso. En Cloud Run, `/salud` responde 503 | Firma los tokens de sesión (ES256). El `kid` es la huella JWK de su pública |
| `TOKENS_LLAVE_PUBLICA` | Se deriva de la privada | Se comprueba que sea la de la privada; si no, `/salud` responde 503 |
| `HUMO_CORREO`, `HUMO_CLAVE` | Sin cuenta de prueba | Al arrancar se crea la cuenta de prueba de *staging* con correo verificado |
| `IDENTIDAD_OTP_FIJO` | Cada código de verificación del correo es aleatorio | Todos los códigos son ese (6 dígitos), para las E2E del registro. Nunca en producción |
| `AUDITORIA_DATABASE_URL` | Log de auditoría en memoria (desarrollo y pruebas unitarias) | Log en PostgreSQL con las tres capas de protección. El esquema se crea solo al arrancar |
| `COTIZACION_DATABASE_URL` | Cotizaciones en memoria: se pierden al reiniciar | Cotizaciones en PostgreSQL, en el esquema `cotizacion`, que el módulo crea al arrancar |

Al recibir la orden de apagado, la fachada llama a `detener()` en cada módulo en orden inverso al de
arranque. Auditoría aprovecha ese momento para escribir lo que quedó en su cola; sin eso, una
decisión tomada justo antes de que la plataforma reemplace la instancia se perdería.

## Verificar lo mismo que el CI

```bash
ruff check . && ruff format --check .   # lint y formato
lint-imports                            # frontera entre módulos
pytest --cov=app --cov-report=term-missing
```

## Estructura

```
app/
├── compartido/        Tipos sin reglas de negocio: dinero, reloj, errores, contrato de módulo, bus de eventos
├── fachada/
│   ├── registro.py    Descubre los módulos de app/modulos y toma su descriptor MODULO
│   ├── salud.py       /salud: agrega el estado que reporta cada módulo
│   └── v1/            Interfaz pública versionada. Agrega los routers de los módulos
└── modulos/           Un paquete por capacidad de negocio, todos con la misma forma
    ├── cotizacion/    WEB-F01 · publica rutas
    ├── auditoria/     ARQ-03 · publica rutas; los demás módulos le escriben en proceso
    ├── consentimiento/ WEB-F02 · publica rutas; publica eventos en el bus
    ├── perfilamiento/ caché de perfiles que escucha `consentimiento-revocado`; ARQ-02 la llena
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
| Consentimiento | **Ya es el módulo real** (`app/modulos/consentimiento`, WEB-F02) | Persistencia propia del módulo |
| Perfil de riesgo | **Ya es el módulo real** (`app/modulos/perfilamiento`, ARQ-02): adaptador con presupuesto, circuit breaker y caché | Proveedor de Open Finance real detrás del mismo puerto |
| Log de auditoría | **Ya es el módulo real** (`app/modulos/auditoria`, ARQ-03) | En memoria sin `AUDITORIA_DATABASE_URL`; PostgreSQL con ella |

Las dos primeras se cambian en un solo archivo: `app/modulos/cotizacion/api/dependencias.py`.
Ni el dominio ni los casos de uso se enteran. Esa es la prueba de que RC-05 se sostiene.
Auditoría y consentimiento ya se cambiaron ahí mismo; ninguna de las dos historias tocó el
dominio ni los casos de uso de cotización.

## Módulo de consentimiento (WEB-F02)

Otorgar, consultar, revocar y renovar el consentimiento Open Finance, por finalidad
(RC-04, RC-05). Cotización y suscripción preguntan en proceso, por la interfaz pública
(`obtener_servicio().consentimiento_vigente(id_cliente, finalidad)`), y la respuesta sale
del repositorio y no de una copia: una revocación se ve en la siguiente consulta.

| Método | Ruta | Qué hace |
|---|---|---|
| `GET` | `/v1/consentimiento/finalidades` | Catálogo: alcance, vigencia, entidades origen y versión del texto legal |
| `GET` | `/v1/consentimiento/clientes/{id_cliente}/consentimientos` | Los del cliente, en cualquier estado, con `por_vencer` y `dias_para_vencer` |
| `POST` | `/v1/consentimiento/clientes/{id_cliente}/consentimientos` | Otorga (201). Exige la versión vigente del texto y al menos una entidad del catálogo |
| `GET` | `.../consentimientos/{id}` | Detalle |
| `POST` | `.../consentimientos/{id}/revocacion` | Revoca uno vigente. 409 si no lo está |
| `POST` | `.../consentimientos/{id}/renovacion` | Renueva uno vigente o vencido sin repetir el flujo. 409 si está revocado, o si está vencido y ya hay otro vigente para la finalidad |
| `GET` | `.../consentimientos/{id}/eventos` | Histórico cronológico: otorgado, renovado, revocado, vencido |

El núcleo no autentica: el BFF pone el cliente en la ruta y el canal en la cabecera
`X-Canal` (`web` o `movil`). Un consentimiento de otro cliente responde 404, igual que si
no existiera. Los códigos de error (`consentimiento_ya_vigente`, `consentimiento_no_vigente`,
`consentimiento_revocado`, `version_texto_desactualizada`, `entidades_invalidas`) son los
mismos que devuelven los mocks del BFF web, y el catálogo es el mismo de `apps/web/src/mocks/datos.ts`.

### Qué pasa al revocar

1. El repositorio queda actualizado primero: es la fuente que consulta cotización.
2. El evento se escribe en el log de auditoría con actor, finalidad, canal, marca de tiempo
   y versión del texto legal aceptado.
3. Se publica `consentimiento-revocado` en el bus del proceso (`app/compartido/eventos.py`).
   Perfilamiento está suscrito y vacía la caché de perfil de ese cliente. Sin este paso,
   la revocación se vería en pantalla pero el perfil seguiría sirviéndose desde la caché
   (corrección D-04 de EXP-04).

El vencimiento no lo provoca nadie: la primera vez que se consulta un consentimiento
después de su fecha, queda el evento `vencido` en el histórico y en auditoría, una sola
vez. Con 30 días o menos de vigencia, `por_vencer` es `true` y la renovación extiende la
fecha sin pedir el flujo completo.

### Solicitudes simultáneas

Cada transición del mismo cliente (otorgar, revocar, renovar y materializar un vencimiento)
lee, decide y guarda bajo un cerrojo por cliente. Sin él, un doble clic en «Otorgar» dejaba
dos consentimientos vigentes para la misma finalidad, y revocar uno no cortaba el acceso
porque cotización encontraba el otro. El cerrojo es por proceso: cuando el repositorio pase
a una base compartida entre procesos, hará falta además una restricción única (un índice
parcial sobre cliente y finalidad vigentes).

La composición de cada módulo (`_componentes()`, `obtener_bus()`, `obtener_cache()`) usa
`app.compartido.unica.instancia_unica` en lugar de `lru_cache(maxsize=1)`: con el servidor
recién levantado, `lru_cache` dejaba que dos solicitudes simultáneas armaran dos servicios,
y lo guardado en el que no quedaba en caché se perdía.

## Módulo de perfilamiento: adaptador de Open Finance (ARQ-02)

Perfil de riesgo del cliente a partir de sus datos de Open Finance, detrás del puerto
`ProveedorOpenFinance`. El núcleo solo conoce ese puerto; el proveedor simulado vive en
`infraestructura/` y el real se enchufa en `api/dependencias.py`. El perfil no se expone
por HTTP: cotización y suscripción lo piden en proceso, siempre después de verificar el
consentimiento, por `obtener_servicio().perfil_de(id_cliente, id_consentimiento)`.

| Método | Ruta | Qué hace |
|---|---|---|
| `GET` | `/v1/perfilamiento/metricas` | Estado del circuito, latencia p95 y tasa de error del adaptador |

### Cómo resiste al proveedor (EXP-01 en el núcleo)

El adaptador `AdaptadorOpenFinanceResiliente` aplica las tres correcciones que salieron
del experimento:

| Corrección | Qué hace aquí |
|---|---|
| D-01 · presupuesto efectivo | La llamada del camino del usuario tiene 120 ms. Si el proveedor no responde en ese tiempo, se abandona y se sirve de caché. Los 700 ms son solo para el sondeo |
| D-02 · mínimo de llamadas | El circuito no se evalúa con menos de 5 llamadas en la ventana de 30 s; con ≥ 10 % de fallos se abre |
| D-03 · sondeo en segundo plano | Con el circuito abierto, cada 10 s se lanza un sondeo en un hilo propio con el tiempo límite largo. El usuario nunca lo espera. Si responde, el circuito se cierra |

Cuando el proveedor no responde, la respuesta dice de dónde salió el perfil: `proveedor`,
`cache` (cotización lo marca como `open_finance_en_cache` en la explicación del precio) o
`ninguno` (cotización tarifa a precio estándar en vez de dejar al cliente sin oferta,
RC-03). La caché es la misma que WEB-F02 vacía al revocar el consentimiento: un perfil
nunca sobrevive a su consentimiento.

El núcleo nunca ve una excepción del cliente HTTP del proveedor: el adaptador la traduce a
`ProveedorOpenFinanceNoDisponibleError`, que es `DependenciaNoDisponible` del dominio.

### Configuración

| Variable | Efecto |
|---|---|
| `PERFILAMIENTO_OPEN_FINANCE_MODO` | `sano` (por omisión), `degradado` (tarda más que el presupuesto) o `caido`. Son los tres escenarios de WireMock de EXP-01 |
| `PERFILAMIENTO_PRESUPUESTO_MS` | Presupuesto del camino del usuario. 120 por omisión |

`/salud` reporta `open_finance` como `simulado` con el estado del circuito, el p95 y la
tasa de error, y como `degradado` mientras el circuito esté abierto y se sirva de caché.

## Módulo de identidad: hook de KYC/AML (MOB-F01)

Verificación de identidad del cliente contra el proveedor de KYC/AML, detrás del puerto
`ProveedorKYC` (ARQ-02). El núcleo solo conoce ese puerto; el proveedor simulado vive en
`infraestructura/` y el real se enchufa en `api/dependencias.py` sin tocar nada más.

| Método | Ruta | Qué hace |
|---|---|---|
| `POST` | `/v1/identidad/clientes/{id_cliente}/verificaciones` | Envía los datos al proveedor (202). 409 si ya está verificada o hay una en curso; 503 si el proveedor no responde |
| `GET` | `/v1/identidad/clientes/{id_cliente}/verificaciones/ultima` | Última verificación. Si estaba `en_proceso`, pregunta al proveedor antes de responder |
| `GET` | `/v1/identidad/clientes/{id_cliente}/estado` | `pendiente_verificacion`, `en_revision`, `verificada` o `rechazada`: lo que el BFF pone en `/sesion` |

El BFF móvil expone esto como `/identidad/verificacion`; la forma de la respuesta es la
misma del contrato (`id`, `estado`, `motivo`, `reintento_permitido`, `actualizada_en`).

**Proveedor simulado.** Determinista y con la misma regla que el mock del BFF móvil, para
que la app se comporte igual contra el núcleo que contra sus mocks: el último dígito del
documento decide. `7` rechazada (`datos_no_coinciden`, con reintento), `8` en revisión
manual, `9` en proceso y aprobada en la siguiente consulta, cualquier otro aprobada.
`IDENTIDAD_KYC_CAIDO=1` lo deja fuera de servicio: toda solicitud responde 503 con
`proveedor_kyc_no_disponible` y `/salud` reporta `proveedor_kyc: caido`. El núcleo nunca ve
una excepción del cliente HTTP del proveedor, solo `DependenciaNoDisponible` del dominio.

**Auditoría.** Todo intento queda en el log de ARQ-03 con resultado, proveedor, canal y
marca de tiempo, incluido el intento que falló porque el proveedor estaba caído. El
número de documento no se escribe en el log.

**Fuera del Sprint 1:** la cola para reintentar sin que el usuario espere cuando el
proveedor está caído (MOB-F01, criterio 8), la captura de documento y selfie, y el
registro de la cuenta en el núcleo.

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
