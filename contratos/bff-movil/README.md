# Contrato del BFF móvil

[`openapi.yaml`](openapi.yaml) es la fuente de verdad entre la app Android y su Backend for Frontend.
Lo consumen:

- **La app Android** ([`mobile/android`](../../mobile/android)): `core/network` lo implementa con
  Retrofit (`BffApi` y `Modelos.kt`), y el build debug trabaja contra un mock en memoria que cumple
  este mismo contrato (`app/src/debug/.../mock`) mientras el BFF no exista.
- **El BFF móvil**, cuando se construya: debe cumplirlo tal cual.

## Alcance del Sprint 1

| Recurso | Operaciones | Historia |
|---|---|---|
| `/registros` | Crear la cuenta (queda *pendiente de verificación*) y enviar el OTP | MOB-F01 |
| `/registros/{id}/otp/verificacion` · `/reenvio` | Verificar el correo (3 intentos por código) y pedir un código nuevo | MOB-F01 |
| `/sesiones` | Ingreso con correo y contraseña; respaldo de la biometría | MOB-F01 |
| `/sesiones/renovacion` | Renovar la sesión con el token de refresco (ingreso biométrico) | MOB-F01 |
| `/sesion` | Cliente autenticado y estado de su identidad | MOB-F01 |
| `/identidad/verificacion` | Solicitar y consultar la verificación KYC/AML (simulada) | MOB-F01 |

**Biometría.** El BFF nunca recibe el dato biométrico: la app guarda el token de refresco cifrado
con una llave del Android Keystore que solo se desbloquea con huella o rostro, y lo envía a
`/sesiones/renovacion`.

**Fuera del Sprint 1:** captura guiada de documento y selfie, cola ante caída del proveedor de
KYC/AML, recuperación de contraseña.

## Reglas de cambio

- Un cambio **compatible** (campo opcional nuevo, operación nueva) sube la versión MENOR de
  `info.version`.
- Un cambio **incompatible** (quitar o renombrar un campo, cambiar un tipo) no se hace sobre `/v1`:
  se publica bajo `/api/v2/movil` sin retirar `/v1`, según la fachada versionada de ARQ-01.
- Todo cambio entra por PR y el workflow `mobile-android.yml` lo valida con Redocly:

  ```bash
  npx @redocly/cli@2.62.1 lint --config contratos/bff-movil/redocly.yaml contratos/bff-movil/openapi.yaml
  ```

- Si cambia un esquema, actualiza también `core/network` y el mock de debug en el mismo PR.
