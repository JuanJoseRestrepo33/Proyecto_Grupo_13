plugins {
    alias(libs.plugins.kover)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "co.solventa.feature.polizas"
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
    // Billetera sin conexión (MOB-F03): cifrado, llave del Keystore y monitor de conectividad.
    api(project(":core:almacen"))
    implementation(project(":core:designsystem"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    // SavedStateHandle: el número de la póliza llega por la ruta del detalle.
    implementation(libs.lifecycle.viewmodel.savedstate)
    // FileProvider: el PDF se abre con el visor del sistema sin permisos de almacenamiento.
    implementation(libs.core)
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    debugImplementation(libs.compose.ui.test.manifest)
}
