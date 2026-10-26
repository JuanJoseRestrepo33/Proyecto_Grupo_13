# Changelog · contrato `bff-movil`

Cambios de [`openapi.yaml`](openapi.yaml), del más reciente al más antiguo. Formato basado en
[Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/); la versión es la de `info.version`
(ver "Reglas de cambio" en el [README](README.md)). Cada entrada nombra su historia, y los cambios
que rompen a un cliente existente van marcados como **incompatible**.

## [0.4.0] · 2026-10-26 · MOB-F05

### Agregado

- `POST /siniestros/{id}/evidencias` (`multipart/form-data`, campo `archivo`): adjunta una foto,
  un video o un documento de hasta 10 MB, con la fecha de la cámara y la ubicación, o el lugar
  escrito, opcionales. Errores: 413 `archivo_demasiado_grande`; 422 `formato_no_admitido`,
  `archivo_vacio`, `ubicacion_invalida` y `fecha_de_captura_invalida`; 409 `siniestro_cerrado`;
  503 `almacenamiento_no_disponible`.
- `GET /siniestros/{id}/evidencias` y `GET /siniestros/{id}/evidencias/{id_evidencia}`: listar
  y descargar. La evidencia agregada en un canal se ve en el otro.
- Esquemas `SolicitudEvidencia` y `Evidencia`.

## [0.3.0] · 2026-10-26 · WEB-F05

### Agregado

- `GET /polizas`: pólizas del cliente con su vigencia y estado, para elegir la afectada en el
  reporte. WEB-F04 amplía esta misma respuesta con coberturas, deducibles y documento.
- `GET /siniestros`, `POST /siniestros` y `GET /siniestros/{id}`: reportar y consultar
  siniestros. El reporte exige `Idempotency-Key` (UUID): la misma clave responde 200 con el mismo
  siniestro. Errores: 404 `poliza_no_encontrada`; 409 `evento_fuera_de_vigencia` (con
  `vigente_desde` y `vigente_hasta`) y `clave_idempotencia_en_uso`; 422 `fecha_futura` y
  `clave_idempotencia_invalida`.
- Esquemas `PolizaResumen`, `EstadoPoliza`, `TipoEventoSiniestro`, `EstadoSiniestro`,
  `SiguientePaso`, `SolicitudSiniestro`, `Siniestro` y `ErrorVigencia`. El esquema `Dinero` es el mismo del contrato web.

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
