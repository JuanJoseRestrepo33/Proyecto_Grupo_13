# Changelog · contrato `bff-movil`

Cambios de [`openapi.yaml`](openapi.yaml), del más reciente al más antiguo. Formato basado en
[Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/); la versión es la de `info.version`
(ver "Reglas de cambio" en el [README](README.md)). Cada entrada nombra su historia, y los cambios
que rompen a un cliente existente van marcados como **incompatible**.

## [0.2.0] · 2026-10-26 · MOB-F02 y WEB-F13

### Agregado

- MOB-F02 (#160): `/consentimientos/finalidades`, `/consentimientos` (listar y otorgar),
  `/consentimientos/{id}/revocacion`, `/renovacion` y `/eventos`, con los mismos esquemas y códigos
  de error del contrato `bff-web`. El canal `movil` queda en el histórico del consentimiento.
- WEB-F13 (#152): el 401 de toda ruta protegida documenta el código `sesion_no_vigente` y la
  cabecera `WWW-Authenticate: Bearer`. El BFF real implementa registro, OTP y sesiones tal como
  estaban.

## [0.1.0] · 2026-10-17 · MOB-F01

### Agregado

- Primera versión del contrato, con el mock en memoria del build debug de la app.
- `POST /registros`: crear la cuenta (queda *pendiente de verificación*) y enviar el OTP.
- `/registros/{id}/otp/verificacion` y `/reenvio`: verificar el correo (3 intentos por código) y pedir
  un código nuevo.
- `POST /sesiones`: ingreso con correo y contraseña; `POST /sesiones/renovacion`: renovar la sesión
  con el token de refresco (ingreso biométrico).
- `GET /sesion`: cliente autenticado y estado de su identidad.
- `/identidad/verificacion`: solicitar y consultar la verificación KYC/AML (simulada).
