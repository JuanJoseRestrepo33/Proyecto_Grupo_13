# WEB-F04 · Consultar mis pólizas en la app

El cliente ve en la pestaña **Pólizas** las mismas pólizas que en la web, sabe cuáles lo protegen hoy y
entra al detalle de cada una para consultar coberturas, deducible y límites y abrir su PDF. Usa las rutas
de pólizas de [`contratos/bff-movil`](../../../../contratos/bff-movil/openapi.yaml) (entraron en 0.6.0):
una póliza emitida en la web aparece en la app sin hacer nada más. Esta parte es solo **con conexión**:
la billetera y la credencial sin conexión son de MOB-F03 (#134). En debug, el mock de `app/src/debug`
responde los escenarios descritos en el README de `mobile/android`.

## Flujo

```text
Barra inferior ──▶ Pólizas ──┬── con pólizas ──▶ Activas · Vencidas · Todas ──toque──▶ Detalle
                             ├── sin pólizas ──▶ «Cotizar un seguro» (la web)          │
                             └── error ───────▶ Reintentar                             │
                                      ┌─────────────────┬──────────────────────────────┤
                                con documento     sin documento                 no encontrada
                                      ▼                 ▼                              ▼
                               Descargar PDF     «no disponible»             Volver a mis pólizas
                                      │
                    ┌─────────────────┼────────────────────┬──────────────────┐
                    ▼                 ▼                    ▼                  ▼
              Visor del         Sin red o 5xx:     documento_no_disponible:   Sin visor:
               sistema           Reintentar        aviso y se oculta el botón    aviso
```

`SolventaApp` (en `app`) pone `RutaMisPolizas` en la pestaña Pólizas (`policies`) y `RutaDetallePoliza`
en `policies/detalle/{numero}`, anidada para que la barra inferior siga marcando Pólizas. Cada `Ruta…`
crea su ViewModel; `PantallaMisPolizas` y `PantallaDetallePoliza` no tienen estado propio y son las que
dibujan las pruebas. El número va codificado en la ruta (`Uri.encode`). Tocar Pólizas estando en el
detalle vuelve a la lista.

## Paquetes

| Paquete | Pieza | Qué resuelve | Criterios |
|---|---|---|---|
| `lista` | `MisPolizasViewModel` | Consulta `GET /polizas` y conserva el orden del BFF (de la más reciente a la más antigua). Pestaña elegida, actualizar y la política de refresco. Si una consulta falla, se queda la lista anterior con el aviso. | 1, 2, 9–11 |
| `lista` | `PantallaMisPolizas` | Pestañas **Activas** (`vigente`, la inicial), **Vencidas** (`vencida`) y **Todas**: una por iniciar solo aparece en Todas. Tarjetas con producto, número, estado, vigencia y prima. Toda la tarjeta abre el detalle. También el estado vacío con «Cotizar un seguro», la pestaña vacía, el error y el gesto para actualizar con el botón «Actualizar». | 1, 3, 7, 10, 14 |
| `detalle` | `DetallePolizaViewModel` | `GET /polizas/{numero}` y la descarga con su tabla de errores (abajo). Un 404 de otro cliente se trata igual que uno que no existe. | 4–6, 8, 9 |
| `detalle` | `PantallaDetallePoliza` | Encabezado, información general (vigencia, prima, emisión), financiera (valor asegurado y deducible), coberturas con su límite y documento. | 4–7, 14 |
| `detalle` | `DocumentoPoliza.kt` | `DocumentosPolizaAndroid` guarda el PDF en `cacheDir/polizas/` a través de un archivo parcial, así que una descarga cortada no deja un PDF a medias. `VisorPdfAndroid` lo abre con `ACTION_VIEW` y `FileProvider` (`ProveedorDocumentos`), con permiso de lectura temporal y sin permisos de almacenamiento. | 5 |
| `comun` | `ReglasPoliza.kt` · `Componentes.kt` | Pestañas, tono de la insignia y formatos de fecha y dinero, sin Compose. También la plantilla con «Actualizar», la insignia con texto y el nombre genérico del producto. | 7, 14 |

### Política de refresco

Cada vez que el cliente toca **Pólizas** en la barra inferior, `SolventaApp` sube un contador
(`rememberSaveable`) y se lo pasa a `RutaMisPolizas`. El ViewModel consulta otra vez si el contador
cambió, y mientras llega la respuesta sigue mostrando la lista anterior con el indicador. Rotar la
pantalla, volver del segundo plano o volver del detalle recomponen la pantalla con el mismo valor, así
que no consultan. `SolventaApp` conserva el estado de los destinos (`saveState`/`restoreState`): por eso
el refresco va al tocar la pestaña y no al recomponer.

Si Android cierra el proceso, la sesión (solo en memoria, MOB-F01) se pierde y la app vuelve a Ingreso
sin restaurar la lista ni el detalle (criterio 12). De la póliza solo queda en disco el PDF que el
cliente pidió, en la caché de la app.

### Descarga del documento

La descarga va por `GET /polizas/{numero}/documento` (`@Streaming`) con el mismo cliente HTTP de la
sesión, que renueva el token ante un 401. Nunca se usa una URL del almacén. Mientras descarga, el botón
muestra «Descargando…» y no se puede volver a tocar.

| Respuesta | Comportamiento |
|---|---|
| `404 documento_no_disponible` | Aviso «El documento de esta póliza no está disponible» y se oculta el botón |
| `404 poliza_no_encontrada` | Pantalla de póliza no encontrada, con regreso a la lista |
| `401` que no se pudo renovar | Flujo de sesión, como en el reporte de siniestros |
| Sin red, tiempo agotado o corte a mitad | Aviso con «Reintentar» sin salir del detalle |
| 5xx | Aviso con «Reintentar» |
| Sin app para abrir PDF | Aviso de que no hay un visor de PDF instalado |

### Datos que pueden faltar

Son las mismas reglas que la web (#189). Las pólizas de prueba de la semilla no traen todos los datos.

| Dato que falta | Lo que ve el cliente |
|---|---|
| `nombre_producto` | El nombre genérico: «Seguro de auto», «Seguro de hogar», «Seguro de viaje», «Seguro de dispositivos» |
| `prima_mensual` | «No disponible» («Prima: No disponible» en la tarjeta) |
| `deducible` o `valor_asegurado` | «No disponible» |
| `emitida_en` | La fila no aparece |
| `coberturas` vacías | «Esta póliza no tiene coberturas registradas.» |
| `documento_url` | No se ofrece la descarga y se explica que el documento no está disponible |

### Estados de la póliza

| Valor del contrato | Texto | Insignia |
|---|---|---|
| `vigente` | Vigente | Verde (`SolventaColors.success`) |
| `vencida` | Vencida | Gris |
| `por_iniciar` | Por iniciar | Índigo (`SolventaColors.secondary`) |

La insignia siempre lleva texto (P12) y TalkBack lee «Estado: …». Cada tarjeta se lee como un solo
control con todo su contenido.

### Cotizar desde el estado vacío

La app aún no tiene cotizador. «Cotizar un seguro» abre `WEB_URL` + `cotizacion` en el navegador.
`WEB_URL` es una propiedad de Gradle (`-PWEB_URL=https://…/`, HTTPS y terminada en `/`), igual que
`BFF_BASE_URL`. Sin ella apunta a un host `.invalid`. El APK contra staging la toma de la variable
`WEB_URL` del environment `staging`, si existe.

## Pruebas

| Prueba | Qué cubre |
|---|---|
| `MisPolizasViewModelTest` | Carga, filtro por pestaña, pestaña vacía, sin pólizas, sin red, 5xx, 401, actualizar sin duplicar, refresco al entrar (la recién emitida primero), sin consultar con la misma entrada, y la lista anterior que se conserva si falla |
| `DetallePolizaViewModelTest` | Detalle, no encontrada, 401, reintento, una prueba por fila de la tabla de la descarga, doble toque y sin documento |
| `ReglasPolizaTest` | Pestañas y tono de los tres estados, formatos de fecha y prima |
| `DocumentoPolizaTest` (Robolectric) | Archivo en la caché, descarga cortada, nombre de archivo, `FileProvider` con permiso temporal y sin visor |
| `PantallasPolizasTest` (Compose) | Lista con datos, pestaña vacía, sin pólizas, error, detalle completo, detalle con datos faltantes y sin documento, no encontrada, descargando y avisos. También TalkBack y 48 dp |
| `SolventaAppTest` (app) | La tarjeta abre `policies/detalle/{numero}` con Pólizas marcada, entrar de nuevo consulta, volver del detalle no, y el acceso directo a la ruta |
| `MockPolizasTest` · `MockSiniestrosTest` (app) | Escenarios del mock. Las pólizas del asistente de reporte conservan su número |
| `ReporteViewModelTest` (siniestros) | El asistente lista y reporta igual con el `PolizaResumen` ampliado |
| `NetworkClientTest` (core) | Campos nuevos con valor, en `null` o ausentes, número codificado en la ruta y PDF con `@Streaming` |
