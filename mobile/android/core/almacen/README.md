# core/almacen · Lo que la app guarda cifrado en el teléfono

Piezas comunes a los dos almacenes cifrados de la app. Salieron de `feature/siniestros/borrador`
(MOB-F05-02) cuando la billetera de pólizas (MOB-F03, #134) necesitó lo mismo; los borradores siguen
funcionando igual, con su llave y su carpeta.

| Pieza | Qué resuelve |
|---|---|
| `PuertoCifrado` · `CifradoAesGcm` | AES-256-GCM en flujo con un IV aleatorio por archivo. Formato: `versión · largo del IV · IV · texto cifrado y etiqueta de 128 bits`. GCM no entrega texto en claro sin verificar la etiqueta: un archivo alterado no se descifra. |
| `FuenteLlave` · `LlaveKeystore` | La llave AES-256 de cada almacén en el Android Keystore (por hardware cuando el equipo lo tiene). Descifrar nunca la crea. Alias: `solventa.borradores` y `solventa.billetera`. |
| `ArchivoIlegible` · `clasificar` | Por qué no se pudo leer: **permanente** (sin llave, llave invalidada, etiqueta que no coincide, encabezado alterado) o **temporal** (`KeyStoreException`, E/S). |
| `ArchivoCifrado` | Un archivo cifrado que se reemplaza entero, con escritura atómica (temporal, `fsync` y `rename`): un corte deja la versión anterior o la nueva, nunca una a medias. Lo usa la billetera. |
| `MonitorConectividad` · `MonitorConectividadAndroid` | Si hay una red con `NET_CAPABILITY_VALIDATED`. Un wifi sin internet o un portal cautivo no cuentan. La app crea una sola instancia por proceso. |

## Modelo de confianza

Las llaves no exigen autenticación ni `setUnlockedDeviceRequired`: los borradores se envían también con
la pantalla bloqueada y la billetera se abre sin pedir la huella cada vez. Protegen los datos el
aislamiento de la app, la llave ligada al Keystore y que los archivos viven en `noBackupFilesDir`, fuera
de las copias de seguridad. La misma política vale desde API 26.

## Pruebas

`ArchivoCifradoTest` (JVM, con una llave de la JCA): cifrado y lectura, sin texto en claro en el disco,
reemplazo sin temporales, corte a mitad de la escritura, archivo alterado, versión desconocida,
encabezado truncado, llave borrada (permanente) y error del Keystore (temporal). Con la llave real del
Keystore lo prueba `AlmacenKeystoreTest` de `feature/siniestros` en un emulador.
