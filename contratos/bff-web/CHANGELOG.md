# Changelog · contrato `bff-web`

Cambios de [`openapi.yaml`](openapi.yaml), del más reciente al más antiguo. Formato basado en
[Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/); la versión es la de `info.version`
(ver "Reglas de cambio" en el [README](README.md)). Cada entrada nombra su historia, y los cambios
que rompen a un cliente existente van marcados como **incompatible**.

## [0.6.0] · 2026-10-26 · WEB-F01

El BFF real (#164) implementa las rutas de cotización.

### Agregado

- `motivo_tarifa` admite `proveedor_no_disponible`: había consentimiento vigente, pero el proveedor
  de Open Finance no respondió y no había perfil en caché, así que la prima es la estándar. La web
  lo explica distinto de `sin_consentimiento` (criterio 3).

## [0.5.0] · 2026-10-26 · WEB-F13

### Agregado

- `POST /sesion`: inicia sesión con correo y contraseña (esquema `Credenciales`, el mismo del
  contrato móvil). Responde el mismo `Sesion` de `GET /sesion` y pone la cookie `sesion`
  (`HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/api/v1/web`). Con credenciales inválidas responde
  401 `credenciales_invalidas`, igual exista o no el correo.
- `DELETE /sesion`: cierra la sesión y borra la cookie. Responde 204 aunque la sesión ya no sea
  válida.
- Código `sesion_no_vigente` en el 401 de toda ruta protegida: falta la cookie, venció o su firma
  no es válida.

## [0.4.0] · 2026-10-25 · WEB-F03 (rebanada)

### Agregado

- `POST /suscripciones`: acepta una cotización vigente. Es idempotente por cotización (aceptar de
  nuevo devuelve la misma suscripción con 200); una cotización vencida responde 409
  `cotizacion_vencida`.
- `GET /suscripciones/{id}`: suscripción y su último pago.
- `POST /suscripciones/{id}/pagos` con el encabezado obligatorio `Idempotency-Key`: paga contra la
  pasarela simulada (`aprobado`, `rechazado` o `pendiente`). La misma clave nunca cobra dos veces:
  responde el mismo pago con `reintento: true`, o 409 `pago_en_curso` si el primero sigue en proceso.
  Con otra clave y un pago aprobado o pendiente, 409 `pago_ya_registrado`. El cliente no envía datos
  de tarjeta ni de cuenta.
- `GET /suscripciones/{id}/pagos/{id_pago}`: consulta un pago, por ejemplo uno pendiente.
- Esquemas `SolicitudSuscripcion`, `Suscripcion`, `MedioPago`, `SolicitudPago` y `Pago`.

## [0.3.0] · 2026-10-18 · WEB-F02

### Agregado

- `Consentimiento.por_vencer` y `Consentimiento.dias_para_vencer` (obligatorios): vigente a 30 días
  o menos de vencer y días que faltan (`null` si no está vigente), para avisar y ofrecer renovar
  (criterio 7). **Incompatible** para quien construye respuestas: el BFF debe enviarlos.

## [0.2.0] · 2026-10-18 · WEB-F01

### Agregado

- `POST /cotizaciones/{id}/recotizacion`: nueva cotización con los mismos datos cuando la anterior
  venció.
- `Producto`: `nombre_corto`, `prima_estandar`, `valor_asegurado`, `deducible` y `coberturas`.
- `Cotizacion`: `nombre_producto`, `prima_estandar`, `ahorro_mensual`, `tipo_tarifa`,
  `base_perfilamiento`, `motivo_tarifa`, `factores`, `version_regla`, condiciones y vigencia
  (`vigente_hasta`, 5 minutos).

### Cambiado

- **Incompatible:** `SolicitudCotizacion` pasa de `{ producto, datos }` a
  `{ producto, asegurado, usar_open_finance }`, con `DatosAsegurado` (`edad`, `ciudad`,
  `valor_asegurado`).

## [0.1.1] · 2026-10-11 · WEB-F01

### Corregido

- Los montos (`Dinero.valor`) son enteros en la unidad mayor de la moneda, sin decimales (pesos
  para COP: `132900` = $132.900), no en la unidad mínima. `moneda` es un código ISO 4217.

## [0.1.0] · 2026-10-11 · WEB-F01, WEB-F02

### Agregado

- Primera versión del contrato, con los mocks del cliente web.
- `GET /sesion`: cliente autenticado (cookie `sesion`).
- Consentimiento (WEB-F02): `/consentimientos/finalidades`, `/consentimientos` (listar y otorgar),
  `/consentimientos/{id}/revocacion`, `/renovacion` y `/eventos`.
- Cotización (WEB-F01): `/cotizaciones/productos`, `POST /cotizaciones` y
  `GET /cotizaciones/{id}`.
