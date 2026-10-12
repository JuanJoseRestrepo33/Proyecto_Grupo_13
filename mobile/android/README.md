# Solventa Android · ARQ-01 · Base móvil y pipeline

Aplicación nativa Kotlin/Jetpack Compose. Los mockups navegables del canal móvil están en `mobile-docs/`;
la raíz del proyecto Gradle es exclusivamente `mobile/android/`.

## Requisitos y comandos

JDK **17**, Android SDK con plataforma **35** y Build Tools **34.0.0**, y Android Studio
compatible con AGP 8.7.3. Gradle **8.9** se descarga mediante el wrapper incluido;
su distribución se valida con SHA-256. No se requiere Gradle instalado globalmente.

Abrir esta carpeta en Android Studio y seleccionar JDK 17 para Gradle. Configurar
`ANDROID_HOME` o `local.properties` (ignorado) con `sdk.dir=/ruta/al/sdk`.
Desde esta carpeta:

```bash
./gradlew detekt lintDebug
./gradlew :core:network:test :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

En Windows usar `gradlew.bat`. `installDebug` requiere un emulador/dispositivo API 26+
conectado; también se puede ejecutar `app` desde Android Studio. APK:
`app/build/outputs/apk/debug/app-debug.apk`.

## Estructura y decisiones

- `app`: Activity, composición de dependencias, NavHost y cuatro placeholders.
- `core/network`: módulo Kotlin/JVM; Retrofit, OkHttp y kotlinx.serialization,
  sin dependencia de Android. API suspend, timeouts explícitos y sin logging de cuerpos.
- `core/designsystem`: biblioteca Compose con colores, escala tipográfica, radios y
  espaciado del **Design System v2 PDF**, que tiene prioridad sobre el JSON v1.
- `feature/onboarding`: biblioteca vacía reservada para **MOB-F01**, sin biometría ni KYC.
  Las próximas features se agregan como módulos `feature/<nombre>` en `settings.gradle.kts`.

Dependencias: app → core y features. Core no depende de features ni de app.
Versiones centralizadas en `gradle/libs.versions.toml`; minSdk 26, compile/targetSdk 35,
bytecode y toolchain JDK 17. Detekt analiza Kotlin y scripts Gradle; Android lint falla
ante errores o advertencias, sin baseline ni supresiones generales.

P01 de `../../docs/patrones-ui-ux.md` exige cuatro destinos pero no enumera sus nombres.
Se conservan **Inicio, Pólizas, Avisos, Perfil**, según los mockups que referencia
(`../../mobile-docs/index.html`) y el sistema de navegación. Navigation Compose guarda/restaura estado
por destino, evita duplicados al reseleccionar y permite volver a Inicio con Atrás.
El NavController restaura su estado tras recreación de Activity. Las pruebas JVM cubren
resolución y selección de rutas; la interacción de Atrás y recreación necesita dispositivo.
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
El valor por defecto `https://mobile-bff.invalid/v1/` es deliberadamente no resoluble;
**no es un backend desplegado**. No guardar tokens/secretos en propiedades ni BuildConfig.

**Debug siempre simula la red**, incluso al cambiar la URL: `BffProvider` instala
`DebugMockInterceptor` con `app/src/debug/assets/mocks/health.json`. GET sobre el prefijo
configurado + `health` devuelve el fixture; todo lo demás devuelve 404 local y nunca
se reenvía a Internet. Las clases y fixtures de mocks solo pertenecen al source set debug.
Release crea el cliente real sin interceptor y requiere configurar la URL del ambiente.

`core/network/.../BffApi.kt` y `HealthResponse` son ejemplos técnicos provisionales
(`GET health` relativo al prefijo v1), **no contratos acordados ni endpoints de negocio**.
Cuando Harold publique OpenAPI v1 del BFF móvil, conectar sus contratos en
`core/network/src/main/kotlin/co/solventa/core/network/` (interfaces Retrofit y DTO
serializables) y sustituir el ejemplo según las rutas reales. Actualizar fixtures y pruebas;
las features consumirán interfaces inyectadas desde app, sin fabricar contratos por su cuenta.

## Pipeline

`.github/workflows/mobile-android.yml` corre en push y PR que cambien `mobile/android/**`
o el propio workflow, y permite ejecución manual. Respeta permisos mínimos, cancelación
por rama y publicación de reportes descritos en `../../docs/devops.md`.

1. Checkout con historial completo y **gitleaks-action v3**: los secretos detectados
   hacen fallar el job. Los comentarios automáticos están desactivados. Para repositorios
   de una organización se necesita el secreto `GITLEAKS_LICENSE`.
2. JDK 17, SDK 35/Build Tools 34.0.0, validación del wrapper y caché de Gradle.
3. **Lint → test → assembleDebug**: Detekt + Android lint; JUnit para cliente con
   MockWebServer (deserialización, ruta y errores), interceptor debug y selección de destinos.
4. Reportes lint/Detekt/JUnit y APK como artefactos, con resultado en el resumen del job.

El workflow no despliega ni publica la app. El check móvil es independiente de `ci-ok`
del CI general: agregar **Android · lint, test, assembleDebug** a los checks obligatorios
si se desea bloquear merges por este workflow. La protección de ramas se configura
manualmente en GitHub, como indica devops.md. No hay gate de cobertura Android todavía;
el umbral de cobertura del código nuevo de la definición de hecho queda pendiente.

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

Pendientes: contratos OpenAPI de Harold, URL real por ambiente, MOB-F01, validación visual
con TalkBack/tamaños de fuente y pruebas de navegación en dispositivo. El paquete y los
SDK elegidos son una base del sprint; revisar los requisitos de distribución antes de publicar.
