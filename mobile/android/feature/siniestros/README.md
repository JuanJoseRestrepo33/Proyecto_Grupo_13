# WEB-F05 y MOB-F05 · Reporte de siniestros y evidencia en la app

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
- `409 clave_idempotencia_en_uso`: se genera una clave nueva y se pide confirmar el envío otra vez.
- Los rechazos con código (`404`, `409 evento_fuera_de_vigencia`, `422`) son definitivos: el núcleo
  valida antes de registrar, así que se pueden corregir los datos y reenviar.

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

## Fuera de alcance

Servicio de reporte y rutas del BFF (#135, #168), reporte sin conexión con borrador cifrado
(MOB-F05-02), «Mis siniestros» (WEB-F06), E2E en emulador contra *staging* (#172) y el
asistente web (#132).
