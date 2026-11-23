plugins {
    alias(libs.plugins.kover)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "co.solventa.feature.siniestros"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        // Almacén de borradores con el Keystore real (MOB-F05-02): emulador con API 26 y 34.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = true
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    buildFeatures { compose = true }
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
    // api: la app construye las dependencias del flujo con tipos de core/network (BffApi).
    api(project(":core:network"))
    implementation(project(":core:designsystem"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    // SavedStateHandle: el borrador y la clave sobreviven a la muerte del proceso (criterio 6).
    implementation(libs.lifecycle.viewmodel.savedstate)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.core)
    // Evidencia (MOB-F05): cámara, ubicación, EXIF y compresión de video.
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.play.location)
    implementation(libs.exifinterface)
    implementation(libs.media3.common)
    implementation(libs.media3.effect)
    implementation(libs.media3.transformer)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
