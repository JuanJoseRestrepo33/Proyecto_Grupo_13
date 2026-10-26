# Changelog · contrato `bff-movil`

Cambios de [`openapi.yaml`](openapi.yaml), del más reciente al más antiguo. Formato basado en
[Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/); la versión es la de `info.version`
(ver "Reglas de cambio" en el [README](README.md)). Cada entrada nombra su historia, y los cambios
que rompen a un cliente existente van marcados como **incompatible**.

## [Sin publicar]

- WEB-F13 (#152): el BFF real implementa registro, OTP, `/sesiones`, `/sesiones/renovacion` y
  `/sesion` tal como están. Se documenta que el 401 de toda ruta protegida lleva el código
  `sesion_no_vigente` y `WWW-Authenticate: Bearer`; no cambia ningún esquema.
- Sprint 2: se esperan las rutas de consentimiento en v0.2 (MOB-F02, #160).

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
