# WEB-F05 · Reporte de siniestros en la app

Asistente para que el cliente asegurado reporte un siniestro desde Android y termine con el número de
referencia y el siguiente paso de su caso. Usa las mismas rutas, la misma referencia y la misma clave de
idempotencia que la web ([`contratos/bff-movil`](../../../../contratos/bff-movil/openapi.yaml) 0.3.0):
un siniestro reportado en la app se ve igual en la web, con `canal: movil`. En debug, el mock de
`app/src/debug` responde los escenarios descritos en el README de `mobile/android`.

## Flujo

```
Inicio ──Reportar siniestro──▶ 1 · Póliza ──▶ 2 · Información ──▶ 3 · Revisión ──Enviar──▶ Confirmación
                                  │                                 │  ▲                 (referencia, estado,
                                  │ sin pólizas                     │  └─ Cambiar         siguiente paso)
                                  ▼                                 │
                        «Necesitas una póliza»     404 ──▶ paso 1 (lista refrescada)
                                                   422 ──▶ paso 2 (error en su campo)
                                                   409 vigencia ──▶ aviso con las fechas de la póliza
```

La web tiene tres pasos (información, evidencia y revisión); en la app la póliza va en un paso propio
porque la lista no cabe cómoda junto al formulario. El paso de evidencia de #137 (MOB-F05) se inserta
entre información y revisión: basta con agregarlo a `PasoReporte` y su pantalla en `PantallaReporte`.

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

### Rotación y segundo plano

Lo ingresado, el paso, la clave, el envío pendiente y la confirmación se guardan en el
`SavedStateHandle`: sobreviven a la rotación y a la muerte del proceso en segundo plano (criterio 6).
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
- **App** (`app/src/testDebug`): escenarios del mock (`MockSiniestrosTest`) y el reporte de punta a
  punta desde el ingreso (`SolventaRaizTest`).

## Fuera de alcance

Servicio de reporte y rutas del BFF (#135, #168), paso de evidencia (#137), reporte sin conexión con
borrador cifrado (MOB-F05-02), «Mis siniestros» (WEB-F06), E2E en emulador contra *staging* (#172) y el
asistente web (#132).
