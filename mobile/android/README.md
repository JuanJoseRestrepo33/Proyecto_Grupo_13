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

## BFF móvil y contratos pendientes

`BuildConfig.BASE_URL` se genera en `app` desde la propiedad Gradle `BFF_BASE_URL`:

```bash
./gradlew :app:assembleDebug -PBFF_BASE_URL=https://bff.example.com/v1/
```

Debe ser HTTPS, terminar en `/` y no contener credenciales, query ni fragmento.
El valor por defecto `https://mobile-bff.invalid/api/v1/movil/` es deliberadamente no resoluble;
**no es un backend desplegado**. No guardar tokens/secretos en propiedades ni BuildConfig.

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
`contratos/bff-movil/**` o el propio workflow, y permite ejecución manual. Respeta permisos mínimos, cancelación
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

Pendientes: contratos OpenAPI de Harold, URL real por ambiente, validación visual
con TalkBack/tamaños de fuente y pruebas de navegación en dispositivo. De MOB-F01 queda probar en
dispositivo (API 26–27 y 30+) el ingreso biométrico real: creación de la llave, activación, fallos
del sensor, invalidación al enrolar otra huella y rotación del token. El paquete y los
SDK elegidos son una base del sprint; revisar los requisitos de distribución antes de publicar.
