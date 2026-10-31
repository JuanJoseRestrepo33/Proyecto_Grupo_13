import java.net.URI

plugins {
    alias(libs.plugins.kover)
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val bffBaseUrl = providers.gradleProperty("BFF_BASE_URL").orElse("https://mobile-bff.invalid/api/v1/movil/")
val parsedBaseUrl = URI(bffBaseUrl.get())
// HTTP solo hacia la máquina local (el BFF simulado con Mockoon); el emulador la ve como 10.0.2.2.
val hostLocal = parsedBaseUrl.host in setOf("10.0.2.2", "localhost", "127.0.0.1")
require((parsedBaseUrl.scheme == "https" || parsedBaseUrl.scheme == "http" && hostLocal) &&
    parsedBaseUrl.host != null && parsedBaseUrl.rawUserInfo == null && parsedBaseUrl.rawQuery == null &&
    parsedBaseUrl.rawFragment == null && bffBaseUrl.get().endsWith("/")) {
    "BFF_BASE_URL debe ser HTTPS (HTTP solo hacia 10.0.2.2 o localhost), sin credenciales, query ni fragmento " +
        "y terminar en /."
}
// Debug responde con el mock salvo -PBFF_MOCK=false, que habla con el BFF del ambiente (WEB-F13).
val bffMock = providers.gradleProperty("BFF_MOCK").orElse("true").get().let {
    requireNotNull(it.toBooleanStrictOrNull()) { "BFF_MOCK debe ser true o false, no '$it'." }
}
require(bffMock || !parsedBaseUrl.host.endsWith(".invalid")) {
    "BFF_MOCK=false necesita la URL del ambiente: -PBFF_BASE_URL=<URL de solventa-bff>/api/v1/movil/"
}
require(bffMock || parsedBaseUrl.rawPath.endsWith("/api/v1/movil/")) {
    "BFF_BASE_URL debe terminar en el prefijo del router móvil: /api/v1/movil/"
}

android {
    namespace = "co.solventa.mobile"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = true
        // Avisos de "hay versión más nueva": se gestionan al actualizar el catálogo, no deben romper el CI.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable", "OldTargetApi")
    }
    defaultConfig {
        applicationId = "co.solventa.mobile"
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "BASE_URL", "\"${bffBaseUrl.get()}\"")
        buildConfigField("boolean", "BFF_MOCK", bffMock.toString())
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release { isMinifyEnabled = false }
    }
}
kotlin { jvmToolchain(17) }

kover {
    reports {
        variant("debug") {
            xml { }
            html { }
        }
    }
}

dependencies {
    implementation(project(":core:network"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:consentimiento"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    debugImplementation(libs.compose.ui.test.manifest)
    implementation(libs.compose.icons)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    // BiometricPrompt usa diálogos AppCompat en API 26-27: el tema de la app hereda de AppCompat.
    implementation(libs.appcompat)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.core)
}
