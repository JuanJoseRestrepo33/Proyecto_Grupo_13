# Informe de calidad · ARQ-05 · Verificación de calidad en staging

> **Estado: en curso.** Este informe recoge las corridas hechas hasta el 8 nov 2026 y la causa del
> bloqueo, encontrada el 20 nov 2026 (sección 3.1). Solo hay
> corridas con el proveedor de Open Finance **sano**. Las de `degradado` y `caido`, el caos y la
> verificación de la cadena de auditoría quedan pendientes, por el hallazgo de la
> [sección 3](#3-hallazgo-el-núcleo-se-traba-cerca-de-la-cotización-1000).
>
> **Historia:** ARQ-05 (#124) · **Partes:** #136 (Cloud), #216 (pruebas de carga y caos), #217
> (documentación) · **Decisiones relacionadas:** [ADR 0002](../adr/0002-consentimiento-vigente-antes-de-la-cache.md),
> [ADR 0003](../adr/0003-topologia-de-despliegue-cloud-run.md)

## 1. Cómo se mide

| | |
|---|---|
| **Herramienta** | k6 `v0.54.0`, desde el workflow manual [*Carga de cotización (k6)*](../../.github/workflows/carga.yml) |
| **Guion** | [`scripts/carga/cotizacion.js`](../../scripts/carga/cotizacion.js): el perfil de EXP-01 (tasa constante, 1.000 cotizaciones por minuto) sobre el recorrido real |
| **Recorrido** | `POST /api/v1/web/cotizaciones` → BFF → núcleo → perfilamiento (Open Finance simulado, circuit breaker, caché) → Cloud SQL. Producto `auto`, asegurado de 35 años, ciudad alternando Bogotá y Cali |
| **Cuenta** | La cuenta de prueba de *staging*, con consentimiento vigente para `cotizacion` (el guion lo otorga si falta, #282) |
| **Ambiente** | *Staging* en `us-east1`: núcleo en Cloud Run con **1 instancia** (`max-instances=1`, 1 vCPU, 512 MB, concurrencia 80); Cloud SQL `db-f1-micro` zonal ([ADR 0003](../adr/0003-topologia-de-despliegue-cloud-run.md)) |
| **Origen de la carga** | Un runner de GitHub Actions. La latencia incluye la red del runner a `us-east1`; "Bogotá y Cali" son la ciudad del asegurado, no el origen de la carga |
| **Umbrales** | RC-01: p95 ≤ 250 ms y p99 ≤ 500 ms de extremo a extremo. RC-03: menos de 0,1 % de solicitudes sin oferta o con 5xx |

## 2. Corridas

Todas con el proveedor de Open Finance **sano**.

| # | Fecha (UTC) | Tasa · duración | Solicitudes | p50 | p95 | p99 | Máx | Sin oferta | 5xx | Tarifa estándar | Corrida |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 1 | 08 nov 21:29 | 1.000/min · 1 min | 1.001 | 177 ms | 241 ms | 2.796 ms | 4.966 ms | 0,10 % | 0,10 % | 100 % | [42523825482](https://github.com/JuanJoseRestrepo33/Proyecto_Grupo_13/actions/runs/42523825482) |
| 2 | 08 nov 21:57 | 1.000/min · 1 min | 1.000 | 113 ms | **205 ms** | 1.141 ms | 1.784 ms | **0 %** | 0 % | 0 % | [42527415985](https://github.com/JuanJoseRestrepo33/Proyecto_Grupo_13/actions/runs/42527415985) |
| 3 | 08 nov 22:37 | 1.000/min · 5 min | 4.918 | 147 ms | 9.161 ms | 10.051 ms | 12.681 ms | 80,58 % | 27,82 % | 0 % | [42531034832](https://github.com/JuanJoseRestrepo33/Proyecto_Grupo_13/actions/runs/42531034832) |
| 4 | 08 nov 23:07 | 300/min · 5 min | 1.501 | 141 ms | 5.076 ms | 5.083 ms | 5.109 ms | 28,45 % | 25,85 % | 0 % | [42532882742](https://github.com/JuanJoseRestrepo33/Proyecto_Grupo_13/actions/runs/42532882742) |

**La corrida 1 no cuenta para los criterios.** La cuenta de prueba no tenía consentimiento, así que
el 100 % de las ofertas salió a tarifa estándar sin pasar por el perfil de Open Finance. Por eso el
guion ahora otorga el consentimiento antes de empezar (#282).

**Sobre los porcentajes de las corridas 3 y 4.** "Sin oferta" cuenta toda respuesta distinta de
201. Incluye los 5xx y también los 429 con los que Cloud Run aborta las solicitudes que esperan
demasiado por una instancia libre.

## 3. Hallazgo: el núcleo se traba cerca de la cotización 1.000

Según los registros de Cloud Run, el núcleo **se traba después de unas 1.000 cotizaciones, sin
importar la tasa**:

| Corrida | Cuándo se trabó | Cotizaciones atendidas hasta ese momento |
|---|---|---|
| 1 (1.000/min, 1 min) | En el último segundo: unas 16 cotizaciones tardaron entre 1,8 y 4,9 s dentro del núcleo | ~1.000 |
| 2 (1.000/min, 1 min) | Cola al final, por el p99 de 1.141 ms (sin revisar sus registros) | ~1.000 |
| 3 (1.000/min, 5 min) | Hacia el minuto 1 | ~1.000 |
| 4 (300/min, 5 min) | A las 23:11:12, en el minuto 3,5 | ~1.050 |

**El detalle de la corrida 4**, latencia del núcleo en `/v1/cotizacion/cotizaciones`:

| Minuto (UTC) | Respuestas 201 | p50 | p95 | Máx |
|---|---:|---:|---:|---:|
| 23:08 | 300 | 65 ms | 75 ms | 119 ms |
| 23:09 | 300 | 67 ms | 75 ms | 139 ms |
| 23:10 | 300 | 64 ms | 72 ms | 125 ms |
| 23:11 | 297 | 12,2 s | 22,9 s | 31,8 s |
| 23:12 | 177 | 22,5 s | 29,7 s | 31,8 s |

- **Hasta el minuto 3,5 el núcleo cumple con holgura:** p95 de 75 ms, muy por debajo de los
  250 ms de RC-01.
- **El cambio es brusco.** A las 23:11:11 una cotización tardaba 63 ms; a las 23:11:14, 6,9 s. No
  se degrada de a poco.
- **No quedó ningún error en el registro del núcleo ni en el de Cloud SQL** en ese momento. Cloud
  SQL solo muestra un *vacuum* rutinario.
- **Lo que ve el cliente:** el BFF corta a los 5 s (`tiempo_espera_nucleo`) y responde 503. Por eso
  el p95 y el p99 de la corrida 4 se quedan en ~5,1 s. Con la cola llena, Cloud Run aborta las
  demás solicitudes con 429 ("*The request was aborted after waiting too long for an available
  instance*"), porque el núcleo no puede escalar más allá de una instancia.
- **Todas las cotizaciones son del mismo cliente** (la cuenta de prueba).

### 3.1 Causa: una conexión nueva a PostgreSQL por cada operación

La prueba que separa las dos hipótesis se hizo el 20 nov 2026: 1.300 cotizaciones seguidas del mismo
cliente contra el núcleo en local, con el mismo recorrido (consentimiento vigente, Open Finance
sano).

| Configuración local | p50 por cotización | ¿Se traba cerca de la 1.000? |
|---|---:|---|
| Sin base (repositorios en memoria) | 2,4 ms | No |
| PostgreSQL 16 limitado a 0,25 CPU, sin pool | 45 ms | No |
| PostgreSQL 16 limitado a 0,25 CPU, con pool (#300) | 9 ms | No |

- **No es algo por cliente.** En memoria, la cotización 1.300 tarda lo mismo que la primera. Se
  descarta la primera hipótesis.
- **Es el costo de conectarse.** Los ocho repositorios abrían y cerraban una conexión en cada
  operación, y una cotización hace seis o más (consentimiento, cotización, auditoría). Con la base
  limitada, eso multiplica por 19 el tiempo de la cotización.
- **Por qué en staging se traba y en local no.** En staging cada conexión nueva pasa por el socket de
  Cloud SQL y se autentica contra una `db-f1-micro`, que es de **núcleo compartido**: rinde por
  ráfagas y, gastado el margen, baja a una fracción de CPU. Eso explica que el corte dependa del
  número de cotizaciones y no de la tasa, que sea brusco y que no deje errores en ningún registro.
  Es la explicación más consistente con los datos; la confirma o la descarta repetir k6 con el pool.

**Arreglo: pool de conexiones por módulo (#300).** Las conexiones se reutilizan (máximo 3 por módulo
y réplica, `POSTGRES_POOL_MAXIMO`), las ociosas se cierran a los 60 s y cada préstamo se verifica,
para sobrevivir al apagado nocturno de Cloud SQL.

## 4. Estado de los criterios de ARQ-05

| # | Criterio | Estado | Evidencia |
|---|---|---|---|
| 1 | Carga a 1.000/min durante 5 min: p95 ≤ 250 ms y p99 ≤ 500 ms con el proveedor sano, degradado y caído (RC-01) | ❌ **No cumple con el proveedor sano** sin el pool. Repetir con #300; degradado y caído pendientes | Corrida 3. Antes del bloqueo, la corrida 4 da p95 de 75 ms en el núcleo. Causa en la sección 3.1 |
| 2 | Proveedor caído: menos de 0,1 % de fallas y cada oferta dice si usó caché o tarifa estándar (RC-03) | ⏳ Pendiente | Necesita resolver el hallazgo de la sección 3 |
| 3 | Revocación en una réplica respetada por las dos (RC-04) | ⏳ Pendiente en staging | Identidad en Cloud SQL y 2 réplicas en #301. En local ya pasa: `test_consentimiento_postgres.py` y `test_identidad_postgres.py` |
| 4 | El circuito se cierra solo y `/salud` vuelve de `degradado` a `ok` | ⏳ Pendiente | Corrida en `caido` seguida de `sano` |
| 5 | La cotización degradada queda registrada, con su base en la auditoría | ⏳ Pendiente | Corrida en `degradado` y consulta del log |
| 6 | Un registro alterado se detecta al verificar la cadena de hash (RC-08) | ⏳ Pendiente | Prueba manual sobre *staging* |
| 7 | El costo de verificar el consentimiento cabe en p95 ≤ 250 ms | ⏳ Pendiente | La verificación solo corre al servir desde la caché ([ADR 0002](../adr/0002-consentimiento-vigente-antes-de-la-cache.md)). Con el proveedor sano no se usa la caché (0 % en las corridas 2 a 4), así que se mide en `degradado` |

## 5. Qué falta y de quién depende

| Paso | Depende de | Issue |
|---|---|---|
| ~~Encontrar por qué el núcleo se traba cerca de la cotización 1.000~~ Hecho: sección 3.1. Arreglo en revisión | Revisión del backend (Sergio) | #300 |
| ~~Persistir identidad en Cloud SQL~~ y subir el núcleo a 2 réplicas. En revisión | Revisión del backend (Sergio); *Aprovisionar* después del merge (Juan) | #301 |
| Repetir `sano`, `degradado` y `caido` a 1.000/min durante 5 min | Cloud (Juan) | #216 |
| Revocación entre réplicas, caos (quitar una réplica a mitad de carga) y verificación de la cadena de auditoría | Cloud (Juan) | #216 |
| Cerrar este informe con todas las corridas | Documentación (Juan) | #217 |

## 6. Límites de esta medición

- **Origen de la carga:** la latencia incluye la red del runner de GitHub a `us-east1`. Para medir
  desde Colombia se corre el mismo guion desde una máquina allá:
  `k6 run -e BASE_URL=… -e HUMO_CORREO=… -e HUMO_CLAVE=… scripts/carga/cotizacion.js`.
- **Un solo cliente:** toda la carga es de la cuenta de prueba. Un perfil más realista repartiría la
  carga entre muchos clientes, y quizá no mostraría el bloqueo de la sección 3 si es algo por
  cliente. Eso es justamente lo que hay que averiguar.
- **Instancia mínima:** el núcleo y Cloud SQL están dimensionados para *staging*
  (`db-f1-micro`, 1 vCPU). Sirven para medir el diseño, no la capacidad de producción.
