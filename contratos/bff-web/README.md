# Contrato del BFF web

[`openapi.yaml`](openapi.yaml) es la fuente de verdad entre el cliente web y su Backend for Frontend.
Lo consumen:

- **El cliente web** ([`apps/web`](../../apps/web)): genera sus tipos con `npm run contratos:tipos` y
  usa mocks que implementan este mismo contrato mientras el BFF no exista.
- **El BFF web**, cuando se construya: debe cumplirlo tal cual.

## Alcance del Sprint 1

| Recurso | Operaciones | Historia |
|---|---|---|
| `/sesion` | Cliente autenticado | Base |
| `/consentimientos/finalidades` | Catálogo de finalidades con alcance, vigencia, entidades y versión del texto legal | WEB-F02 |
| `/consentimientos` | Listar y otorgar | WEB-F02 |
| `/consentimientos/{id}/revocacion` · `/renovacion` · `/eventos` | Revocar, renovar e histórico | WEB-F02 |
| `/cotizaciones/productos` | Catálogo de productos | WEB-F01 |
| `/cotizaciones` · `/cotizaciones/{id}` | Cotizar y consultar: prima personalizada o estándar con su motivo, ahorro, coberturas, factores del precio y vigencia de 5 minutos | WEB-F01 |
| `/cotizaciones/{id}/recotizacion` | Nueva cotización con los mismos datos cuando la anterior venció | WEB-F01 |

## Checkout (WEB-F03, rebanada)

Rebanada del Sprint 1 que se entregó después (0.4.0). La firma y la emisión llegan con el resto de
WEB-F03.

| Recurso | Operaciones | Historia |
|---|---|---|
| `/suscripciones` | Aceptar una cotización vigente; idempotente por cotización | WEB-F03 |
| `/suscripciones/{id}` | Suscripción y su último pago | WEB-F03 |
| `/suscripciones/{id}/pagos` | Pagar contra la pasarela simulada con `Idempotency-Key`: aprobado, rechazado o pendiente, sin cobro doble | WEB-F03 |
| `/suscripciones/{id}/pagos/{id_pago}` | Consultar un pago, por ejemplo uno pendiente | WEB-F03 |

## Sesión (WEB-F13)

| Recurso | Operaciones | Historia |
|---|---|---|
| `/sesion` | Iniciar sesión con correo y contraseña (cookie `sesion`) y cerrarla | WEB-F13 |

## Siniestros (WEB-F05)

| Recurso | Operaciones | Historia |
|---|---|---|
| `/polizas` | Pólizas del cliente con vigencia y estado, para elegir la afectada | WEB-F05 |
| `/siniestros` | Listar y reportar, con `Idempotency-Key`: la misma clave devuelve el mismo siniestro | WEB-F05 |
| `/siniestros/{id}` | Detalle con estado y siguiente paso | WEB-F05 |
| `/siniestros/{id}/evidencias` · `/{id_evidencia}` | Adjuntar (multipart, hasta 10 MB), listar y descargar | MOB-F05 |

El reporte es el mismo en los dos canales: mismas rutas, misma referencia y mismos códigos.

## Historial

Cada versión del contrato, con su historia y sus cambios incompatibles: [`CHANGELOG.md`](CHANGELOG.md).

## Reglas de cambio

- Mientras `info.version` sea **0.x** (sin BFF ni consumidores en producción), un cambio incompatible
  sube la versión MENOR y se explica en el PR. Así pasó en 0.2.0, que amplió la cotización.
- Desde la 1.0, un cambio **compatible** (campo opcional nuevo, operación nueva) sube la versión
  MENOR de `info.version`.
- Desde la 1.0, un cambio **incompatible** (quitar o renombrar un campo, cambiar un tipo) no se hace sobre `/v1`:
  se publica bajo `/api/v2/web` sin retirar `/v1`, según la fachada versionada de ARQ-01.
- Todo cambio entra por PR y el CI lo valida con Redocly (`npm run contratos:validar` en `apps/web`).
