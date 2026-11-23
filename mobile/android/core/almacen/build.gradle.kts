plugins {
    alias(libs.plugins.kover)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "co.solventa.core.almacen"
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
    // api: MonitorConectividad expone un StateFlow.
    api(libs.coroutines.core)
    testImplementation(libs.junit)
}
