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
| `/cotizaciones` · `/cotizaciones/{id}` | Cotizar y consultar, con tarifa personalizada o estándar y factores del precio | WEB-F01 |

## Reglas de cambio

- Un cambio **compatible** (campo opcional nuevo, operación nueva) sube la versión MENOR de
  `info.version`.
- Un cambio **incompatible** (quitar o renombrar un campo, cambiar un tipo) no se hace sobre `/v1`:
  se publica bajo `/api/v2/web` sin retirar `/v1`, según la fachada versionada de ARQ-01.
- Todo cambio entra por PR y el CI lo valida con Redocly (`npm run contratos:validar` en `apps/web`).
