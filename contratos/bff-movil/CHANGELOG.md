# Changelog · contrato `bff-movil`

Cambios de [`openapi.yaml`](openapi.yaml), del más reciente al más antiguo. Formato basado en
[Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/); la versión es la de `info.version`
(ver "Reglas de cambio" en el [README](README.md)). Cada entrada nombra su historia, y los cambios
que rompen a un cliente existente van marcados como **incompatible**.

## [0.8.0] · 2026-11-08 · MOB-F06

Rutas nuevas, solo en este contrato: el push es propio de la app.

### Agregado

- `POST /dispositivos` (`SolicitudDispositivo` con `token_push` y `plataforma: android`): registra
  el dispositivo para recibir push. Responde 201 si el token es nuevo y 200 si ya estaba (queda con
  esta cuenta), así la app puede registrarlo en cada arranque. 422 `token_push_invalido` si está
  vacío, tiene espacios o pasa de 4096 caracteres.
- `DELETE /dispositivos/{id}`: da de baja el dispositivo al cerrar sesión. 404 si no existe.
- `GET /notificaciones` (`limite` de 1 a 100, 50 por defecto): notificaciones del cliente, de la más
  reciente a la más antigua: una por cada cambio de estado de un siniestro, aunque el núcleo
  reciba el evento dos veces.
- `POST /notificaciones/{id}/lectura`: marca una notificación como leída. 404 si no existe.
- Esquemas `SolicitudDispositivo`, `Dispositivo` y `Notificacion`. En `datos`, cada notificación de
  un siniestro trae `tipo: siniestro_estado` y el `id_siniestro` que la app abre al tocarla.
- La descripción del contrato explica cómo recibe la app el push mientras el proveedor sea
  simulado: registra el token al iniciar sesión, lee `GET /notificaciones` y muestra cada una como
  notificación local.

## [0.7.0] · 2026-11-08 · WEB-F06

Compatible: los enums de estado y de siguiente paso no cambian, y la app los sigue leyendo como
enums cerrados.

### Agregado

- `GET /siniestros/{id}` responde `SiniestroDetalle`, con el `historial` de eventos y los
  `requerimientos`, igual que en `bff-web` 0.11.0.
- `Siniestro.estado_actualizado_en`, opcional.
- `POST /siniestros/{id}/evidencias` acepta `id_requerimiento` para responder un requerimiento.
  Errores: 404 `requerimiento_no_encontrado`; 409 `requerimiento_ya_atendido`.
- Esquemas `SiniestroDetalle`, `EventoSiniestro` y `Requerimiento`.

## [0.6.0] · 2026-11-08 · WEB-F04

Compatible: la app ignora los campos desconocidos, así que el resumen ampliado no la rompe.

### Agregado

- `GET /polizas/{numero}`: detalle con coberturas, deducible, valor asegurado y fecha de emisión,
  igual que en `bff-web` 0.10.0. 404 `poliza_no_encontrada`, también si es de otro cliente.
- `GET /polizas/{numero}/documento`: el PDF de la póliza emitida en la web, con la sesión de la app.
  404 `documento_no_disponible` en las pólizas de prueba.
- `PolizaResumen` suma `nombre_producto`, `prima_mensual` y `documento_url`, opcionales.
- Esquema `Cobertura`. Las rutas de pólizas pasan a su propia etiqueta, `polizas`.

## [0.5.0] · 2026-10-31 · WEB-F13

Los códigos nuevos son compatibles: la app relee la verificación ante cualquier 409 y muestra su
pantalla de error ante un 503.

### Agregado

- `POST /identidad/verificacion`: 409 `correo_no_verificado`, `reintento_no_permitido` y
  `reintento_sin_cambios`, además de los que ya existían.
- `GET` y `POST /identidad/verificacion`: 503 `proveedor_kyc_no_disponible` y
  `nucleo_no_disponible`, que el BFF ya devolvía sin estar documentados.

### Cambiado

- `POST /identidad/verificacion`: se documenta que no lleva cuerpo y que `verificacion_en_curso`
  también aplica mientras un intento anterior espera al proveedor.

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
