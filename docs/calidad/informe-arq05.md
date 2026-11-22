# Informe de calidad · ARQ-05 · Verificación de calidad en staging

> **Estado:** cerrado el 22 nov 2026.
>
> - Con Cloud SQL en `db-g1-small` y el núcleo con dos réplicas, la carga a 1.000 cotizaciones por
>   minuto durante 5 minutos **cumple RC-01 y RC-03** con el proveedor de Open Finance sano,
>   degradado y caído (sección 2).
> - En *staging* también se verificaron la revocación entre réplicas (RC-04), la base de la
>   decisión en el log y el costo de verificar el consentimiento (sección 4).
> - El caos y la auditoría alterada (RC-08) se corren con #318 y quedan en la sección 4.
> - Las secciones 3 y 3.2 cuentan los dos hallazgos que hubo que resolver antes de cumplir.
>
> **Historia:** ARQ-05 (#124) · **Partes:** #136 (Cloud), #216 (pruebas de carga y caos), #217
> (documentación) · **Decisiones relacionadas:** [ADR 0002](../adr/0002-consentimiento-vigente-antes-de-la-cache.md)
> (reemplaza D-04 de EXP-04), [ADR 0003](../adr/0003-topologia-de-despliegue-cloud-run.md)

## 1. Cómo se mide

| | |
|---|---|
| **Herramienta** | k6 `v0.54.0`, desde el workflow manual [*Carga de cotización (k6)*](../../.github/workflows/carga.yml) |
| **Guion** | [`scripts/carga/cotizacion.js`](../../scripts/carga/cotizacion.js): el perfil de EXP-01 (tasa constante, 1.000 cotizaciones por minuto) sobre el recorrido real |
| **Recorrido** | `POST /api/v1/web/cotizaciones` → BFF → núcleo → consentimiento → perfilamiento (Open Finance simulado, circuit breaker, caché) → Cloud SQL → auditoría. Producto `auto`, ciudad del asegurado alternando Bogotá y Cali |
| **Cotizaciones distintas** | Desde #312 cada solicitud lleva un valor asegurado distinto. Así ninguna reutiliza una cotización vigente (WEB-F01, criterio 4) y todas pasan por el perfil, la base y el log. Cada corrida suma unos 5.000 registros a la auditoría |
| **Cuenta** | La cuenta de prueba de *staging*, con consentimiento vigente para `cotizacion` (el guion lo otorga si falta, #282) |
| **Ambiente** | *Staging* en `us-east1`. Núcleo en Cloud Run con **2 réplicas fijas** (mínimo y máximo 2, 1 vCPU, 512 MB, concurrencia 80; #301). Cloud SQL **`db-g1-small`** zonal (0,5 vCPU, 1,7 GB; #314). Pool de conexiones por módulo (#300). [ADR 0003](../adr/0003-topologia-de-despliegue-cloud-run.md) |
| **Escenarios** | `PERFILAMIENTO_OPEN_FINANCE_MODO` del núcleo en `sano`, `degradado` (250 ms por consulta) o `caido`. El workflow lo cambia antes de la carga y lo devuelve a `sano` al final |
| **Aislamiento** | La carga no corre a la vez que las E2E, que cambian el consentimiento de la misma cuenta (grupo `e2e-cuenta-staging`, #310) |
| **Origen de la carga** | Un runner de GitHub Actions. La latencia incluye la red del runner a `us-east1`. "Bogotá y Cali" son la ciudad del asegurado, no el origen de la carga (sección 6) |
| **Umbrales** | RC-01: p95 ≤ 250 ms y p99 ≤ 500 ms de extremo a extremo. RC-03: menos de 0,1 % de solicitudes sin oferta o con 5xx |

## 2. Corridas

### 2.1 Corridas que cuentan para los criterios

A 1.000/min durante 5 min, con `db-g1-small`, dos réplicas, pool y cotizaciones distintas:

| Escenario | Fecha (UTC) | Solicitudes | p50 | p95 | p99 | Máx | Sin oferta | 5xx | Tarifa estándar | Auditoría (registros al terminar) | Corrida |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| **Sano** | 20 nov 22:35 | 5.000 | 93 ms | **110 ms** | **123 ms** | 356 ms | **0 %** | **0 %** | 0 % | 11.531 · conforme | [44830122689](https://github.com/JuanJoseRestrepo33/Proyecto_Grupo_13/actions/runs/44830122689) |
| **Degradado** | 21 nov 00:46 | 5.001 | 130 ms | **249 ms** | **259 ms** | 1.125 ms | **0 %** | **0 %** | 100 % | 16.533 · conforme | [44845951108](https://github.com/JuanJoseRestrepo33/Proyecto_Grupo_13/actions/runs/44845951108) |
| **Caído** | 21 nov 01:00 | 5.001 | 93 ms | **109 ms** | **122 ms** | 315 ms | **0 %** | **0 %** | 100 % | 21.535 · conforme | [44847398759](https://github.com/JuanJoseRestrepo33/Proyecto_Grupo_13/actions/runs/44847398759) |

- **Los tres cumplen RC-01 y RC-03**, sin ningún 5xx ni ninguna solicitud sin oferta.
- **Degradado:** el proveedor tarda 250 ms por consulta y el presupuesto de 120 ms lo corta. El
  circuito se abre y el resto sale a tarifa estándar. Por eso el p95 queda en 249 ms, **justo debajo
  del límite**: es el escenario con menos margen.
- **Caído:** con el circuito abierto, la respuesta no espera al proveedor. Por eso la latencia es
  igual a la de sano.
- **Caché de perfiles en 0 %.** La cuenta de prueba no tenía un perfil en la caché de la réplica al
  caer el proveedor, así que las ofertas degradadas salen a tarifa estándar y no desde la caché. La
  ruta de la caché con consentimiento vigente se prueba en
  `test_cotizacion_tarifa_con_el_perfil_real_y_degrada_a_cache_y_a_estandar`.
- **La auditoría crece con la carga.** Una entrada por cotización, unos 5.000 registros por
  corrida, y la cadena sigue conforme después de cada una (`GET /v1/auditoria/integridad`).

### 2.2 Corridas anteriores (no cuentan)

| # | Fecha (UTC) | Ambiente | Tasa · duración | Solicitudes | p95 | p99 | Sin oferta | 5xx | Corrida | Por qué no cuenta |
|---|---|---|---|---:|---:|---:|---:|---:|---|---|
| 1 | 08 nov 21:29 | 1 réplica, f1-micro, sin pool | 1.000/min · 1 min | 1.001 | 241 ms | 2.796 ms | 0,10 % | 0,10 % | [42523825482](https://github.com/JuanJoseRestrepo33/Proyecto_Grupo_13/actions/runs/42523825482) | Sin consentimiento: 100 % a tarifa estándar (#282) |
| 2 | 08 nov 21:57 | 1 réplica, f1-micro, sin pool | 1.000/min · 1 min | 1.000 | 205 ms | 1.141 ms | 0 % | 0 % | [42527415985](https://github.com/JuanJoseRestrepo33/Proyecto_Grupo_13/actions/runs/42527415985) | Solo 1 min. Bloqueo de la sección 3 al final |
| 3 | 08 nov 22:37 | 1 réplica, f1-micro, sin pool | 1.000/min · 5 min | 4.918 | 9.161 ms | 10.051 ms | 80,58 % | 27,82 % | [42531034832](https://github.com/JuanJoseRestrepo33/Proyecto_Grupo_13/actions/runs/42531034832) | Bloqueo de la sección 3 |
| 4 | 08 nov 23:07 | 1 réplica, f1-micro, sin pool | 300/min · 5 min | 1.501 | 5.076 ms | 5.083 ms | 28,45 % | 25,85 % | [42532882742](https://github.com/JuanJoseRestrepo33/Proyecto_Grupo_13/actions/runs/42532882742) | Bloqueo de la sección 3 |
| 5 | 20 nov 16:18 | 2 réplicas, f1-micro, pool | 1.000/min · 5 min | 5.000 | 135 ms | 154 ms | 0 % | 0 % | [44772008160](https://github.com/JuanJoseRestrepo33/Proyecto_Grupo_13/actions/runs/44772008160) | Reutilizaba cotizaciones vigentes (solo unos 1.400 registros de auditoría) y una E2E revocó el consentimiento a mitad de la carga (51,9 % a tarifa estándar). Arreglado en #310 y #312 |
| 6 | 20 nov 19:12 | 2 réplicas, f1-micro, pool | 1.000/min · 5 min | 4.987 | 1.120 ms | 3.692 ms | 0,20 % | 0,20 % | [44799900725](https://github.com/JuanJoseRestrepo33/Proyecto_Grupo_13/actions/runs/44799900725) | Primera con cotizaciones distintas: la `db-f1-micro` se queda sin CPU (sección 3.2) |

"Sin oferta" cuenta toda respuesta distinta de 201. Incluye los 5xx y también los 429 con los que
Cloud Run aborta las solicitudes que esperan demasiado por una instancia libre.

## 3. Hallazgo 1: el núcleo se traba cerca de la cotización 1.000

Corridas 1 a 4. Según los registros de Cloud Run, el núcleo **se trababa después de unas 1.000
cotizaciones, sin importar la tasa**:

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

- **El cambio es brusco.** A las 23:11:11 una cotización tardaba 63 ms; a las 23:11:14, 6,9 s.
- **No quedó ningún error en el registro del núcleo ni en el de Cloud SQL.**
- **Lo que ve el cliente:** el BFF corta a los 5 s (`tiempo_espera_nucleo`) y responde 503. Con la
  cola llena, Cloud Run aborta las demás solicitudes con 429, porque entonces el núcleo no podía
  escalar más allá de una instancia.

### 3.1 Causa: una conexión nueva a PostgreSQL por cada operación

La prueba que separa las hipótesis se hizo el 20 nov 2026: 1.300 cotizaciones seguidas del mismo
cliente contra el núcleo en local, con el mismo recorrido (consentimiento vigente, Open Finance
sano).

| Configuración local | p50 por cotización | ¿Se traba cerca de la 1.000? |
|---|---:|---|
| Sin base (repositorios en memoria) | 2,4 ms | No |
| PostgreSQL 16 limitado a 0,25 CPU, sin pool | 45 ms | No |
| PostgreSQL 16 limitado a 0,25 CPU, con pool (#300) | 9 ms | No |

- **No es algo por cliente.** En memoria, la cotización 1.300 tarda lo mismo que la primera.
- **Es el costo de conectarse.** Los ocho repositorios abrían y cerraban una conexión en cada
  operación, y una cotización hace seis o más (consentimiento, cotización, auditoría). En *staging*
  cada conexión nueva pasa por el socket de Cloud SQL y se autentica contra una base de núcleo
  compartido.

**Arreglo: pool de conexiones por módulo (#300).** Las conexiones se reutilizan (máximo 3 por módulo
y réplica, `POSTGRES_POOL_MAXIMO`), las ociosas se cierran a los 60 s y cada préstamo se verifica.
Con el pool, la corrida 5 ya no se trabó.

### 3.2 Hallazgo 2: la `db-f1-micro` no alcanza con cotizaciones nuevas

La corrida 5 cumplía, pero casi todas sus solicitudes reutilizaban una cotización vigente: con los
mismos datos, el núcleo devolvía la misma cotización sin pasar por el perfil ni escribir en el log.
Con #312 cada solicitud es una cotización nueva, y la corrida 6 falló.

Latencia del núcleo en la corrida 6, por minuto:

| Minuto (UTC) | Cotizaciones | p50 | p95 | CPU de Cloud SQL |
|---|---:|---:|---:|---:|
| 19:56 | 1.000 | 36 ms | 46 ms | 9 % |
| 19:57 | 1.000 | 35 ms | 46 ms | 16 % |
| 19:58 | 1.000 | 36 ms | 46 ms | 21 % |
| 19:59 | 998 | 145 ms | 2.804 ms | 20 % |
| 20:00 | 565 | 263 ms | 3.214 ms | 20 % |

- **La `db-f1-micro` tiene un tope de 0,2 vCPU.** Con unas cuatro escrituras por cotización, su CPU
  llegó al 20 % en el minuto 3. Desde ahí las consultas se encolaron y el BFF respondió 503 al
  cortar a los 5 s.
- **No era el núcleo:** su CPU no pasó del 16 %. Tampoco el tamaño de las tablas: sin carga, la
  misma cotización tardaba 30 ms en el servidor con 6.446 registros en el log.

**Arreglo: Cloud SQL en `db-g1-small` (#314)**, con 0,5 vCPU y 1,7 GB. `aprovisionar` la crea en ese
tamaño y cambia el de una instancia existente. Con ella, las corridas de la sección 2.1 cumplen.

## 4. Estado de los criterios de ARQ-05

| # | Criterio | Estado | Evidencia |
|---|---|---|---|
| 1 | Carga a 1.000/min durante 5 min: p95 ≤ 250 ms y p99 ≤ 500 ms con el proveedor sano, degradado y caído (RC-01) | ✅ Cumple | Sección 2.1. Sano 110/123 ms, degradado 249/259 ms y caído 109/122 ms (p95/p99) |
| 2 | Proveedor caído: menos de 0,1 % de fallas, y cada oferta dice si usó la caché o la tarifa estándar (RC-03) | ✅ Cumple | Corrida en caído: 0 % sin oferta y 0 % de 5xx en 5.001 solicitudes. Cada oferta trae `base_perfilamiento` y `motivo_tarifa` |
| 3 | Revocación en una réplica respetada por las dos (RC-04) | ✅ Cumple en *staging* | 20 nov, cliente `CLI-RC04-1795194106`: antes de revocar, 30 de 30 cotizaciones con consentimiento, atendidas por las dos réplicas. Revocación a las 17:01:55 en una réplica. Después, 30 de 30 `sin_consentimiento`, repartidas entre las dos (14 y 16) |
| 4 | El circuito se cierra solo y `/salud` vuelve de `degradado` a `ok` | ✅ Cumple (prueba automática) | `test_el_circuito_se_cierra_solo_cuando_el_proveedor_vuelve_y_salud_pasa_a_ok` (#318), sobre la app completa y sin reiniciar el núcleo. En *staging* no se puede probar así: cambiar el modo del proveedor crea una revisión nueva, es decir, reinicia el núcleo |
| 5 | Una cotización calculada sin el perfil en vivo queda registrada como tal, con su base en la auditoría | ✅ Cumple en *staging* | 22 nov, `QT-2026-29139`: en la secuencia 21.859 del log, `base_perfilamiento: estandar` y `motivo_tarifa: sin_consentimiento` en el resultado y en las variables. `GET …/recalculo` vuelve a dar la misma prima (`coincide: true`). Las corridas en degradado y caído dejaron 10.002 decisiones a tarifa estándar en el log, conforme |
| 6 | Un registro alterado se detecta al verificar la cadena de hash (RC-08) | ⏳ Se corre con #318 | Workflow *Auditoría alterada (RC-08)*. Ya verificado: la cadena de 21.535 registros es conforme después de las tres corridas, y en local `test_auditoria.py` detecta el registro alterado |
| 7 | El costo de verificar el consentimiento cabe en p95 ≤ 250 ms | ✅ Cumple | 22 nov, 240 cotizaciones intercaladas contra el núcleo de *staging*, latencia del servidor en Cloud Run. Con consentimiento y perfil: p50 31 ms, p95 39 ms. Sin Open Finance (ni consentimiento ni perfil): p50 19 ms, p95 24 ms. **Costo: +12 ms en p50 y +15 ms en p95**, que ya está dentro de los 110 ms de p95 de la corrida en sano |
| Caos | Quitar una réplica a mitad de la carga (#216) | ⏳ Se corre con #318 | `carga.yml` con `caos=true`: de 2 réplicas a 1 entre los minutos 2 y 3,5 |

## 5. Qué falta y de quién depende

| Paso | Depende de | Issue |
|---|---|---|
| Fusionar #318 y correr *Carga* con `caos=true` y *Auditoría alterada (RC-08)* | Revisión (Sergio); corridas y aprobación (Juan) | #216 |
| Poner aquí el resultado de las dos corridas (criterio 6 y caos) | Documentación (Juan) | #217 |

## 6. Límites de esta medición

- **Origen de la carga:** la latencia incluye la red del runner de GitHub a `us-east1`. Para medir
  desde Colombia (Bogotá y Cali), se corre el mismo guion desde una máquina allá:
  `k6 run -e BASE_URL=… -e HUMO_CORREO=… -e HUMO_CLAVE=… scripts/carga/cotizacion.js`. Desde
  Bogotá, la red suma unos 180 ms de ida y vuelta, que el servidor no controla.
- **Un solo cliente:** toda la carga es de la cuenta de prueba. Ya se descartó que el bloqueo de la
  sección 3 fuera algo por cliente.
- **Instancias de *staging*:** el núcleo (2 × 1 vCPU) y Cloud SQL (`db-g1-small`, zonal, sin
  respaldos) sirven para medir el diseño, no la capacidad de producción. El degradado queda a 1 ms
  del límite de p95: con más carga o con una base más chica no cumpliría.
- **Costo:** la `db-g1-small` cuesta unos USD 25 al mes encendida, frente a unos 9 de la f1-micro.
  El apagado nocturno (#269) necesita el environment `staging-apagado` configurado.
