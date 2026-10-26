import java.net.URI

plugins {
    alias(libs.plugins.kover)
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val bffBaseUrl = providers.gradleProperty("BFF_BASE_URL").orElse("https://mobile-bff.invalid/api/v1/movil/")
val parsedBaseUrl = URI(bffBaseUrl.get())
require(parsedBaseUrl.scheme == "https" && parsedBaseUrl.host != null &&
    parsedBaseUrl.rawUserInfo == null && parsedBaseUrl.rawQuery == null &&
    parsedBaseUrl.rawFragment == null && bffBaseUrl.get().endsWith("/")) {
    "BFF_BASE_URL debe ser una URL HTTPS sin credenciales, query ni fragmento y terminar en /."
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
