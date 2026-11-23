# WEB-F04 · MOB-F03 · Mis pólizas en la app, con y sin conexión

El cliente ve en la pestaña **Pólizas** las mismas pólizas que en la web, sabe cuáles lo protegen hoy y
entra al detalle de cada una para consultar coberturas, deducible y límites y abrir su PDF. Usa las rutas
de pólizas de [`contratos/bff-movil`](../../../../contratos/bff-movil/openapi.yaml) (entraron en 0.6.0):
una póliza emitida en la web aparece en la app sin hacer nada más. Sin conexión, la misma pestaña muestra
la **billetera**: una copia cifrada de la última sincronización, con la **credencial digital** de cada
póliza vigente (MOB-F03, #134; ver [Billetera sin conexión](#mob-f03--billetera-y-credencial-sin-conexión)).
En debug, el mock de `app/src/debug` responde los escenarios descritos en el README de `mobile/android`.

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
sin restaurar la lista ni el detalle (criterio 12): la navegación sí se restaura, pero `SolventaRaiz`
manda a Ingreso las rutas que necesitan sesión (`SoloSi`). En disco quedan el PDF que el cliente pidió, en
la caché de la app, y la billetera cifrada (MOB-F03), que se puede abrir sin sesión desde Ingreso.

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

## MOB-F03 · Billetera y credencial sin conexión

El cliente puede mostrar su póliza aunque no tenga señal: sin conexión ve sus pólizas y abre la credencial
de una póliza vigente con los datos de la última sincronización, sabe de cuándo son esos datos, y cuando
vuelve la conexión se actualizan solos. No cambia el contrato `bff-movil`: usa `GET /polizas` y
`GET /polizas/{numero}`. La **entrega 1** de #134 cubre la app abierta con la sesión viva; la
**entrega 2**, la [entrada sin conexión desde Ingreso](#entrada-sin-conexión-desde-ingreso-entrega-2) cuando
Android cerró el proceso.

```text
Pólizas ──┬── con red ──▶ Lista (#190) ──▶ sincroniza ──┬── ok ──▶ «Disponible sin conexión · sincronizada hoy 08:14»
          │                                              └── falla un detalle ──▶ queda la billetera anterior
          ├── sin red ──┬── billetera ──▶ lista guardada + «Sin conexión · datos del 18 nov., 08:14»
          │             │                     └── vuelve la red ──▶ sincroniza sola (o «No pudimos actualizar»)
          │             └── sin billetera ──▶ «Necesitas conexión una vez…» + Reintentar
          └── tiempo agotado ──▶ lista guardada + «No pudimos actualizar» + Reintentar

Tarjeta vigente ──▶ Ver credencial ──▶ policies/credencial/{numero}
Tarjeta ──▶ detalle guardado (PDF inactivo) · sin detalle guardado ──▶ «Necesitas conexión para ver este detalle»
```

### Paquetes

| Paquete | Pieza | Qué resuelve |
|---|---|---|
| `billetera` | `BilleteraGuardada` | `cliente_id`, `nombre_completo`, la lista en el orden del BFF (puede ser vacía), el detalle de cada póliza `vigente` o `por_iniciar` (las vencidas solo el resumen) y `sincronizada_en`. |
| `billetera` | `AlmacenBilletera` | Una sola billetera por teléfono en `noBackupFilesDir/billetera/billetera.bin`, cifrada con la llave `solventa.billetera` y escrita de forma atómica (`ArchivoCifrado` de `core/almacen`). Guardar, leer y borrar pasan por el mismo `Mutex`. Un archivo ilegible o de otro dueño se borra y se trata como «sin billetera». |
| `billetera` | `SincronizadorBilletera` | Sincroniza **una a la vez** y **todo o nada**; la generación de cuenta y el borrado al cerrar sesión (abajo). Vive en `Proceso` (alcance de aplicación), como el envío de borradores. |
| `comun` | `ReglasPoliza.kt` · `ComponentesBilletera.kt` | La vigencia sin conexión (§5), los formatos de la fecha de sincronización, la franja fija, la línea «Disponible sin conexión» y el texto junto a un control que necesita conexión. |
| `lista` · `detalle` | `MisPolizasViewModel` · `DetallePolizaViewModel` | La tabla de abajo, la reconexión y «Ver credencial». |
| `credencial` | `CredencialViewModel` · `PantallaCredencial` | La credencial digital, que siempre lee la billetera. |

No se guarda el PDF ni ningún token: el token de refresco sigue en `AlmacenToken` (MOB-F01).

### Sincronización

- **Cuándo:** cada vez que la lista llega bien con sesión (primera carga, entrada a la pestaña,
  «Actualizar», deslizar) y al volver la conexión. Después de la lista se piden, de a uno, los detalles de
  las `vigente` y `por_iniciar`.
- **Una a la vez:** un segundo disparo espera la sincronización en curso y recibe su resultado. Solo
  si es de la misma cuenta: una de la cuenta anterior va a descartar lo que traiga, así que la cuenta
  nueva lanza la suya.
- **Todo o nada:** si la lista y todos esos detalles llegan bien, se reemplaza la billetera y
  `sincronizada_en` pasa a ser la hora del final. Si algún detalle falla (un 5xx, sin red o un 404 de una
  póliza que desapareció entre las dos llamadas), la lista en pantalla se actualiza igual, como en #190,
  pero la billetera guardada queda como estaba con su fecha.
- **El detalle manda:** si el detalle y el resumen de la misma póliza difieren, el resumen guardado se
  toma del detalle, que se pidió después. Lista y detalles son llamadas separadas: la billetera es una
  foto aproximada, no una instantánea transaccional del servidor.
- **Al perder y al volver la conexión:** con la pestaña Pólizas, un detalle o una credencial en
  pantalla, cuando `MonitorConectividad.conectado` pasa a `false` la lista y el detalle pasan a lo
  guardado con «Sin conexión · datos del …», y cuando pasa de `false` a `true` con una sesión vigente se
  sincroniza sola. Cada pantalla tiene su `VigiaConexion`, que recuerda la última red que vio aunque
  salga de composición: si la red volvió mientras el cliente estaba en otra pantalla, al regresar se
  sincroniza. Un cambio que llega con una consulta en curso se atiende cuando esa consulta termina.
  Mientras tanto se ve lo guardado con «Conexión recuperada · actualizando tus pólizas…». Si falla
  (una red validada no garantiza llegar al BFF), queda la billetera con «No pudimos actualizar» y
  «Reintentar».

### Cuentas, cierre y carreras

- Una **generación** (contador atómico) sube en `ObservadorCuenta.antes`, antes de que cambie la sesión,
  y al cerrar sesión. Cada sincronización anota el cliente y la generación con que empezó y, **dentro del
  candado** y justo antes de reemplazar el archivo, comprueba que no cambiaron; si cambiaron, descarta lo
  que trae. Lo mismo vale para lo que se publica en memoria: una billetera escrita o leída cuando la cuenta
  ya cambió no llega a ninguna pantalla, y una sincronización no pide más detalles si la cuenta cambió.
- La sesión cambia bajo el candado de `GestorSesion`, no bajo este, así que queda una ventana entre la
  comprobación y la escritura. La cierran dos reglas: el borrado por cambio de cuenta corre en
  `ObservadorCuenta.despues` con el mismo candado (siempre llega después de una escritura que se coló), y
  **leer** también comprueba que el dueño del archivo sea el cliente de la sesión.
- **Otra cuenta:** al iniciar sesión, una billetera de otro `cliente_id` se borra antes de mostrar nada.
  Se compara con el dueño guardado en el archivo, no con la sesión anterior, que tras cerrar el proceso
  ya no existe.
- **Cerrar sesión** (`cerrarSesion` en `SolventaRaiz`) borra la billetera, y el diálogo de cierre lo dice:
  «Tus pólizas dejarán de estar disponibles sin conexión en este teléfono». El vencimiento de la sesión
  (`GestorSesion.alVencer`) y el cierre del proceso **no** la borran: son justo los casos que cubre. Como
  `ObservadorCuenta` recibe `null` en los dos, el borrado va en la acción de cerrar sesión.

### Mis pólizas sin conexión

`llamarBff` devuelve `SinConexion` ante cualquier `IOException`, incluido un tiempo agotado; la pantalla
distingue los dos casos con `MonitorConectividad`:

| Respuesta de `GET /polizas` | Billetera guardada | Lo que ve el cliente |
|---|---|---|
| `SinConexion` y el monitor dice sin red | Sí | La billetera con «Sin conexión · datos del 18 nov., 08:14» |
| `SinConexion` y el monitor dice con red (tiempo agotado) | Sí | La billetera con «No pudimos actualizar · datos del …» y «Reintentar» |
| `SinConexion` | No | «Necesitas conexión una vez para guardar tus pólizas en este teléfono» y «Reintentar». Si ya había una lista del BFF en pantalla, se queda con el aviso de #190 |
| 5xx o respuesta ilegible | — | Lo de #190: la lista anterior, o el error, con «Reintentar». No se muestra la billetera |
| `401` que no se pudo renovar | — | El flujo de sesión de #190 |

- Con conexión, bajo el título: «Disponible sin conexión · sincronizada hoy 08:14», o «Aún no disponible
  sin conexión» si nunca hubo una sincronización completa.
- Sin conexión se conservan las pestañas y el orden guardado. Una billetera sin pólizas muestra el estado
  vacío con la fecha, y «Cotizar un seguro» queda inactivo con «Necesitas conexión para cotizar».
- «Ver credencial» va en cada tarjeta que se muestra vigente, como un control aparte de la tarjeta.
- Sin conexión, tocar una tarjeta abre el detalle guardado si existe, aunque la póliza haya vencido por
  fecha después de sincronizar (sin «Ver credencial»); si no hay detalle guardado (una que ya llegó
  vencida del BFF), «Necesitas conexión para ver este detalle». «Descargar PDF» queda inactivo con
  «Necesitas conexión para descargar el documento».

### Vigencia según la última sincronización

Con conexión manda el `estado` del BFF. Sin conexión, la app no puede saber si la aseguradora canceló o
modificó la póliza, pero no sigue mostrando como vigente una póliza cuyas fechas ya pasaron
(`estadoSinConexion`, con `inicio`, `fin` y la fecha del teléfono):

| Fecha del teléfono | Estado que se muestra |
|---|---|
| Antes de `inicio` | Por iniciar |
| Entre `inicio` y `fin`, incluidos | Vigente si se guardó `vigente` o `por_iniciar`; Vencida si se guardó `vencida` |
| Después de `fin` | Vencida |

Solo ofrece credencial una póliza que se muestra Vigente. Sin conexión la franja lleva siempre la fecha
de sincronización, y la credencial dice «Vigente según datos del 18 nov. 2026».

### Credencial digital

Ruta `policies/credencial/{numero}`, anidada bajo Pólizas. Se abre desde la tarjeta o desde el detalle de
una póliza vigente, con o sin conexión, y **siempre lee la billetera**. Si la póliza todavía no está
guardada, con conexión sincroniza primero; sin red, explica que la necesita. Lleva titular
(`nombre_completo` de la sesión que sincronizó), producto, número (seleccionable para copiarlo),
vigencia, estado con la fecha, coberturas con su límite, valor asegurado y deducible («No disponible» si
faltan) y el pie «Datos sincronizados el 18 nov. 2026, 08:14». Sin conexión se suma la franja
«Sin conexión».

Es una **copia informativa que se puede contrastar con la aseguradora** por el número de póliza, no una
prueba criptográfica de cobertura: una firma o un QR verificable en línea necesitarían una ruta nueva en
el BFF. Falta confirmar con el PO que esto cumple «completa y verificable» (criterio 3 de #139). No lleva
`FLAG_SECURE`: el cliente puede necesitar una captura para enviarla.

### Entrada sin conexión desde Ingreso (entrega 2)

Si Android cerró el proceso, la app vuelve a Ingreso, y el ingreso con huella necesita red
(`POST /sesiones/renovacion`): sin señal, el cliente no llegaría a su billetera. Por eso:

```text
Ingreso sin red ──▶ «Ver mis pólizas sin conexión» ──▶ desbloqueo ──┬── ok ──▶ billetera sin sesión (solo lectura)
                                                                    │            └── vuelve la red ──▶ «Ya tienes conexión · Ingresa para actualizar»
                                                                    └── cancelado ──▶ Ingreso
```

- Ingreso muestra «No tienes conexión.» y «Ver mis pólizas sin conexión» solo si no hay una red
  validada, hay una billetera legible y el teléfono tiene bloqueo de pantalla (`ofertaSinConexion` en
  `app`). Sin bloqueo de pantalla la opción no aparece: sin un desbloqueo local, cualquiera con el
  teléfono vería las pólizas.
- Antes de mostrar nada se pide el desbloqueo del teléfono con `BiometricPrompt`
  (`DesbloqueoTelefono` de `feature/onboarding`): `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`, es decir,
  huella, rostro, PIN o patrón. En Android 9 y 10 la librería no admite esa combinación y se usa
  `BIOMETRIC_WEAK or DEVICE_CREDENTIAL`.
- Superado, `SincronizadorBilletera.abrirSinSesion` desbloquea la entrada y la ruta
  `sin-sesion/polizas` abre `RutaBilleteraSinSesion` (paquete `sinsesion`): Mis pólizas, detalle guardado
  y credencial, con las mismas pantallas en modo `soloLectura` y sin la barra inferior. Su ViewModel no
  recibe el cliente del BFF: sin sesión no hay nada que pedir ni sincronizar.
- Arriba va la franja «Sin conexión · datos del …» con «Ingresar». Cuando vuelve la conexión, la franja
  cambia a «Ya tienes conexión · Ingresa para actualizar», con «Ingresar». Al ingresar desde aquí se
  sincroniza enseguida (en `ObservadorCuenta.despues`), como en §2.
- El desbloqueo vive en memoria. Lo revocan volver a Ingreso, cualquier cambio de cuenta y el cierre de
  sesión; un proceso nuevo empieza sin él. Si Android restaura la ruta sin el desbloqueo, se vuelve a
  Ingreso sin mostrar nada.
- Sin sesión, la billetera que se lee es la del archivo, que es la del último cliente que sincronizó:
  cerrar sesión la borra, y al entrar otra cuenta se borra la de un dueño distinto.

### Modelo de confianza

Como la de los borradores, la llave `solventa.billetera` no exige autenticación: la app la usa con la
sesión abierta sin pedir la huella cada vez. Protegen los datos el aislamiento de la app, la llave ligada
al Keystore y la carpeta sin copia de seguridad (ver [`core/almacen`](../../core/almacen/README.md)). La
entrada sin sesión se protege con el desbloqueo del teléfono, así que quien conozca el PIN del teléfono
puede ver la billetera. Se acepta: es lo mismo que podría ver con la app abierta. En Android 9 y 10 el
desbloqueo admite también la biometría débil del equipo.

### Fuera de alcance

Firma o QR verificable en línea; saber sin conexión si la póliza se canceló o modificó; un reloj del
teléfono equivocado; el PDF sin conexión; varias billeteras en un mismo teléfono; sincronizar con la app
cerrada (`WorkManager`); las acciones de la credencial del mockup (modificar, renovar, cancelar, reportar
siniestro, historial de pagos) y la credencial en la web.

## Pruebas

| Prueba | Qué cubre |
|---|---|
| `MisPolizasViewModelTest` | Carga, filtro por pestaña, pestaña vacía, sin pólizas, sin red, 5xx, 401, actualizar sin duplicar, refresco al entrar (la recién emitida primero), sin consultar con la misma entrada, y la lista anterior que se conserva si falla |
| `DetallePolizaViewModelTest` | Detalle, no encontrada, 401, reintento, una prueba por fila de la tabla de la descarga, doble toque y sin documento |
| `ReglasPolizaTest` | Pestañas y tono de los tres estados, formatos de fecha y prima |
| `DocumentoPolizaTest` (Robolectric) | Archivo en la caché, descarga cortada, nombre de archivo, `FileProvider` con permiso temporal y sin visor |
| `PantallasPolizasTest` (Compose) | Lista con datos, pestaña vacía, sin pólizas, error, detalle completo, detalle con datos faltantes y sin documento, no encontrada, descargando y avisos. También TalkBack y 48 dp |
| `SolventaAppTest` (app) | La tarjeta abre `policies/detalle/{numero}` con Pólizas marcada, entrar de nuevo consulta, volver del detalle no, y el acceso directo a la ruta. «Ver credencial» abre `policies/credencial/{numero}` con Pólizas marcada, desde la lista y desde el detalle |
| `SolventaRaizTest` (app) | Cerrar sesión avisa en el diálogo y borra la billetera; el vencimiento de la sesión no la borra |
| `AlmacenBilleteraTest` | Guardar y leer sin texto en claro en el disco, escritura atómica ante un corte, archivo alterado, truncado o sin llave, un fallo del Keystore (`ProviderException`) al escribir o leer, billetera vacía, una sola billetera y la de otro dueño |
| `SincronizadorBilleteraTest` | Todo o nada (5xx, sin red y 404 de un detalle), una a la vez, el detalle manda sobre el resumen, la lista que ya trajo la pantalla, 401, sin sesión; carreras: otra cuenta durante una sincronización, cierre durante una sincronización, escritura que se coló, otra cuenta justo después de escribir o mientras se lee, la sincronización de la cuenta anterior que no se comparte y dueño distinto tras reiniciar el proceso; vencimiento que no borra y cierre que sí; la vigía: pérdida, vuelta con y sin sesión y el cambio que llegó mientras la pantalla no vigilaba |
| `MisPolizasViewModelTest` (MOB-F03) | Una prueba por fila de la tabla de sin conexión, la vencida por fecha sin credencial, la red que se pierde con la lista en pantalla (con y sin billetera), la reconexión que actualiza la fecha, la que falla, la que llega con una consulta en curso o mientras el cliente estaba en otra pantalla y el indicador mientras sincroniza |
| `DetallePolizaViewModelTest` (MOB-F03) | Detalle guardado con el PDF inactivo, tiempo agotado, vencida por fecha, detalle no guardado, la red que se pierde con el detalle en pantalla, un 5xx que quita la franja, la reconexión que vuelve a consultar o falla y «Reintentar» que vuelve a sincronizar |
| `CredencialViewModelTest` | Con y sin conexión lee la billetera, sincroniza primero si falta, necesita conexión, no vigente, por iniciar que ya empezó, no guardada y la reconexión, también la que falla con «No pudimos actualizar» y «Reintentar» |
| `ReglasPolizaTest` (MOB-F03) | Una prueba por fila de la vigencia sin conexión, con los bordes `inicio` y `fin`, y los formatos de la fecha de sincronización |
| `PantallasBilleteraTest` (Compose) | Con conexión, sin conexión, tiempo agotado, sincronizando, sin billetera, billetera vacía, vencida por fecha, detalle sin conexión y no guardado, credencial con y sin conexión, con datos faltantes y sin credencial. También TalkBack y 48 dp |
| `SincronizadorBilleteraTest` (entrega 2) | Sin desbloqueo no se lee, con sesión no se ofrece ni se abre, sin billetera o tras cerrar sesión no se ofrece, volver a Ingreso o un cambio de cuenta revocan el desbloqueo, ingresar desde la billetera sin sesión sincroniza y un ingreso normal no |
| `BilleteraSinSesionViewModelTest` | Lista, detalle (y el no guardado) y credencial desde la billetera sin llamar al BFF, la vencida por fecha sin credencial, la vuelta de la red sin sincronizar y que sin desbloqueo no muestra nada |
| `PantallasBilleteraTest` (entrega 2) | Billetera sin sesión sin «Actualizar», con una sola franja e «Ingresar» arriba, y la franja «Ya tienes conexión» con su botón de 48 dp |
| `SolventaRaizTest` (app, entrega 2) | Ingreso sin red ofrece la entrada y, tras el desbloqueo, muestra la billetera sin barra inferior; con la red de vuelta, «Ingresar»; sin bloqueo de pantalla no se ofrece; desbloqueo cancelado; tras cerrar sesión no se ofrece; la navegación restaurada sin sesión, o sin el desbloqueo, vuelve a Ingreso; volver a Ingreso pide otra vez el desbloqueo |
| `MockPolizasTest` · `MockSiniestrosTest` (app) | Escenarios del mock. Las pólizas del asistente de reporte conservan su número |
| `ReporteViewModelTest` (siniestros) | El asistente lista y reporta igual con el `PolizaResumen` ampliado |
| `NetworkClientTest` (core) | Campos nuevos con valor, en `null` o ausentes, número codificado en la ruta y PDF con `@Streaming` |
