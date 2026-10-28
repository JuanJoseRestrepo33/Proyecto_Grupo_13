# Solventa Android · ARQ-01 · Base móvil y pipeline · MOB-F01 · Onboarding

Aplicación nativa Kotlin/Jetpack Compose. Los mockups navegables del canal móvil están en `mobile-docs/`;
la raíz del proyecto Gradle es exclusivamente `mobile/android/`.

## Requisitos y comandos

JDK **17**, Android SDK con plataforma **35** y Build Tools **34.0.0**, y Android Studio
compatible con AGP 8.7.3. Gradle **8.9** se descarga mediante el wrapper incluido;
su distribución se valida con SHA-256. No se requiere Gradle instalado globalmente.

El resolvedor Foojay 0.8.0 descarga el toolchain 17 si Gradle se ejecuta con JDK 21.
Abrir esta carpeta en Android Studio y seleccionar JDK 17 para Gradle. Configurar
`ANDROID_HOME` o `local.properties` (ignorado) con `sdk.dir=/ruta/al/sdk`.
Desde esta carpeta:

```bash
./gradlew detekt lintDebug
./gradlew :core:network:test :core:designsystem:testDebugUnitTest :feature:onboarding:testDebugUnitTest :app:testDebugUnitTest
./gradlew :koverXmlReport :koverVerify
./gradlew :koverHtmlReport
./gradlew :app:koverXmlReportDebug :core:designsystem:koverXmlReportDebug
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

Kover 0.9.1 agrega `app`, `core/network`, `core/designsystem` y `feature/onboarding` en la raíz.
`:koverVerify` falla si la cobertura total de líneas es menor al **70%**; XML:
`build/reports/kover/report.xml`, HTML: `build/reports/kover/html/index.html`.
El total incluye las variantes debug y release de Android y el módulo JVM; las tareas
con sufijo `Debug` generan reportes individuales de debug. Kover ejecuta automáticamente
las pruebas requeridas. Las pruebas Compose pertenecen a `src/testDebug` para usar el
host de `ui-test-manifest`, que solo se incluye mediante `debugImplementation`. Solo se excluyen BuildConfig, R, ComposableSingletons generados y
MainActivity; no se excluyen funciones Compose ni lógica propia. No hay previews en esta base.
Las pruebas Compose corren en JVM con Robolectric 4.14.1, recursos Android y SDK simulado 34;
no requieren emulador, pero sí JDK 17 y el Android SDK para compilar.

En Windows usar `gradlew.bat`. `installDebug` requiere un emulador/dispositivo API 26+
conectado; también se puede ejecutar `app` desde Android Studio. APK:
`app/build/outputs/apk/debug/app-debug.apk`.

## Estructura y decisiones

- `app`: Activity, composición de dependencias, navegación de entrada (`SolventaRaiz`: ingreso,
  registro, OTP, identidad, oferta de biometría) y la navegación principal con cuatro placeholders.
  `MainActivity` es una `FragmentActivity` porque `BiometricPrompt` la requiere; el tema hereda de
  AppCompat porque el diálogo de huella de API 26–27 usa diálogos AppCompat.
- `core/network`: módulo Kotlin/JVM; Retrofit, OkHttp y kotlinx.serialization,
  sin dependencia de Android. API suspend, timeouts explícitos y sin logging de cuerpos.
- `core/designsystem`: biblioteca Compose con colores, escala tipográfica, radios y
  espaciado del **Design System v2 PDF**, que tiene prioridad sobre el JSON v1.
- `feature/onboarding`: **MOB-F01**. Registro, OTP, resultado KYC e ingreso con contraseña o
  biometría; ver [su README](feature/onboarding/README.md). Las próximas features se agregan como
  módulos `feature/<nombre>` en `settings.gradle.kts`.

Dependencias: app → core y features; features → core. Core no depende de features ni de app.
Versiones centralizadas en `gradle/libs.versions.toml`; minSdk 26, compile/targetSdk 35,
bytecode y toolchain JDK 17. Detekt analiza Kotlin y scripts Gradle; Android lint falla
ante errores o advertencias, sin baseline ni supresiones generales.

P01 de `../../docs/patrones-ui-ux.md` exige cuatro destinos pero no enumera sus nombres.
Se conservan **Inicio, Pólizas, Avisos, Perfil**, según los mockups que referencia
(`../../mobile-docs/index.html`) y el sistema de navegación. Navigation Compose guarda/restaura estado
por destino, evita duplicados al reseleccionar y permite volver a Inicio con Atrás.
El NavController restaura su estado tras recreación de Activity. Las pruebas JVM cubren
resolución de rutas e interacción con los cuatro destinos, su pantalla, selección y reselección;
la interacción de Atrás y recreación necesita dispositivo.
Todos los ítems inferiores tienen área mínima de **48 × 48 dp**, con texto accesible.
Los textos están externalizados en `app/src/main/res/values/strings.xml`.

Tema claro fijo para esta base: primary de marca `#1E1B4B`, accent `#7C3AED`, secondary
`#6366F1`, surface `#F3F2FF`; estados `#10B981`, `#F59E0B`, `#EF4444`.
Se usa sans del sistema como equivalente permitido por v2, sin descargar fuentes en runtime.
Escalas v2 (tamaño/alto en sp) y espaciados 4/8/16/24/32/48 dp. El texto sobre error
usa negro para mejorar contraste; no se usa color como único indicador de selección.

## BFF móvil: modo mock y modo real

La app habla con el router móvil de `solventa-bff` (`bff/app/movil/`), con el prefijo
**`/api/v1/movil/`**. Dos propiedades Gradle de `app` deciden a dónde van las llamadas:

| Propiedad | Por defecto | Efecto |
|---|---|---|
| `BFF_MOCK` | `true` | En debug, `true` instala `DebugMockInterceptor` y nada sale a Internet; `false` habla con el BFF de `BFF_BASE_URL`. Release nunca usa el mock |
| `BFF_BASE_URL` | `https://mobile-bff.invalid/api/v1/movil/` | Raíz del router móvil del ambiente; genera `BuildConfig.BASE_URL` |

```bash
./gradlew :app:assembleDebug                                    # mock (lo que corre el CI)
./gradlew :app:installDebug -PBFF_MOCK=false \
  -PBFF_BASE_URL="$(gcloud run services describe solventa-bff --region=<GCP_REGION> --format='value(status.url)')/api/v1/movil/"
```

`BFF_BASE_URL` debe ser HTTPS (HTTP solo hacia el BFF simulado en la máquina local, ver abajo), terminar
en `/` y no contener credenciales, query ni fragmento. El
valor por defecto es deliberadamente no resoluble (**no es un backend desplegado**): con
`BFF_MOCK=false` el build falla si el host sigue siendo `.invalid` o si la ruta no termina en
`/api/v1/movil/`. La variable de GitHub `BFF_BASE_URL` (ver `docs/devops.md`) guarda la del ambiente.
No guardar tokens/secretos en propiedades ni BuildConfig.

**Requisitos del modo real contra *staging*** (fuera del alcance de la app, #155):

- La llave de firma de tokens montada en `solventa-bff` y en `solventa-nucleo`; sin ella el BFF no valida tokens.
- `IDENTIDAD_OTP_FIJO` definido en el núcleo: `CanalDeCodigosSimulado` no envía el código al correo.
- Una sola instancia del núcleo: `identidad` guarda las cuentas en memoria, así que cada despliegue las
  borra y, con varias instancias, el ingreso falla de forma intermitente.

### BFF simulado con Mockoon (registro, OTP y sesión)

`mockoon/bff-movil.json` es un ambiente de [Mockoon](https://mockoon.com) 9.9 que implementa, con
estado, las rutas de WEB-F13 del contrato: registro, verificación y reenvío del OTP, ingreso, renovación
con rotación del token de refresco, `GET /sesion` y la verificación de identidad. Sirve para probar el
**modo real** de la app (cliente HTTP, `Authenticator`, errores) sin depender de *staging*. Se edita
con Mockoon Desktop (*Open environment*); `mockoon/mockoon.sh` lo arranca con `mockoon-cli` vía `npx`
(requiere Node 18+):

```bash
mobile/android/mockoon/mockoon.sh                     # http://localhost:3001/api/v1/movil/
mobile/android/mockoon/mockoon.sh prueba              # tiempos cortos + prueba de humo (32 verificaciones)
./gradlew :app:installDebug -PBFF_MOCK=false -PBFF_BASE_URL=http://10.0.2.2:3001/api/v1/movil/
```

El emulador ve la máquina como `10.0.2.2`; en un dispositivo físico, `adb reverse tcp:3001 tcp:3001` y
`http://localhost:3001/...`. HTTP solo se admite hacia esos hosts y solo en debug
(`app/src/debug/res/xml/red_debug.xml`); cualquier otro host exige HTTPS.

| Escenario | Cómo provocarlo |
|---|---|
| Cliente ya verificado | `ana@ejemplo.co` / `Solventa2026` |
| Código OTP | `123456`, o `MOCKOON_OTP_FIJO=aleatorio` y leerlo en el buzón: `GET /api/v1/movil/__pruebas/buzon?correo=...` (solo existe en el mock) |
| OTP incorrecto, bloqueo y vencimiento | 3 intentos (`otp_invalido` → `otp_bloqueado`); vence a los `MOCKOON_VIGENCIA_OTP` s (600) |
| Reenviar OTP | 429 con `Retry-After` antes de `MOCKOON_ESPERA_REENVIO` s (30); el reenvío devuelve los intentos |
| Resultado KYC | Último dígito del documento: 0–6 aprobada, 7 rechazada (`datos_no_coinciden`), 8 en revisión, 9 en proceso → aprobada en la siguiente consulta |
| Token de acceso vencido (renovación automática) | `MOCKOON_TTL_ACCESO=30` y esperar; por defecto vence a los 900 s |
| Token de refresco reutilizado | Cada renovación invalida el anterior: reutilizarlo da 401 `token_refresco_invalido` |
| Correo o documento duplicado | Registrar `ana@ejemplo.co` o el documento `1020304050` |

El estado vive en el *data bucket* `estado` y se reinicia al arrancar. `ContraMockoonTest`
(`core/network`) prueba el cliente real contra este ambiente: solo corre con `MOCKOON_URL` definida, como
hace el CI tras levantar Mockoon con un token de 2 s. Si cambia el contrato de estas rutas, actualizar el
ambiente y `mockoon/probar_mockoon.py` en el mismo PR.

### Sesión: renovación ante un 401

`MainActivity` crea el cliente con `BffProvider.create(sesion)`, donde `GestorSesion` es la `SesionBff`
del proceso. `NetworkClient` instala un `Authenticator` de OkHttp (`RenovadorSesion`) que, ante un 401
de una **ruta protegida** (las que llevan `Authorization`; registro e ingreso no cuentan):

1. Renueva **una sola vez** con el `token_refresco` (`POST sesiones/renovacion`) y repite la llamada con
   el token nuevo. Solo hay una renovación en curso: las llamadas que fallaron con el mismo token
   esperan y reutilizan el resultado. La llamada de renovación no pasa por el autenticador.
2. Con la renovación exitosa, `GestorSesion` guarda la sesión y rota el token cifrado de la huella. Si
   `AlmacenToken.guardar` devuelve `false`, la huella se desactiva e Inicio lo avisa.
3. Si el BFF rechaza el token de refresco (401 `token_refresco_invalido`, p. ej. porque ya se usó), se
   limpia la sesión, se borra el token de la huella y la navegación vuelve a Ingreso con la pila vacía,
   que pide la contraseña. Si la renovación falla por otra causa (5xx, cuerpo ilegible o sin red), la
   sesión también vence, pero se conserva el token y el Ingreso ofrece la huella. Las llamadas que
   esperaban esa renovación no la repiten.
4. Si la llamada repetida vuelve a dar 401, no se renueva otra vez: la sesión vence sin borrar el token
   de la huella y la app vuelve a Ingreso, en cualquier pantalla protegida.

Las escrituras de `GestorSesion.sesion` pasan por un candado: la renovación corre en hilos de OkHttp y una
copia vieja escrita desde una pantalla no puede restaurar un token de refresco ya consumido. El aviso de
huella desactivada queda en Inicio hasta que el cliente toca «Entendido».

`llamarBff` traduce una respuesta que no cumple el contrato (`SerializationException`) a una `Falla` con
`http = HTTP_RESPUESTA_ILEGIBLE` y sin código: la pantalla muestra `error_generico` y la app no se cierra.
En modo mock el `DebugMockInterceptor` responde antes que el autenticador, así que la renovación
automática solo actúa contra un BFF real o el de Mockoon; sus pruebas usan MockWebServer
(`RenovacionSesionTest`) y Mockoon (`ContraMockoonTest`).

### Contrato y mocks

`core/network` implementa el contrato [`contratos/bff-movil/openapi.yaml`](../../contratos/bff-movil/openapi.yaml):
`BffApi` (Retrofit), `Modelos.kt` (esquemas en snake_case) y `ErrorBff.desde(...)` para leer el
`codigo` estable de un error. El token de acceso se pasa con `BffProvider.create(tokenAcceso = { ... })`.

**Debug siempre simula la red**, incluso al cambiar la URL: `BffProvider` instala
`DebugMockInterceptor`, que responde con `MockBffMovil`, una implementación en memoria del contrato.
Nada se reenvía a Internet; una ruta desconocida devuelve 404 local. Release crea el cliente real
sin interceptor y requiere configurar la URL del ambiente.

| Escenario | Cómo provocarlo |
|---|---|
| Código OTP válido | `123456` |
| OTP incorrecto | Cualquier otro código: 3 intentos y el código queda bloqueado |
| Reenviar OTP | Esperar 30 s desde el último envío; antes responde 429 con `Retry-After` |
| KYC aprobada | Documento que termine en 0–6 |
| KYC rechazada (`datos_no_coinciden`) | Documento que termine en 7 |
| KYC en revisión manual | Documento que termine en 8 |
| KYC en proceso → aprobada en la siguiente consulta | Documento que termine en 9 |
| Cliente ya verificado | `ana@ejemplo.co` / `Solventa2026` |
| Correo o documento duplicado | Registrar `ana@ejemplo.co` o el documento `1020304050` |

El estado vive mientras dure el proceso de la app. Si cambia el contrato, actualizar `core/network`
y el mock en el mismo PR.

### Demo en emulador

`scripts/demo-emulador.sh` (Linux o WSL) y `scripts/demo-emulador.ps1` (Windows) dejan la app lista
para probar: arrancan el emulador si no hay uno encendido, compilan e instalan la app debug con datos
limpios, crean una cuenta por el flujo de registro (OTP `123456`) y activan la huella. Ubican cada
control por su texto visible con `uiautomator`, no por coordenadas.

```bash
mobile/android/scripts/demo-emulador.sh                 # opciones: --sin-compilar --sin-huella
```

```powershell
mobile\android\scripts\demo-emulador.ps1                # opciones: -SinCompilar -SinHuella -Correo ...
```

- Requiere un AVD (API 26+) y JDK 17+. En Windows, si `JAVA_HOME` es anterior a 17, el script busca
  un JDK instalado; en WSL usa el SDK y el emulador de Windows y firma con su llave debug para que
  `install -r` no falle frente a una app instalada desde Android Studio.
- La cuenta por defecto es `hernan@ejemplo.co` / `Solventa2026`; se cambia con variables
  (`CORREO=…`) o parámetros (`-Correo …`). El documento debe terminar en 0–6 (KYC aprobada).
- Para activar la huella, el emulador debe tener una registrada (Settings → Security →
  Fingerprint); si no la tiene, el script elige «Ahora no». Para entrar luego con la huella:
  `adb -e emu finger touch 1`.
- Registrar una huella obliga a tener PIN. Con la pantalla bloqueada, el script la desbloquea con la
  huella; justo después de arrancar el emulador Android exige el PIN, que se pasa con
  `PIN_EMULADOR=…` o `-PinEmulador …`.

`scripts/prueba-consentimiento.sh` y `.ps1` prueban MOB-F02 de punta a punta sobre esa demo (la
ejecutan primero, salvo `--sin-preparar`/`-SinPreparar`). Con las tres finalidades: otorgar (no se
envía sin entidad ni aceptación de la versión), revocar con y sin advertencia de tarifa, cancelar,
otorgar de nuevo un revocado y volver a Perfil. Para renovar uno por vencer y uno vencido adelantan
el reloj del emulador con `adb root` (imágenes Google APIs) y lo restauran al terminar;
`--sin-reloj`/`-SinReloj` omite esa parte. Las funciones comunes están en `scripts/emulador-comun.sh`
y `scripts/EmuladorComun.ps1`.

## Pipeline

`.github/workflows/mobile-android.yml` corre en push y PR que cambien `mobile/android/**`,
`contratos/bff-movil/**`, el router móvil del BFF (`bff/app/movil/**`) o el propio workflow, y permite ejecución manual. Respeta permisos mínimos, cancelación
por rama y publicación de reportes descritos en `../../docs/devops.md`.

1. Checkout con historial completo y **gitleaks-action v3**: los secretos detectados
   hacen fallar el job. Los comentarios automáticos están desactivados. Para repositorios
   de una organización se necesita el secreto `GITLEAKS_LICENSE`.
   Luego valida `contratos/bff-movil/openapi.yaml` con Redocly.
2. JDK 17, SDK 35/Build Tools 34.0.0, validación del wrapper y caché de Gradle.
3. **Lint → test → assembleDebug**: Detekt + Android lint; JUnit para cliente con
   MockWebServer (deserialización, ruta y errores), interceptor debug, tema y navegación Compose
   con Robolectric. Kover genera el XML agregado y exige cobertura total de líneas ≥ 70%.
4. Reportes lint/Detekt/JUnit/Kover y APK como artefactos, con porcentaje de líneas y resultado
   en el resumen del job.

El workflow no despliega ni publica la app. El check móvil es independiente de `ci-ok`
del CI general: agregar **Android · lint, test, assembleDebug** a los checks obligatorios
si se desea bloquear merges por este workflow. La protección de ramas se configura
manualmente en GitHub, como indica devops.md. El gate mide cobertura total; la medición diferencial del código nuevo queda pendiente.

## Verificación local y pendientes

En el entorno de creación solo hay JDK 8 y no hay Android SDK. Se incluyen wrapper,
comprobaciones de integridad y revisión estática; lint, JUnit, compilación Android y ejecución
en dispositivo requieren el entorno anterior o una ejecución verde del workflow.
No se considera compilación validada hasta tener esa evidencia.

Se descargaron los scripts y JAR del wrapper oficial 8.9 y se comparó su SHA-256 con
el publicado por Gradle. Pasaron el parseo de TOML/YAML/XML/JSON, las referencias del
catálogo, el orden de etapas del workflow, los nombres de navegación, `bash -n` del wrapper
y la revisión de whitespace. Los intentos de `--version` y de lint/test/assemble se detuvieron
durante la descarga de la distribución Gradle por su lentitud (el segundo con límite de 20 s);
**ninguna tarea Gradle llegó a ejecutarse**. La caché local de descarga está dentro del
repositorio y se ignora mediante `.gitignore`.

**MOB-F01.** Se ejecutó la secuencia del workflow con JDK 17 y Android SDK 35 / Build Tools 34.0.0:
`detekt lintDebug`, las pruebas de los cuatro módulos (6 + 3 + 77 + 30, sin fallos),
`:koverXmlReport :koverVerify` con **94 %** de líneas cubiertas y `:app:assembleDebug`, todo en verde.
La cobertura baja en `feature/onboarding/biometria` (55 %) porque `AlmacenTokenKeystore` y
`BiometriaAndroid` usan el Keystore y el sensor reales, que Robolectric no simula.

**WEB-F13 (#154).** Se agregó el modo real (`-PBFF_MOCK=false`), la renovación centralizada ante un
401 y las pruebas de objetivos táctiles de 48 × 48 dp del recorrido (`ObjetivosTactilesTest`). Falta la
evidencia contra *staging*, que depende de la URL pública de `solventa-bff` y de #155.

Pendientes: contratos OpenAPI de Harold, validación visual
con TalkBack/tamaños de fuente y pruebas de navegación en dispositivo. De MOB-F01 queda probar en
dispositivo (API 26–27 y 30+) el ingreso biométrico real: creación de la llave, activación, fallos
del sensor, invalidación al enrolar otra huella y rotación del token. El paquete y los
SDK elegidos son una base del sprint; revisar los requisitos de distribución antes de publicar.
