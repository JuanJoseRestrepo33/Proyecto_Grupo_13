# ADR 0002 · Verificar el consentimiento vigente antes de usar un perfil de la caché

- **Estado:** aceptada. Reemplaza la decisión **D-04** de EXP-04 ("invalidación explícita por
  evento, no expiración pasiva por TTL").
- **Fecha:** 2026-11-08
- **Historia:** ARQ-05 · Verificación de calidad en staging (#124), Sprint 3. Código en #275.
- **Decisores:** equipo del Grupo 13 (Sergio, Harold, Edwin, Juan)

## Contexto

La cotización usa el perfil de riesgo de Open Finance del cliente, y solo si tiene consentimiento
vigente para la finalidad `cotizacion`. Para cumplir el presupuesto de latencia con el proveedor
degradado o caído (RC-01, RC-03), el adaptador de ARQ-02 guarda el último perfil en una **caché
local de cada réplica** y lo sirve cuando el circuito está abierto o el proveedor se pasa de los
120 ms.

RC-04 exige que, después de revocar, el perfil no se vuelva a usar: la propagación debe tardar
≤ 5 minutos. En el PRY1, EXP-04 encontró que la expiración pasiva (TTL de 300 s) dejaba el dato
accesible y que, tras revocar, la siguiente cotización lo volvía a guardar. La corrección fue
**D-04**: al revocar, se publica un evento y cada consumidor invalida su caché.

D-04 se validó con una sola réplica y una caché compartida (Redis en el experimento). En el PRY2:

- **No hay Redis ni Pub/Sub** en *staging* (ARQ-01 los dejó fuera de alcance), y la caché de
  perfiles vive en la memoria de cada réplica del núcleo.
- ARQ-05 pide **dos réplicas** detrás del balanceador de Cloud Run (criterio 3). El evento de
  revocación lo procesa la réplica que atendió la solicitud; la otra no se entera y seguiría
  sirviendo el perfil de un cliente que ya revocó.
- El consentimiento pasó a vivir en Cloud SQL (esquema `consentimiento`, #275), así que todas las
  réplicas leen la misma fuente de verdad.

## Decisión

**Antes de servir un perfil desde la caché, el núcleo verifica en la fuente de verdad que el
consentimiento siga vigente.** Si no está vigente, descarta el perfil de esa réplica y cotiza a
tarifa estándar.

En el adaptador resiliente de perfilamiento
([`adaptador_resiliente.py`](../../backend/app/modulos/perfilamiento/infraestructura/adaptador_resiliente.py)):

1. Si el proveedor responde dentro del presupuesto, se usa su perfil y se guarda en la caché, como
   antes.
2. Si hay que servir de la caché, se consulta `ServicioDeConsentimiento.esta_vigente` por la
   interfaz pública del módulo de consentimiento (ARQ-01), sin leer sus tablas.
3. **Ante la duda, tarifa estándar:** si la verificación falla (base caída, error), se trata como no
   vigente. Nunca se usa un perfil sin saber que el consentimiento sigue vigente.
4. El evento de revocación sigue invalidando la caché de la réplica que lo recibe. Ya no es lo que
   garantiza RC-04, solo ahorra la consulta en esa réplica.

El costo de la consulta adicional se mide en lugar de suponerse: el adaptador lo registra aparte
(`metricas_verificacion`), y k6 lo mide de extremo a extremo con el proveedor degradado y caído
(ARQ-05, criterio 7), que es cuando se sirve de la caché.

## Alternativas descartadas

| Alternativa | Por qué no |
|---|---|
| **Mantener D-04** (invalidar por evento) con varias réplicas | El evento llega a una sola réplica. Para que llegue a todas hace falta un bus con difusión (Pub/Sub con una suscripción por réplica), y Cloud Run no da una identidad estable por instancia para crear esas suscripciones |
| **Caché compartida en Memorystore (Redis)** | Una sola caché que se invalida una vez, como en EXP-04. Suma un servicio con costo fijo, red privada (VPC connector) y otra dependencia en el camino de la cotización. Queda como plan B **solo si k6 muestra que la verificación no cabe en p95 ≤ 250 ms** |
| **TTL corto en la caché local** (por ejemplo 60 s) | Es la expiración pasiva que EXP-04 ya descartó: deja el dato en uso durante el TTL. Además, con el proveedor caído es justo cuando más se necesita la caché, y un TTL corto la vaciaría |
| **Una sola réplica** | Cumple RC-04 sin cambios, pero renuncia a la redundancia de ARQ-05 (criterio 3) y de RC-03 |

## Consecuencias

- **RC-04 se cumple entre réplicas** sin Redis ni Pub/Sub: la revocación aplica en la siguiente
  cotización de cualquier réplica, no en ≤ 5 minutos.
- **Cuesta una consulta a Cloud SQL por cotización servida de la caché.** Es una lectura por clave
  sobre el esquema `consentimiento`, en la misma instancia que ya usa la cotización. Con el
  proveedor sano no se paga, porque no se sirve de la caché.
- **El camino degradado depende de Cloud SQL.** Si la base no responde, la cotización sale a tarifa
  estándar en lugar de usar el perfil guardado. Es el comportamiento seguro: RC-04 pesa más que
  personalizar la prima.
- **Requisito para tener dos réplicas:** todo el estado que lea una cotización debe estar en Cloud
  SQL. El consentimiento ya lo está; las cuentas de identidad todavía no
  (`RepositorioDeCuentasEnMemoria`), y por eso las réplicas de ARQ-05 (#136) esperan a que
  identidad persista.
- **Se revisa con datos:** si el informe de calidad de ARQ-05 muestra que la verificación rompe el
  presupuesto de p95, esta ADR se reemplaza por una con Memorystore.
