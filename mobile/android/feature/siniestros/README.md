# WEB-F05, MOB-F05, MOB-F05-02 y WEB-F06 · Reporte, evidencia, reporte sin conexión y seguimiento de siniestros en la app

Asistente para que el cliente asegurado reporte un siniestro desde Android y termine con el número de
referencia y el siguiente paso de su caso. Usa las mismas rutas, la misma referencia y la misma clave de
idempotencia que la web ([`contratos/bff-movil`](../../../../contratos/bff-movil/openapi.yaml) 0.3.0):
un siniestro reportado en la app se ve igual en la web, con `canal: movil`. En debug, el mock de
`app/src/debug` responde los escenarios descritos en el README de `mobile/android`.

## Flujo

```
Inicio ──Reportar siniestro──▶ 1 · Póliza ──▶ 2 · Información ──▶ 3 · Evidencia ──▶ 4 · Revisión ──Enviar──▶ Confirmación
                                  │                                 (opcional)         │  ▲                 (referencia, estado,
                                  │ sin pólizas                                        │  └─ Cambiar         carga, evidencia)
                                  ▼                                                    │
                        «Necesitas una póliza»     404 ──▶ paso 1 (lista refrescada)
                                                   422 ──▶ paso 2 (error en su campo)
                                                   409 vigencia ──▶ aviso con las fechas de la póliza
```

La web tiene tres pasos (información, evidencia y revisión); en la app la póliza va en un paso propio
porque la lista no cabe cómoda junto al formulario.

`SolventaApp` (en `app`) abre el asistente desde Inicio; este módulo expone `RutaReporteSiniestro`, que
crea el ViewModel, y `PantallaReporte`/`PantallaConfirmacion`, sin estado propio, que usan las pruebas.

| Pieza | Qué resuelve | Criterios |
|---|---|---|
| `ValidadorReporte` | Reglas de `SolicitudSiniestro`: póliza y tipo elegidos, fecha no futura, descripción de 10 a 2000 caracteres (sin contar espacios al borde) y monto opcional en pesos enteros, con o sin punto de miles. Un monto con decimales es un error, nunca otro monto. | 2 |
| `ReporteViewModel` | Pasos, validación en línea (el error aparece al salir del campo o al intentar avanzar, y se actualiza mientras se corrige), envío y traducción de los rechazos del BFF. | 1–6 |
| `PasoPoliza` | Pólizas del cliente con producto, número, vigencia y estado. Una vencida se puede elegir: el BFF acepta el evento si ocurrió dentro de su vigencia. | 3, 5 |
| `PasoInformacion` · `SelectorFecha` | Tipo de evento con texto legible, calendario que no deja elegir días posteriores a hoy, descripción y monto. | 2 |
| `PasoRevision` | Resumen con «Cambiar» por cada dato y «Enviar reporte». | 1 |
| `PantallaConfirmacion` | Referencia `SIN-AAAA-NNNNNN`, estado y `siguiente_paso` explicado en lenguaje claro. | 1 |

### Idempotencia

- La `Idempotency-Key` (UUID v4) nace **una vez por reporte**, al entrar a la revisión, y se reutiliza
  en cada reintento. Mientras la solicitud está en curso el botón queda ocupado: un doble toque no
  envía otra.
- Sin red, tiempo agotado o un error del servidor sin código del contrato: el envío queda
  **pendiente**. El reintento usa la misma clave y los mismos datos, y el BFF responde con el mismo
  siniestro. Mientras está pendiente no se pueden cambiar los datos: con otros datos, la misma clave
  daría `clave_idempotencia_en_uso` y una clave nueva crearía un duplicado si el primer envío sí llegó.
- Desde MOB-F05-02 todo envío pasa por un borrador cifrado: el detalle de los reintentos, la clave en
  uso y los rechazos está en [Reporte sin conexión](#reporte-sin-conexión-mob-f05-02).

## Evidencia (MOB-F05)

El paso es opcional. Primero se envía `POST /siniestros` y después cada archivo, uno a uno, como en la
web: un fallo de evidencia no deshace el reporte. La confirmación dice qué se adjuntó, qué no y por qué,
y desde ahí se reintenta o se agrega más con el mismo paso (`canal: movil`, visible en la web).

```
Tomar foto ──▶ explicación ──▶ permisos ──┬─ cámara ──▶ foto + tomada_en + lectura única de ubicación
                                          └─ negada ──▶ galería o PDF (siguen disponibles)
Sin ubicación o sin señal (8 s) ──▶ campo «Lugar»        Denegación permanente ──▶ aviso y «Abrir ajustes»
```

| Pieza | Qué resuelve | Criterios |
|---|---|---|
| `GestorEvidencia` | Lista de adjuntos, carga, mapeo de errores, reintento y borrador. Lo posee `ReporteViewModel`. | 1–3, 7–10 |
| `ReglasEvidencia.kt` | Formato por firma de bytes (el criterio de `tipo_real` del núcleo), presupuesto de video, `LimpiadorMp4` y `clasificarPermiso`. JVM puro. | 4–6, 11 |
| `AlmacenEvidenciasAndroid` | Copia a la caché, normaliza, comprime y limpia. | 5, 6, 11 |
| `CompresorVideo` | Media3 Transformer a H.264 720p con el presupuesto de bits. | 5 |
| `LectorUbicacionAndroid` | Una lectura de Play Services con tiempo límite. | 1, 3 |
| `PantallaCamara` | CameraX dentro de la app. | 1 |
| `AccionesEvidencia` | Selector de fotos y de documentos (sin permiso de almacenamiento), permisos y ajustes. | 2, 4 |
| `PasoEvidencia` · `SeccionEvidencia` | Pantallas sin estado propio. | 2–7, 9, 12 |

### Formatos y compresión

| Entra | Sale |
|---|---|
| JPEG o PNG de hasta 10 MB | El mismo archivo, sin coordenadas en EXIF ni XMP |
| JPEG o PNG de más de 10 MB | JPEG reducido (lado de 4096 a 1600 px, calidad 85 o 70) |
| HEIC | JPEG siempre: ExifInterface no puede reescribir HEIC. Si el dispositivo no lo decodifica (API 26–27), se rechaza como formato no admitido |
| MP4 de hasta 10 MB | El mismo video, con `udta`, `meta` y `uuid` convertidas en `free` |
| MP4 de más de 10 MB | H.264 720p con AAC, y la misma limpieza; si no cabe, se explica y se ofrece quitarlo |
| PDF | Tal cual; si pasa de 10 MB, se explica y se ofrece quitarlo |

Si la limpieza no se puede completar, una imagen se vuelve a codificar y un video se rechaza: nunca se
envía un archivo sin limpiar.

### Reintentos sin duplicar

`POST /siniestros/{id}/evidencias` no tiene clave de idempotencia. Cada archivo lleva un
`nombre_original` único y sin datos del cliente (`evidencia-<uuid>.jpg`). Antes de reintentar se consulta
`listarEvidencias`: lo que ya está no se reenvía, y si no se puede consultar no se reenvía nada. Lo que
se estaba enviando cuando murió el proceso se trata igual. Solo se ofrece reintentar lo pasajero (503,
sin red, errores desconocidos); `413`, `422`, `409` y `404` se explican.

La carga usa un cliente con 3 minutos de `callTimeout`, elegido por llamada para los `multipart`
(`conCargas` en `NetworkClient`); las demás operaciones conservan sus 15 s.

### Archivos temporales

Viven en `cacheDir/evidencias`, fuera de las copias de seguridad. Se borran al enviarse, al quitarlos y
al salir del reporte. Lo que quede de un reporte abandonado se borra la próxima vez que se abre.

### Rotación y segundo plano

Lo ingresado, el paso, la clave, el envío pendiente y la confirmación se guardan en el
`SavedStateHandle`: sobreviven a la rotación y a la muerte del proceso en segundo plano (criterio 6).
Los adjuntos y el lugar escrito también; sus archivos siguen en la caché.
Las pólizas se vuelven a consultar al restaurar; una póliza elegida que ya no aparece deja de estarlo.

Todos los textos están en `src/main/res/values/strings.xml`; los controles miden al menos 48 dp y los
campos y botones tienen descripción para TalkBack («Cambiar póliza», «Elegir la fecha del evento»).
Los componentes de `comun` son una copia de los de `feature/onboarding`, porque los módulos de feature
no dependen entre sí; si un tercer módulo los necesita, conviene moverlos a `core/designsystem`.

## Pruebas

- **JVM** (`src/test`): validador, calendario (sin días futuros) y `ReporteViewModel` con un `BffApi`
  falso (`Dobles.kt`): validación por paso, reutilización de la clave en el reintento, cambio de clave
  con `clave_idempotencia_en_uso`, mapeo de 401/404/409/422, envío pendiente y restauración del
  borrador.
- **Compose + Robolectric** (`src/testDebug`): el recorrido completo, errores por campo, avisos del BFF,
  botón ocupado, objetivos táctiles de 48 dp y descripciones para TalkBack.
- **App** (`app/src/testDebug`): escenarios del mock (`MockSiniestrosTest`, `MockEvidenciasTest`) y el
  reporte de punta a punta desde el ingreso (`SolventaRaizTest`).
- **Evidencia:** `ReglasEvidenciaTest` y `GestorEvidenciaTest` en JVM; `LimpiarExifTest` y
  `PasoEvidenciaTest` con Robolectric. `CompresorVideo`, `PantallaCamara` y `LectorUbicacionAndroid`
  dependen del dispositivo y se prueban a mano o en el E2E (#179).

## Reporte sin conexión (MOB-F05-02)

El cliente reporta aunque no tenga señal. «Enviar reporte» guarda primero un **borrador cifrado** en el
teléfono, con o sin red, y después lo envía. Si no hay red, o si la respuesta no llega, la app lo
reintenta sola con la **misma** `Idempotency-Key`: un reenvío nunca crea un segundo siniestro. El
resultado es el de un reporte en línea: una referencia `SIN-AAAA-NNNNNN` que también se ve en la web.

```text
Revisión ──Enviar──▶ guardar borrador cifrado (clave + datos + manifiesto)
                        │ falla la escritura ──▶ M15 «No pudimos guardar» (sigue en la revisión)
                        ▼
                  ¿hay red? ──no──▶ M4a «Guardado» ──▶ Inicio: ESPERANDO_CONEXION (M5)
                        │ sí                                  │ vuelve la red · ingreso · abrir la app
                        ▼                                     ▼
                     ENVIANDO (uno a la vez, con la sesión del dueño) ◀┘
     ┌──────────────┬──────────────────┬──────────────────┬───────────────────────────┐
  201 / 200     sin red · timeout    401 sin renovar   404 · 409 vigencia · 422    409 clave en uso
     ▼          · 5xx (incierto)         ▼                   │                          │
 REPORTADO          ▼              M12 «Ingresa…»   ¿hubo intento incierto?             │
 (id + ref)    M4b / espera,                        no ─▶ REQUIERE_ATENCION (M9)        │
     ▼         misma clave                          sí ─▶ POR_CONCILIAR (M10) ◀─────────┘
 SUBIENDO_EVIDENCIA                                        listarSiniestros:
 (concilia con listarEvidencias)                            encontrado ─▶ REPORTADO
     ▼                                                      no ─▶ REQUIERE_ATENCION
 ENVIADO ─▶ se borra el borrador; queda {referencia, enviado_en} ─▶ M7 hasta «Entendido»
```

| Pieza | Qué resuelve | Criterios |
|---|---|---|
| `AlmacenBorradoresCifrado` | Borradores en `noBackupFilesDir/borradores/<cliente.id>/<id>/`: `borrador.bin` (manifiesto) y un `<adjunto>.bin` por archivo, más la caché de pólizas y los registros enviados. Escritura atómica (temporal, `fsync` y `rename`). JVM puro. | 1, 6, 10–12 |
| `CifradoAesGcm` · `LlaveKeystore` | AES-256-GCM en flujo, con un IV por archivo, y la llave `solventa.borradores` del Android Keystore. | 6, 11 |
| `EnviadorBorradores` | Estados, envío al reconectar, esperas, conciliación, evidencia y tarjetas de Inicio. Alcance de aplicación (vive en `Proceso`, en `MainActivity`). | 1, 3–5, 7–9, 12 |
| `MonitorConectividad` | `NetworkCallback` de la red por defecto con `NET_CAPABILITY_VALIDATED`, detrás de un puerto. | 3 |
| `Conciliacion.kt` | Clasifica la respuesta del `POST` y busca en `listarSiniestros` el reporte de un intento anterior. | 4, 8 |
| `TarjetasBorradores` · `PantallaGuardado` | Tarjetas de Inicio (M5–M14) y «Reporte guardado» (M4a y M4b). | 1, 3, 5, 7–9, 11, 13 |
| `InterceptorDuenio` (`core/network`) | Cada solicitud del borrador lleva la etiqueta `DuenioSolicitud`; si la sesión vigente es de otra cuenta, no sale. | 9 |

### Estados

`ESPERANDO_CONEXION` → `ENVIANDO` → `REPORTADO` (el id y la referencia se guardan **antes** de subir la
evidencia) → `SUBIENDO_EVIDENCIA` → `ENVIADO`. Además: `REQUIERE_ATENCION`, `POR_CONCILIAR` e `ILEGIBLE`
(este último no se guarda: es lo que se muestra de un borrador que no se puede descifrar). Cada
transición se escribe en disco antes de seguir, así que tras la muerte del proceso o un reinicio se
retoma desde donde quedó:

- Un borrador en `ENVIANDO` es un intento que quizá llegó: se reintenta como incierto, con su clave.
- Un borrador en `SUBIENDO_EVIDENCIA` se concilia con `listarEvidencias` antes de seguir, con los mismos
  `nombre_original`.

### Envío, esperas y exclusión mutua

- El enviador procesa los borradores del cliente con sesión al volver la red, al ingresar y al abrir la
  app con sesión. Uno a la vez y en orden de creación; un solo `Mutex` serializa el envío, la subida de
  evidencia, el descarte y la corrección (por eso la tarjeta no ofrece «Descartar» mientras envía).
- Sin red, con tiempo agotado o con un 5xx sin código del contrato, el intento queda **incierto** y se
  reintenta con la misma clave y los mismos datos tras 30 s, 1, 2, 5 y 10 min, mientras la red siga
  arriba. La espera vuelve a empezar cuando la red regresa.
- El primer envío desde el asistente corre en el alcance de la app y reserva su borrador: si hay red y el
  BFF responde 201/200 se muestra la confirmación de #169 y la evidencia sigue subiendo con el enviador,
  aunque el cliente salga.

### Rechazos y conciliación

El núcleo valida fecha, póliza y vigencia **antes** de resolver la idempotencia
(`backend/app/modulos/siniestros/aplicacion/servicio.py`). Un rechazo no prueba que un intento anterior
no haya registrado el siniestro:

- **Sin intento incierto previo** → `REQUIERE_ATENCION`. «Revisar» abre el asistente en el paso afectado
  con el aviso de #169, y al enviar se reemplazan los datos del mismo borrador con una **clave nueva**.
- **Con intento incierto previo** → `POR_CONCILIAR`. Se conservan la clave y los datos, y se busca en
  `listarSiniestros` uno con la misma póliza, tipo, fecha, descripción y monto, `canal: movil`, reportado
  desde la creación del borrador (con un día de margen por el reloj del teléfono). Si lo encuentra, pasa
  a `REPORTADO` con esa referencia; si no, a `REQUIERE_ATENCION`; si no puede consultar, sigue por
  conciliar y lo vuelve a intentar. Mientras tanto no se ofrece corregir con una clave nueva.
- **`409 clave_idempotencia_en_uso`**: no se rota la clave; el borrador pasa a `POR_CONCILIAR`. Si el BFF
  informa la `referencia`, la tarjeta la muestra (el contrato 0.4.0 aún no la publica: el campo es
  opcional en `ErrorBff`).

### Cifrado en reposo (RC-04)

- Llave AES-256-GCM `solventa.borradores` del Android Keystore. No exige autenticación del usuario ni
  `setUnlockedDeviceRequired`: el envío puede ocurrir con la pantalla bloqueada, y la misma política vale
  desde API 26. La protección viene del aislamiento de la app, de la llave ligada al Keystore (por
  hardware cuando el equipo lo tiene) y de que `noBackupFilesDir` queda fuera de las copias de seguridad.
- Cada archivo lleva su IV: `versión · largo del IV · IV · texto cifrado y etiqueta`. Un archivo alterado
  no se descifra.
- Al confirmarse el guardado, los archivos de `cacheDir/evidencias` pasan al almacén cifrado y se borran
  de la caché, y el `SavedStateHandle` del asistente queda solo con el identificador opaco del borrador
  (la confirmación guarda referencia, estado y siguiente paso, sin póliza ni descripción).
- La evidencia se sube con un `RequestBody` que descifra en flujo mientras escribe en el socket: no queda
  copia en claro en disco. Antes de subir un archivo se verifica que se pueda descifrar entero.
- Errores **temporales** (`KeyStoreException`, E/S): el borrador se conserva y se reintenta. Errores
  **permanentes** (sin llave, `KeyPermanentlyInvalidatedException`, `AEADBadTagException`): queda
  ilegible, Inicio lo explica (M14) y solo se elimina si el cliente lo decide.

### Pólizas sin conexión

Se guarda cifrada la última lista de `GET /polizas` que cargó bien, por cliente y con su fecha. Sin red,
el paso 1 la muestra con «Última actualización: …» (M1); si no hay lista, explica que hace falta
conexión una vez (M2). La vigencia la valida el BFF al enviar.

### Sesión y aislamiento

- Borradores, caché de pólizas y registros enviados están separados por `cliente.id`.
- `GestorSesion` avisa a su `ObservadorCuenta` cuando cambia la cuenta: el enviador se detiene **antes**
  de reemplazar la sesión y empieza con la cuenta nueva **después**. Además, cada solicitud lleva la
  etiqueta `DuenioSolicitud`, e `InterceptorDuenio` la compara con la sesión vigente antes de enviarla y
  otra vez en el intento que repite el autenticador tras renovar.
- Perfil ofrece «Cerrar sesión»; con borradores pendientes avisa que siguen guardados y cifrados para esa
  cuenta (M13).

### Límite de la evidencia

`POST /siniestros/{id}/evidencias` no tiene clave de idempotencia y el backend no valida que el nombre
sea único. Si un `POST` anterior sigue en proceso cuando el `GET` todavía no lo muestra, podría quedar un
duplicado. Una garantía estricta necesita soporte del servidor (fuera de alcance).

### Por qué el envío depende de la sesión (y no de WorkManager)

Mientras el proceso vive, la sesión está en memoria (`GestorSesion`) y `RenovadorSesion` renueva el token
sin interacción. Si el proceso muere, la sesión se pierde, y el token de refresco guardado solo se lee con
biometría (`AlmacenTokenKeystore`): un *worker* en un proceso nuevo no tendría sesión. Por eso el envío
autónomo se garantiza **mientras la app tiene sesión**, aunque esté en segundo plano. Si el proceso murió,
se reanuda en cuanto el cliente vuelve a ingresar, sin reescribir ni reenviar el reporte. Ningún texto de
la app promete un envío con la app cerrada.

### Pruebas del reporte sin conexión

- **JVM** (`src/test/.../borrador`): `AlmacenBorradoresCifradoTest` (AES-GCM de la JCA, texto alterado,
  sin llave, error temporal, escritura fallida, orden y separación por cliente), `ConciliacionTest` y
  `EnviadorBorradoresTest` con `BffApi`, `MonitorConectividad` y un puerto criptográfico falsos: guardado
  antes del primer `POST`, fallo de escritura, misma clave en cada reintento, muerte del proceso en
  `ENVIANDO` y en `SUBIENDO_EVIDENCIA`, rechazos con y sin intento incierto, conciliación (encontrado, no
  encontrado y sin red), clave en uso sin rotar, esperas, orden, exclusión mutua y cambio de cuenta.
- **Compose + Robolectric**: «Reporte guardado» (las dos variantes), «No pudimos guardar», pólizas sin
  conexión y tarjetas de Inicio (`TarjetasBorradoresTest`); en `app`, el recorrido sin red → al volver la
  red Inicio muestra la referencia, y cerrar sesión con un pendiente (`SolventaRaizTest`).
- **Instrumentada** (`src/androidTest`): `AlmacenKeystoreTest` con el Keystore real (cifrar y descifrar,
  archivo alterado, pantalla bloqueada y caché sin archivos en claro). Correr en emulador con API 26 y 34:
  `./gradlew :feature:siniestros:connectedDebugAndroidTest`.

## Seguimiento (WEB-F06)

«Mis siniestros» en Inicio, junto a «Reportar siniestro»: los casos del cliente reportados desde cualquier
canal, con el mismo estado, la misma referencia y el mismo historial que la web (#198). Usa
`GET /siniestros`, `GET /siniestros/{id}` y la parte `id_requerimiento` de la carga de evidencia
(`bff-movil` 0.7.0, vigente 0.8.0).

```
Inicio ──▶ Mis siniestros ──┬── con casos ──▶ Tarjetas ──toque──▶ Detalle ◀┄┄ push (#204)
           home/siniestros  ├── sin casos ──▶ Estado vacío ──▶ Reportar (#169)
                            └── error ─────▶ Reintentar        home/siniestros/detalle/{id}
                                                                       │
             sin pendientes ◀────────────────┬─────────────────────────┤
          (o cerrado sin respuesta)  requerimiento pendiente     404 ──▶ Volver a mis siniestros
                                             ▼
                              Responder: un archivo de cámara, galería o documento
                                             │
             201 ──▶ consulta el detalle ──▶ atendido con su fecha (si la consulta falla: «Reintentar» solo consulta)
             409/404 del requerimiento ──▶ aviso y caso actualizado
             sin respuesta ──▶ concilia por `nombre_original` antes de reenviar
```

Las rutas tienen un segmento propio (`detalle`) para no chocar con `home/siniestros/reporte` ni con
`home/siniestros/borradores/{borrador}`. Del detalle se vuelve a la lista; si se llegó directo (como hará
el push), volver la abre.

| Pieza | Qué resuelve | Criterios |
|---|---|---|
| `ReglasSeguimiento.kt` | Siguiente paso con `estado` y `siguiente_paso` juntos (el contrato manda `esperar_evaluacion` también en los cerrados), los tres estados de un requerimiento, la insignia de cada estado y si termina el caso. JVM puro. | 4, 7, 15 |
| `MisSiniestrosViewModel` · `PantallaMisSiniestros` | Lista del más reciente al más antiguo, «Actualizar» y deslizar para actualizar, vacía, error y «Reportar siniestro» con el bloqueo de Inicio (MOB-F05-02). | 1, 2, 9, 11, 12, 14 |
| `DetalleSiniestroViewModel` · `PantallaDetalleSiniestro` | Encabezado, resumen, línea de tiempo (solo el último evento resaltado), requerimientos, no encontrado y errores. El `id` llega por la ruta y queda en el `SavedStateHandle`. | 2–8, 10–13, 15 |
| `SeccionesDetalle.kt` | Secciones del detalle y la hoja inferior para responder, que reutiliza `PasoEvidencia` (mismas reglas, compresión y permisos). | 3–5, 7, 8, 17 |
| `GestorEvidencia.responder` | Respuesta única con `id_requerimiento` (modo `unico`: elegir otro archivo reemplaza al anterior), conciliación de una carga incierta y resultado tipado para la pantalla. `subir` y el enviador de borradores siguen sin `id_requerimiento`. | 5, 6, 8 |

### Estados del caso

| Estado | Qué le dice al cliente | ¿Termina el caso? | Insignia |
|---|---|---|---|
| Reportado | Se recibió el reporte | No | Ámbar |
| En evaluación | Lo está revisando un ajustador | No | Ámbar |
| Requiere información | Falta algo que debe entregar | No | Ámbar |
| Aprobado | Se aprobó el caso | No | Verde |
| Rechazado | Se rechazó, con el motivo si lo hay | Sí | Rojo |
| Pagado | Se pagó la indemnización | Sí | Verde |

La insignia siempre lleva el texto y TalkBack lo lee («Estado: …»).

### Responder un requerimiento

- **Un archivo por respuesta**: el primero deja el requerimiento atendido y otro recibiría
  `409 requerimiento_ya_atendido`.
- Tras un `201` se vuelve a consultar el detalle (el `POST` responde la `Evidencia`, no el requerimiento).
  Si esa consulta falla, la pantalla dice que la respuesta se envió y «Reintentar» solo consulta.
- Sin red o sin respuesta, el adjunto queda en la hoja; el reintento primero busca su `nombre_original` en
  `GET /siniestros/{id}/evidencias` y, si llegó, no lo vuelve a cargar. Si no se puede consultar, no se
  reenvía nada. Un `503` no deja dudas y se reenvía directo, sin volver a elegir el archivo.
- Errores por `codigo`: `requerimiento_ya_atendido`, `requerimiento_no_encontrado` y `siniestro_cerrado`
  avisan (*snackbar*) y actualizan el caso; `siniestro_no_encontrado` muestra no encontrado; `413`/`422`
  usan el mensaje del paso de evidencia; un `401` va al flujo de sesión.

### Rotación, segundo plano y muerte del proceso

Los datos del caso viven en el ViewModel: la rotación no vuelve a consultar. Si Android cerró el proceso,
el `SavedStateHandle` conserva solo el `id`, el requerimiento que se respondía y el adjunto (en la caché);
la pantalla vuelve al mismo caso y lo consulta otra vez. Del siniestro no se guarda nada en disco.

### Pruebas del seguimiento

- **JVM**: `ReglasSeguimientoTest` (cada fila de la regla del siguiente paso, los tres estados de un
  requerimiento y el catálogo de estados), `MisSiniestrosViewModelTest` y `DetalleSiniestroViewModelTest`
  (lista, detalle, actualizar, respuesta, respuesta perdida sin carga duplicada, fallo solo de la consulta,
  error de red, una prueba por fila de la tabla de errores, muerte del proceso). `GestorEvidenciaTest` y
  `EnviadorBorradoresTest` comprueban que las cargas sin requerimiento no cambian; `EvidenciaApiTest`
  (`core/network`), la parte `id_requerimiento`, el detalle y `estado_actualizado_en` ausente o `null`.
- **Compose + Robolectric**: `PantallasSeguimientoTest` (lista con datos, vacía, bloqueada, error, detalle
  con requerimiento pendiente, atendido y cerrado sin respuesta, campos `null`, caso cerrado y no
  encontrado); en `app`, `SolventaAppTest` recorre Inicio → lista → detalle, «Reportar siniestro» desde la
  lista (abre el asistente, nunca el detalle) y la ruta del detalle abierta directamente.
- **Mock**: `MockSeguimientoTest` recorre los escenarios sembrados por el cliente real.

## Fuera de alcance

Servicio de reporte y rutas del BFF (#135, #168), E2E en emulador contra
*staging* (#172, y el del reporte sin conexión en #213), el asistente web (#132), la guía de usuario del
reporte sin conexión (#214), el envío con la app cerrada y sin sesión, una pantalla de detalle del
borrador y una clave de idempotencia para la evidencia. Del seguimiento: notificaciones push (MOB-F06,
#204), actualización automática, filtros y paginación, cambiar el estado desde la app, la web (#198) y su
E2E en emulador (#202).
